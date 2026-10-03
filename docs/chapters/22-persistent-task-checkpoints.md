# 第二十二章：任务与检查点双持久化

正常完成一轮后，关闭并重新启动应用，原任务仍可查看和续写。本章同时保存业务任务与 Graph 检查点：前者决定任务属于谁、当前能否运行，后者提供同一任务的历史状态。只把 MemorySaver 换成数据库保存器无法补齐任务归属与恢复关联。

## 页面与操作

打开 <http://127.0.0.1:18080/internal/draft-tasks>，在统一客服页使用已有 customer1001 / customer2002 登录。选择自己的 BOT 会话，创建任务，再发送本轮描述。创建本身不调用模型。正常完成后重启服务、重新登录同一账户，任务列表和最近完成的候选仍在；选中原任务后再发送修改，不重放浏览器历史。

第二十一章内存对照页保留在 <http://127.0.0.1:18080/internal/local-draft-tasks>，第二十章单次实验仍在 `/internal/draft-agent`。旧内存 taskId 不自动迁移成持久化任务；历史标签和既有数据库迁移不改动。

页面展示 taskId、业务版本、已使用轮次、最近正常完成轮次、历史消息计数、当轮事实和工具记录。RUNNING / RECOVERY_REQUIRED 下如存在旧候选，会明确标成上一正常完成轮次的快照；它不是本轮结果。页面不自动轮询或重跑未完成任务。结束普通任务只改变业务状态，保留数据库结果与检查点，之后隐藏候选并禁止继续。

## 代码入口

以下 Java 文件位于 `src/main/java/com/example/cloudcustomerservice/`。

| 文件 | 职责 |
| --- | --- |
| `agent/PersistentDraftTaskController.java` | 主入口、客户角色、Cookie/CSRF、当前 Session 与会话权限 |
| `agent/PersistentDraftTaskService.java` | 状态检查、单轮重建、检查点前后核对、结果保存、超时和迟到结果保护 |
| `agent/persistence/DraftTaskRepository.java` | 三秒短事务、任务所有权、版本 CAS、活动任务容量和业务快照 |
| `agent/persistence/PostgresSaverFactory.java` | 从同一 JdbcConnectionDetails 新建实际版本的 PostgreSQL 保存器 |
| `agent/DraftAgentFactory.java`、`AfterSaleDraftTools.java` | 复用前章有界 Agent 与只读工具；每轮新对象，重新核验订单和政策 |
| `src/main/resources/db/migration/V6__persistent_draft_tasks.sql` | 业务任务及官方保存器兼容表 |
| `src/main/resources/persistent-draft-tasks/index.html`、`static/persistent-draft-tasks.js` | 持久化任务页面，复用前章样式 |

保持 JDK 17、Boot 3.5.8、Spring AI 1.1.2、Spring AI Alibaba Agent/Graph **1.1.2.2**。文章的 1.1.2.0 示例按当前实际依赖 API 适配，没有升级或降级框架。PostgreSQL 驱动版本不变，scope 改为 compile，供工厂使用官方 JDBC URL 解析器。

## 两份持久化数据

| 数据 | 保存位置 | 作用 |
| --- | --- | --- |
| taskId / tenantId / userId / conversationId | `ai.cs_draft_task` | 业务定位和当前账户授权，不从历史消息推断身份 |
| threadId / orderNo / reason / agentProfile | 同上 | 稳定关联图历史、锁定业务范围、限制版本兼容性 |
| status / version / turnNo / runId | 同上 | 原子领取、每轮追踪、防止旧版本重复执行 |
| lastCheckpointId / lastResultJson | 同上 | 最近正常完成轮次的关联与业务快照；查询无需再次执行图 |
| Graph 线程与逐节点检查点 | `public.graphthread` / `public.graphcheckpoint` | 框架历史消息及控制状态 |

`RunnableConfig.threadId` 对应 `graphthread.thread_name`；表内 `thread_id` 是保存器另行生成的 UUID。检查点 `state_data` 保存 `{binaryPayload: "Base64..."}` JSONB，按 `state_content_type` 用框架序列化器恢复成带类型的消息。它不是普通的聊天 JSON，也不是加密。不要直接改成字符串列表或通过业务 HTTP 返回完整检查点。

固定契约为 `after-sale-draft-v1-saa1.1.2.2`。后续不兼容的提示词、工具或状态结构更改需要变更契约并设计迁移；不能把数据库中旧记录的版本号改成新版本就声称兼容。当前代码拒绝不同契约的续写，查看仍只读取旧业务快照。

## 一轮如何完成

```text
当前客户身份 → 自己的任务 → 当前 BOT 会话及版本 → 实例容量
  → 短事务 CAS：expectedVersion + 正常状态 + 契约 + 轮次 + 会话版本
  → RUNNING，新 runId，turnNo/version 各加一
  → 新 PostgresSaver 从数据库加载同一 threadId
  → 最新检查点必须等于业务 lastCheckpointId，且上一轮正常结束
  → 新 Agent / Tools / RunnableConfig / 预算，仅传本轮输入
  → 本轮重新核验事实，再读取模板，生成未经审核候选
  → 另一个新 Saver 重新读取数据库，确认新终态已持久化
  → 重查当前 Session、BOT 及会话版本
  → 短事务 CAS：完成状态 + 新 checkpointId + 结果 JSON + version 加一
  → 查询已存业务快照返回
```

每轮仍只调用一次 `agent.call`，内部可以循环调用模型/工具；不会在 call 后再次 invoke 来“查看结果”。正常第一轮版本从 0 → 1 → 2，第二轮从 2 → 3 → 4。前端 POST 必须携带最近读取的版本。相同版本的两个请求由 PostgreSQL 条件更新竞争，最多一个成功，无需依赖某个 JVM 的锁。

`PostgresSaver` 继承 MemorySaver 并缓存已加载状态。因此每轮创建新的 Saver，运行结束后还用另一新实例核对实际落库的最新检查点。只有数据库 ID 与运行实例 ID 一致、不同于上一轮、终点为 `StateGraph.END`、恢复出的用户消息数与轮次一致，才允许写业务完成状态。恢复保留的是 Graph 历史，Java 工具字段不从数据库复活；本轮核验结果必须重新获取。

`GET /tasks/{id}` 只读取业务行中的 `last_result_json`，不构造 Saver、不反序列化图、不查订单、不调用模型。先验证当前账户及会话权限，再展示历史快照；非 BOT 或 CLOSED 隐藏候选。查询不会自动“修复”任务。

## API

所有路径相对于 `/internal/draft-tasks`，仅 `local,knowledge` 同时启用时注册。页面可打开，API 需要客户登录，写请求使用登录后的最新 CSRF token；返回 `Cache-Control: no-store`。

| 方法与路径 | 请求与结果 |
| --- | --- |
| POST `/tasks` | `{conversationId,orderNo,reason}`，201 返回 Summary，初始 version=0 |
| GET `/tasks` | 当前账户最近 100 项元数据，含 CLOSED；不返回检查点/候选全文 |
| GET `/tasks/{id}` | `{task,lastCompletedTurn,lastRun,state}`，只读最近完成快照 |
| POST `/tasks/{id}/turns` | `{expectedVersion:0,message:"本轮补充"}`，一次显式续写 |
| POST `/tasks/{id}/close` | `{expectedVersion:2}`，结束正常状态任务，保留历史 |

`requests.http` 分别提供第二十一章旧内存 API 和第二十二章持久化 API，并自动记录 taskId / version。外部不得指定 tenantId、userId、threadId、runId 或 checkpointId；订单与初始原因固定，换订单需新任务。

| 状态 | 可执行的操作 |
| --- | --- |
| READY | 查看、开始、结束；重启后仍有效 |
| CANDIDATE_UNVALIDATED | 查看、继续、结束；候选仍未经审核 |
| NEEDS_ATTENTION | 本轮正常结束但事实/模板/候选不足；可补充后新一轮 |
| RUNNING | 查看最近已完成快照；禁止普通续写和结束，不因重启自动解锁 |
| RECOVERY_REQUIRED | 本轮未确认完成；查看已有快照，核查后另行处理，不提供普通续跑 |
| CLOSED | 保留元数据与数据库历史，隐藏候选，禁止继续 |

401/403 表示登录、角色或 CSRF 问题；404 对不存在和非本人任务一致；409 表示版本/契约/轮次/状态不允许操作；429 表示容量已满；503 表示本轮未确认完成或存储不可用，先 GET 查状态，不能自动重放 POST。

## 故障、事务和运行边界

- Repository 写入是独立的三秒短事务，模型执行明确禁止在事务内。Graph 保存器使用自己的 JDBC 连接并分别提交，和业务完成更新**不是同一个事务**。
- 图已保存但业务完成失败时，尝试 CAS 标记 RECOVERY_REQUIRED；标记也失败则留在 RUNNING。先前正常结果保留，部分新检查点不冒充完成结果。若业务完成实际提交而响应丢失，失败标记不会覆盖已提交结果；客户端 GET 可核对，旧版本重放返回 409。
- 进程被强制终止时，RUNNING 不在启动时重置为 READY。没有租约自动接管、后台重试、任意失败节点恢复或 exactly-once 业务提交保证。
- 结束任务不调用 `PostgresSaver.release()`。此 API 把图线程标记为 released，并非关闭连接；每轮调用会破坏后续加载关联。没有自动清理历史或无限期存储容量保证，生产需单独设计保留策略。
- 当前保存器 Builder 没有 `dataSource(...)`，只支持 host/port/database/user/password。工厂复用 Boot 的 JdbcConnectionDetails（含 Testcontainers 覆盖），拒绝会被 Builder 丢弃的 JDBC SSL/search_path/多主机参数，仅允许测试容器 `loggerLevel=OFF`。Flyway 创建表，`createTables=false`、`dropTablesFirst=false`。这个适配器只验证了本地单库连接，不声称沿用了 Hikari 的池参数或网络超时。
- 同任务版本竞争由数据库保护；每实例最多两任务执行、两个有界工作池。最多 100 项未关闭任务、每任务最多 8 轮、每轮 6 次模型和 8 次工具，90 秒总体等待/30 秒单次等待。远程调用或数据库底层等待不保证立即停止，超时后任务禁止自动再跑。尚无跨实例全局运行配额或生产容量验证。
- 按当前登录和会话权限恢复；完成前后复查会话，转人工/注销时迟到结果隐藏。任务快照提交并非与人工接待变更原子提交的正式发布操作；它不写正式客服消息。
- 登录 Session 仍在 JVM 内存，重启后需要重新登录。持久化候选不是正式版本化草稿，不包含审批、材料真实上传/读取、申请提交或退款执行；submitted / refundExecuted 始终 false。有限文本规则不能证明语义正确。
- `DASHSCOPE_API_KEY` 仍从环境读取。`app.ai.log-payload` 开启时打印真实模型请求/工具 Schema/返回，关联 `persistentDraft:<taskId>:<runId>`，不打印 Key；检查点与日志可能含业务正文，不提交公共 GitHub。

## 自动验证

```bash
RUN_PGVECTOR_TESTS=true RUN_ACCEPTANCE_TESTS=true \
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home ./mvnw package
node --check src/main/resources/static/persistent-draft-tasks.js
```

2026-10-04 全量 **391 项 Java 测试通过，0 失败、0 错误、0 跳过**；其中 53 项旧数据库集成测试和 39 项临时数据库验收，共 92 项真实 PostgreSQL。受控模型不消耗百炼额度。

`PersistentDraftTaskTest` 新增 20 项真实数据库/HTTP/Agent 验证：新 Saver 与新服务恢复带类型的历史、每轮重新核验、跨服务 CAS、旧版本重放、Cookie/CSRF/租户/账户/角色隔离、契约不兼容、缺失/损坏/额外检查点、模型失败、完成写入失败、提交后响应丢失、恢复标记失败、超时、轮次上限、结束保留、非 BOT 隐藏、迟到交接/注销。

`PersistentDraftRestartTest` 新增 1 项实际进程测试：专用临时数据库，启动 JVM A 创建 READY、完成和阻塞任务，强制终止 A，再启动 JVM B。验证 READY 能运行第一轮，完成任务查询结果相同且不增加模型调用，原 threadId 第二轮恢复两条用户消息、runId 更新、version=4，旧版本 409；被中断任务仍 RUNNING、不能自动续跑，正式消息数为 0。测试夹具只存在于 test 源码，不进入生产 JAR，也不增加绕过认证的测试 HTTP 接口。证明文件为忽略的 `target/chapter-22-process-restart.json`。

## 本机真实模型与页面验收

2026-10-04 在本机 18080、真实 Qwen / Embedding、现有教学订单与政策上操作页面。JVM 从 PID 76014 重启为 76258，登录 Session 随之失效；重新登录后，同一个已完成 taskId 的候选全文、runId、版本 2 和第 1 轮结果完全相同。此时新进程没有任何 `persistentDraft:` 模型调用日志，READY 任务仍可见。随后在原任务继续第二轮。

| 轮次 | 操作 | 状态 / 版本 | 模型 / 工具 | 检查点 / 消息 | 耗时 |
| --- | --- | --- | --- | --- | --- |
| 1 | 记录“右侧外壳开裂”并重新核验 | CANDIDATE_UNVALIDATED / 2 | 3 / 2 | 13 / 6 | 8759 ms |
| 2 | 重启后更正为“右侧按钮按不动” | CANDIDATE_UNVALIDATED / 4 | 3 / 2 | 26 / 12 | 8464 ms |

taskId 相同，两轮 runId 不同；第二轮 2 条用户、6 条助手、4 条工具消息。候选删除“外壳开裂”，保留“右侧按钮按不动”，质量仍为 UNVERIFIED；没有宣称已核验、已提交或已退款。上述数值是单次样例，不是跨版本协议或性能保证。实际崩溃保护使用前节受控模型的强制进程终止测试，未在用户工作数据库制造一个永远 RUNNING 的任务。

浏览器检查了结束确认的取消操作、1280 px 桌面、390 px 手机；无横向溢出、控制台无错误。内存对照实验保留。最后仅补充中文注释和切换任务时清空旧输入，重新打包、JS 语法检查通过，并在 18080 启动最终 JAR。

服务实际由终端启动，IDEA 当前仍是其他项目，自动点击项目切换没有生效。可在 IDEA 打开本项目、刷新 Maven、选择 JDK 17 和已有 `CloudCustomerServiceApplication` 运行配置；配置使用 `local,knowledge`、18080、环境变量 `DASHSCOPE_API_KEY` 和日志开关。IDEA 启动前需先停止本项目的终端实例，避免端口冲突。没有修改本机 Key、账号或知识库内容；V6 只增加本章表。
