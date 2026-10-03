# 第十九章：第一阶段可执行验收

本章交付一套可以重新运行、追踪配置与检查失败门槛的验收工具，不新增模型业务能力，也不把十八个演示自动升级为生产系统。真实用户认证、订单系统、人工聊天通道和生产容量仍需单独接入、验收。

## 从 IDEA / 终端运行

应用仍以 JDK 17、`local,knowledge` 和 `DASHSCOPE_API_KEY` 在 18080 启动。先在 IDEA 刷新 Maven，新增依赖仅限 test，不进入运行 JAR：`spring-boot-testcontainers` 与 `org.testcontainers:postgresql`，版本分别由现有 Boot BOM 管理。

项目根目录运行：

```bash
# Docker 需运行；旧章节集成测试使用已配置的 cloud_customer_service_test 库。
# macOS 可用 /usr/libexec/java_home -v 17 选择 JDK 17。
export JAVA_HOME=$(/usr/libexec/java_home -v 17)

# 仅新增数据库切片：不需要模型 Key。
RUN_ACCEPTANCE_TESTS=true ./mvnw -Dtest=HandoffAcceptanceTest test

# 完整离线回归：Java、浏览器解析器、报告评分器。
python3 scripts/accept-stage1.py

# 额外进行真实 Nginx HTTP 复测（需本机 nginx 可执行文件）。
python3 scripts/accept-stage1.py --proxy

# 加上真实模型效果实验：会消耗百炼额度。
python3 scripts/accept-stage1.py --proxy --live
```

`NGINX_BINARY` 可指定 Nginx 绝对路径。工具不会自行安装 Nginx，不启动登录项或后台系统服务。代理每项测试使用本机临时目录与随机端口，完成后关闭；不会放宽应用的回环访问限制。

报告写入 `.local/acceptance/<运行编号>/report.md` 和 `report.json`。目录权限由 umask 077 限制；数据库与模型明细、原始合成证据、回答、XML 和日志保存在该目录，不提交 Git，不默认打印到普通日志。入口会执行 `clean package`，因此正在运行的 JAR 应放在 `target` 之外。运行前确认旧章节专用测试库不是业务数据库。

默认不执行收费模型实验或 Nginx，报告相应列明确标为 `NOT_EXECUTED`；这不是通过。失败命令保留退出码，失败硬门槛导致非零退出。即使离线命令以 0 退出，也应读取门槛状态，缺失代理证据时工程验收仍未完成。

## 临时 PostgreSQL 与真实事务

`acceptance/AcceptanceDatabase` 用 `@ServiceConnection` 启动新的 pgvector 容器，固定到项目原镜像摘要，使用随机端口和 `stage1_acceptance` 数据库，无数据卷。Spring Boot 根据连接详情覆盖本地数据源设置；新测试先验证库名，并执行项目正式 V1～V5 Flyway 迁移。

`HandoffAcceptanceTest` 使用 `@JdbcTest`，只导入真实 HumanHandoffService、方法权限代理、ObjectMapper 和数据库。没有 AI Bean，不需要模型可用。全量回归曾发现 Testcontainers 传递 commons-compress 1.24.0，使既有 POI 的 DOCX/PPTX 测试发生 NoSuchMethodError；已在 dependencyManagement 统一到第十八章运行时实际使用的 1.28.0，保留失败记录并重新执行全量。测试类使用 `NOT_SUPPORTED`，取消的是测试方法外层事务；服务方法仍各自开启并提交真实事务。

七项新增检查包括：重复受理、跨用户/租户与伪造 Actor、客服权限、结束后不能重开、两个申请竞争、两个客服竞争，以及实际迁移清单。身份使用项目自己的 HandoffPrincipal，与 Actor 对应；它证明服务层权限，不冒充 HTTP 登录验收。

两项竞争测试先用独立事务持有会话行锁，启动另两笔事务，通过 `pg_blocking_pids` 确认真实数据库等待，再释放锁并断言结果。Awaitility 只提供有界轮询与超时，不使用睡眠推测是否发生竞争。

前十七章正式发布门测试继续执行：用门闩确认生成已开始，提交人工状态，再允许旧候选返回，断言数据库未保存正式旧答案；已经发布的早期消息则作为历史保留。

## 固定夹具与真实模型实验

[夹具说明](../../src/test/resources/acceptance/README.md) 固定测试时钟、当前/旧版/其他租户政策、上传版本和订单。当前项目用 A10002 表示属于用户 2002 的订单，保留既有样例，不照抄文章的 A20002 改写业务数据。

`StageOneLiveExperiment` 单独启动临时数据库和随机端口的真实 Tomcat，使用真实 Qwen 与 Embedding：

1. 导入七条合成政策，包含归档旧版和其他租户干扰项。
2. 走正式 Reader/切分/Embedding/入库流程新增编码资料，检查向量和关键词检索。
3. 导入同来源 2.0，验证旧编码消失、版本及来源仍正确，再归档该资料，避免影响固定政策题集。
4. 三个知识问题记录查询转换、多路召回、融合候选、排序及最终 Prompt 证据、实际 UUID 和版本、答复、耗时。
5. 真正通过 Cookie 和 CSRF 登录统一入口，依次执行问候、自有订单、他人订单、质量问题预检查和转人工，读取正式消息发布结果。

这五个正式 HTTP 场景与内部知识实验分开标注，后者不能替代完整业务入口。订单仍为模拟数据。售后 Clock 固定为 2026-08-30T02:00:00Z；接待时间是数据库真实提交时间，不篡改生产日期。

## 证据组评分，不用相关关键词充当正确答案

知识标注位于 `knowledge-cases.json`。证据标识为 `sourceId@sourceVersion#chunkIndex`，每组允许多个等价 ID。实际报告仍保存具体 Document UUID 以便定位。

例如三组必需证据只找到两组：组覆盖率为 2/3，整题完整覆盖为失败。没有标注的题不计算该指标，没有执行的阶段为 null / `NOT_EXECUTED`，不能记 0 命中或默认通过。

报告分别计算 `vectorBranches`、`hybridCandidates`、`ranked`、`finalPrompt` 的覆盖。评分函数检查证据范围、结构化订单/售后结果和正式发布回执；不会用 `answer.contains("退款")` 或另一个模型的认可替代状态和权限检查。

`requiredMeaning` / `forbiddenMeaning` 用于后续语义评审。当前自动报告将语义完整性、事实逐句支持和错误拒答率标为未执行；来源覆盖通过不等于整段话已经核验。没有引入收费的模型裁判或虚构“人工通过率”。

## 真实 Nginx 流式验收

SseHttpIntegrationTest 可在 `RUN_NGINX_TESTS=true` 时把同样的九项 HTTP 测试经过真实 Nginx 再执行一次。上游 ChatModel 是可控替身，数据库、Cookie、CSRF、Tomcat、代理和网络客户端是真的。测试包括首片段先于模型结束、中断失败、退出、断线取消、连接计数归零、限额与重连只读。

发现并修正了验收脚本的一个错误假设：Nginx 默认消费 `X-Accel-Buffering` 控制头，不回传给客户端。直连继续检查此响应头；代理链路依据实际事件到达和取消行为判断，不因缺少该头错误判失败。没有为此关闭应用权限或启用信任任意 Forwarded 头。

另外运行真实模型经过本机 18081 Nginx 的小样例，记录首个 `answer.delta` 耗时；不会拿立即发出的 `turn.started` 当作首字延迟。章节十八脚本继续可用：

```bash
python3 scripts/check-sse.py --base http://127.0.0.1:18081 --model
```

需要先按参考配置启动代理。该命令会在本地教学库创建并结束一条合成验收会话，不触碰用户知识文件。

## 报告与发布门槛

`manifest.json` 记录 Git 基础提交、是否含未提交改动、全部业务/测试/脚本文件 SHA-256、POM 版本、Java 版本、迁移与镜像信息。测试时存在尚未提交的本章代码，因此内容哈希用于确定实际被测版本；提交后可对照相同内容。生成过程中代码变化会导致验收失败，旧 Surefire XML 不会混进新一轮。

硬门槛绑定真实测试类/方法：身份范围、只读售后、人工幂等及竞争、迟到回答拦截、故障合同、真实 HTTP 流与 Nginx。缺少测试、跳过或失败各有明确状态，不用平均分冲抵越权。

效果记录与硬门槛分开：覆盖率、各题耗时、小样本最近秩 P50/P95。不同类型请求不合并承诺统一耗时；模型调用总数、Token、数据库查询数未完整采集的字段为 null。单并发小样本不推出生产容量。

尚未完成的发布条件：统一保护历史实验接口并接入生产 IAM；真实订单及人工消息通道；配置并验收云端精排；业务认可的代表性题集和语义效果门槛；生产并发、监控、灰度及回滚演练。幂等已验证，但未注入真实 TCP 丢失人工申请 POST 响应。能力开关和多实例全局资源限制仍为后续工作；关闭 AI 不应回滚已受理人工状态。

实际执行结果见本节末尾和 [公开汇总](../acceptance/chapter-19-summary.json)。生产发布结论保留 **BLOCKED**，不在本章直接部署或开放公网。


## 2026-10-04 实际执行结果

运行编号：`20261004-002941-fa737f66`。工程硬门槛 **PASS**，生产发布 **BLOCKED**。

| 验收层 | 实际结果 |
| --- | --- |
| 全量 Java（含新容器切片） | 325 通过，0 失败，0 跳过；其中 60 项使用真实 PostgreSQL |
| Nginx 再执行真实 HTTP 流测试 | 9 通过 |
| 真实模型隔离库实验 | 1 项实验通过，含 3 道知识题及 5 个正式 HTTP 场景 |
| 前端 SSE 解析 | 122 通过 |
| 报告评分器 | 11 通过 |
| 知识证据覆盖 | 4/4 必需证据组，2/2 有标注问题完整覆盖 |
| 无资料题 | NO_EVIDENCE；不以此单题计算整体错误拒答率 |
| 云端重排序 | FALLBACK_NOT_CONFIGURED，未执行云端排序 |
| 语义人工标注 / 生产容量 | 未执行 |

RAG 三题耗时 3291 / 5506 / 400 ms。正式问候 / 自有订单 / 他人订单 / 售后预检查 / 申请人工分别为 44 / 796 / 857 / 5861 / 13 ms。均为单并发小样本，不能推出生产 SLA。售后返回 NEED_QUALITY_VERIFICATION、refundExecuted=false，程序保护后的正式答复未宣称已经批准或退款。

另一次真实 Qwen 经过本机 Nginx 的观察为 16 段，首个文字片段 285 ms，总计 1480 ms；此观察使用前章相同业务运行代码，与本轮受控模型的 9 项代理测试分开记录。没有把不同执行的数字合并为同一轮模型结果。

本机通过 Homebrew 安装 Nginx 1.31.6，仅使用临时配置进程做验收，未设置后台登录服务；临时代理测试完成后关闭。Docker 宿主网络模式在本机不可达，因此未放宽应用访问过滤来绕过限制。

被测基础提交为 `c33b9829a23ba7a7f84a52f5662d55d9eeb39820`，测试时包含本章未提交改动；业务/测试/脚本内容指纹为 `10d19a6b786bd08a4ea925c9934455e452ce64b4d3ab581f117964f015992a0b`。公开汇总保留这种状态，不虚构尚不存在的提交号。提交后的同一文件内容可对应此指纹。

最终打包版本已从终端在 18080 启动，统一客服和流式实验页面均返回 200。IDEA 配置保持 JDK 17、local,knowledge 和相同端口；此前的自动切换窗口问题仍未解决，本章没有新增 IDEA 内启动成功的证明。
