# 第三十章：整项售后任务的第二阶段验收

本章收拢第20～29章已有能力，增加真实跨服务验收、故障代理、只读证据断言和报告页面。没有增加新的 AI 提交权限，也没有修改 V1～V10 迁移、投递退避、业务授权或退款规则。

## 验收对象与实际接线

用户的输入先进入 `PersistentDraftTaskService`，Agent 只获得 `inspectAfterSale` 和 `readDraftTemplate` 两个工具。候选和检查点持久化，结构化正文由 `DraftApplicationService` 发布为不可变版本；用户确认的是具体 `taskId + draftVersion`。

正式创建从 `SubmissionController` 的显式 HTTP 审批进入 `SubmissionGraph`，随后调用受 Spring 事务代理保护的 `IdempotentSubmissionService`。服务检查身份、版本、内容确认、接待状态、审批与期限；申请、批准消费、任务关闭和 Outbox 在同一事务中提交。之后独立接收服务持久化 Inbox、申请、固定回执，客服可只读查询原事件再补记送达。

**第24章 `SubmissionProbe` / `HitlLabSession` 仍是实验。** 它们不会调用正式申请服务，不能用模拟计数证明真实提交。正式 Agent → 持久化 HITL → 正式提交工具的整体接线保留为 **BLOCKED**。已实现 HTTP 链路可以单独通过，完整 V2 和生产发布不能因此被标成通过。

## 四个里程碑与五项不变量

| 里程碑 | 已经发生 | 还不能声称 |
| --- | --- | --- |
| M1 | 用户明确确认 V2 内容 | 已批准创建或对外投递 |
| M2 | 本地申请与 Outbox 已提交 | 远端收到、审核通过 |
| M3 | 接收端 Inbox、申请和回执已提交 | 发送方已经知道成功 |
| M4 | 发送方已保存匹配原事件的远端回执 | 退款完成 |

验收分别检查：身份与订单范围；确认正文与实际入库正文；两端至多一份业务记录；未知状态的诚实表达；恢复过程不换号、不改意图、不扩权。数据库 `count(*)=1` 只是其中一个条件，不能代替正文和身份链一致。

## 新增代码

| 文件 | 作用 |
| --- | --- |
| `scripts/stage2/AckLossProxy.java` | 纯 JDK17 本机故障代理；先等接收端完整成功，再把目标事件前 N 次回执替换为503 |
| `scripts/stage2/AckLossProxyTest.java` | 真实本机 HTTP 的15项代理合同测试，与业务验收分开 |
| `scripts/accept-stage2.py` | 建立独立库、运行两套生产 JAR、真实登录/CSRF/JWT、真实模型和真实退避；保存本轮证据 |
| `scripts/stage2/sender.sql` / `receiver.sql` | 分别在本库只读、可重复读事务中采集稳定状态；保留各自时间戳 |
| `scripts/stage2/evidence.py` | 对冻结预期与两端观察逐字段核对；缺行、多行、错版、错身份均失败 |
| `scripts/summarize-stage2.py` | 按本轮新鲜 JUnit 报告汇总组件覆盖；保留缺失、跳过、失败和阻塞 |
| `scripts/tests/test_stage2_evidence.py` | 验收器自身的7项负例测试，防止缺失数据或“数量正确、内容错误”被误判通过 |
| `static/stage-two.html/.css/.js` | 四个里程碑、身份坐标、硬门槛筛选、模型样本和发布缺口；支持本地 JSON 导入 |

报告页为 `/stage-two.html`，首页已有入口。页面只读随版本保存的合成验收摘要，**不是实时监控**；上传选择的 JSON 只在当前浏览器解析，不传到服务器。原始正文、检查点、账号、JWT、Cookie 不进入公开报告。动态字段使用 `textContent`，不执行报告中的 HTML。

## 运行方式

需要 JDK17、Python3、已启动的本机 pgvector 容器和 `DASHSCOPE_API_KEY`。主服务仍使用18080，常规接收服务18083；验收默认只占18085、18086、18084。端口占用时直接失败，不终止其他项目。

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home
RUN_PGVECTOR_TESTS=true RUN_ACCEPTANCE_TESTS=true ./mvnw -B package
RUN_RECEIVER_TESTS=true ./mvnw -B -f after-sale-receiver/pom.xml package

mkdir -p target/stage2
"$JAVA_HOME/bin/javac" -d target/stage2 scripts/stage2/AckLossProxy.java scripts/stage2/AckLossProxyTest.java
"$JAVA_HOME/bin/java" '-DsocksNonProxyHosts=localhost|127.*|[::1]' -cp target/stage2 AckLossProxyTest
python3 -m unittest discover -s scripts/tests -p 'test_*.py'

# 真实调用百炼；新增合成任务、独立数据库和随机教学凭证。
python3 scripts/accept-stage2.py --live
```

脚本输出 `.local/stage2/<runId>/report.json`。使用显式本轮目录进行汇总：

```bash
python3 scripts/summarize-stage2.py .local/stage2/<runId>
```

汇总器只收集**本轮开始时间之后**的 JUnit 报告，因此初次运行上述命令时，应在端到端验收开始后重新执行两个 Maven 回归，或在它开始后并行运行回归，再汇总。它不会把前一天的 XML 当成新证据。完整复测可先打包，再启动端到端验收，随后在另一个终端执行两套回归。验收进程使用启动时复制的 JAR，不受后续 Maven 打包替换影响。`reviewed-report.json` 保留详细组件信息；`public-report.json` 是可供页面载入或人工审阅后附到版本的白名单副本，不会自动推送 GitHub。

默认代码中不含真实凭证。脚本沿用本机 Docker 容器 `cloud-customer-service-postgres-1` 的管理连接，只为本轮创建两个随机名称的数据库和独立角色，预建所需扩展后让正式 Flyway 迁移建表。两应用使用不同库和角色。进程退出会停止全部专用 JVM 与代理；证据、数据库、角色保留供复核。核对完成后，可按报告中的精确数据库名和角色名手动清理，脚本不自动删除证据。它不改常规18080的配置或投递地址。

## 主验收如何排除假成功

1. 固定用户1001、订单A10001、租户tenant-yunshan、质量UNVERIFIED、producer `yunshan-customer-service`。V1描述“外壳开裂”；真实 JVM 重启后继续同一任务，更正为V2“右侧按钮按不动”。
2. 先拒绝旧版V1确认，再明确确认V2。提交前写出 `expected-before-execute.json`，冻结所有者、订单、版本、操作号和用户已看到的完整正文；提交后的库不能反过来定义期望。
3. 在没有 relay 的进程中明确批准 `AFTER_SALE_V1`，通过两个真实登录会话并发执行。同操作只创建一份申请、一条 Outbox；允许实际锁超时返回待核查，再用原编号读权威回执。
4. 获取真实 eventId，启动 JDK 代理，仅命中该 Idempotency-Key。接收协议校验 header 与正文 eventId 一致；代理只在上游200/201完整响应之后注入503。原正文、Authorization、Idempotency-Key、Content-Type保留，lookup不注入；401/403/失败/其他事件不消耗成功预算。
5. 启用真实 relay，用生产5～300秒指数退避及抖动，直到八次失败耗尽进入REVIEW。没有SQL写入REVIEW、没有修改attempt_count/next_attempt_at/租约或业务时钟。
6. 在REVIEW采集两个只读快照；再真正重启发送方，重新登录support9001，显式核查原事件一次。核查后采集M4快照，验证创建请求仍为8、查询为1，两端仍各一份申请。
7. 核对V2完整正文、本地body_snapshot、Outbox userStatement、Inbox完整JSONB、接收申请来源编号与陈述、原回执和核查审计。发送方 remoteApplicationId 必须等于接收方真实主键。

代理请求/响应最多64KiB，上游完整响应8秒、整个交换10秒有界，固定127.0.0.1目标，不跟随重定向；并发计数保证恰好注入N次。它是“成功回执替换为错误”的可控模拟，**不是精确TCP丢包实验**。附件中提到的 sandbox ZIP 没有提供，本仓库工具为本章独立实现，不沿用文章中的13个用例/46条断言数字。

## 组件证据与语义证据

组件层重复执行草稿发布×确认、审批过期/拒绝/换版/跨用户租户、提交×新轮领取、Outbox插入失败整体回滚、旧租约回写、旧NOT_OBSERVED晚于新PERSISTED、防护审计原子性及真正子JVM恢复等测试。部分故障用模拟远端或人工调到期时间帮助验证单一合同，只计为组件证据；本章主链另用两套生产JAR、两套数据库和真实退避。

本章补充了提交×新轮领取两个锁顺序的真实数据库测试；用实际`pg_stat_activity`锁等待作屏障，分别验证任务CLOSED/一份申请，或RUNNING/零份申请，不能同时成功。

六组真实模型输入分别覆盖更正描述、否定词、只准备、照片声明、他人订单和确认但暂不发送。程序记录工具、模型调用次数、候选状态和没有隐式授权的数据库事实；语义另由Codex逐项读实际回答，保留评审者及备注，**不冒充人工业务签字或总体正确率**。状态码/Schema合法不等于中文含义已验证。模型供应商故障或回答偏差须保留在对应样本，不能重跑后覆盖失败历史。

## 运维处理表

| 情况 | 负责人角色 / 入口 | 可执行动作 | 不能做 |
| --- | --- | --- | --- |
| REVIEW、HTTP_503、远端结果不明 | `support:reconcile`，同步核查台 | 保留原eventId，显式查询原正文并查看审计 | 新建操作或强制标送达 |
| PERSISTED且身份/正文/回执全匹配 | 核查服务 | 原子补记DELIVERED，记录操作者 | 声称退款或审核通过 |
| NOT_OBSERVED、查询401/403/不可用 | 核查人员与接收服务维护者 | 查服务权限、数据库和日志，保留REVIEW | 把“没观察到”译成“没创建”，自动重发 |
| PAYLOAD_CONFLICT / RECEIVER_INCONSISTENT | 两端服务维护者 | 根据原证据核对协议、业务唯一约束及事务 | 修改原事件正文绕过去重 |
| STARTED / STALE | 核查人员 | 读取原审计；需要时发起新的显式只读核查 | 用旧结果覆盖较新状态 |
| 用户更改内容或投递范围 | 原用户 | 新版本内容确认与明确操作授权 | 恢复流程替用户扩大授权 |
| RUNNING / RECOVERY_REQUIRED | 任务维护者 | 按原检查点与业务状态查证 | 将不完整图自动重跑称为恢复成功 |

生产值班负责人、告警渠道与触发时限尚未指定，状态为NOT_RUN；没有凭空设定SLA。旧实验接口、真实订单/附件/资金系统、正式Agent/HITL接线、业务人工审查、生产容量及灰度仍是发布缺口。

## 本次实际验证

真实运行编号：`5d89a070-7baf-4d3f-bac8-9de0242a8590`，2026-10-04 14:49～14:59（北京时间）。

- 主任务 `55fa6fbd-fad1-4da8-b64a-1fdb7e81d6b8`，V2；操作 `3d2017e1-f5b9-4665-bf55-923dabeb0d6f`；事件 `0e7bb5c2-6b70-40af-9ecb-a3010499c894`。
- 55项跨服务硬断言全部通过，8次创建请求 / 8次成功回执屏蔽 / 1次只读核查。REVIEW与M4两个阶段完整核对正文和身份链。
- 6组真实Qwen样本本轮安全约束通过，Codex文本复核通过；人工业务复核未执行。
- Java17.0.20.1、Boot3.5.8、Spring AI1.1.2、Spring AI Alibaba1.1.2.2；生产业务JAR来自第29章实现，JAR与源码哈希及迁移checksum保存在报告中。本章修改验收工具、测试和页面，业务实现保持相同。
- 首次接收端启动被本机SOCKS设置影响，回环直连修复后另起新run保留失败记录。首次根回归发现旧HITL测试的250ms冷启动时序假设；改为实际工作者进入屏障和5秒超时预算后重跑，不放松业务断言。

最终发送端585项、接收端65项，共650项Java测试通过（含214项真实数据库测试）；15项代理合同测试和21项Python测试通过。端到端使用的JAR与最终包逐项比较业务类、非静态资源和依赖完全一致。页面验证见README及同版本 `docs/acceptance/chapter-30-summary.json`。原始只读快照与模型轨迹仅在 `.local/stage2/<runId>/`；公开摘要不含凭证及完整正文。PASS仅限相应证据范围，完整V2及生产发布仍BLOCKED。

页面实测：桌面1280px与手机390px布局通过，390px页面宽度和内容宽度均为390，无横向溢出；未通过项筛选、有效JSON载入、错误JSON拒绝且保留旧结果、刷新恢复版本报告均通过，浏览器无控制台错误。当前主服务18080、接收服务18083由终端启动，故障代理及专用验收JVM已停止；IDEA仍在其他项目且自动点击未生效，未宣称IDEA内运行成功。
