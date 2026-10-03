# 第二十一章：进程内任务续写与检查点隔离

同一售后任务现在可以连续补充和修改描述。第二十章仍保持每次新运行；第二十一章复用同一任务的 Agent、工具对象、MemorySaver 和内部 threadId，每轮生成新的 runId 与预算。章节没有引入数据库任务表，应用重启后无法恢复。

> 第二十二章已将主入口切换为持久化版本。本说明中的 URL 已同步为当前内存对照实验地址；历史 `chapter-21` 标签仍使用 `/internal/draft-tasks`，下面的旧章验收数字对应当时版本。

## 打开与使用

打开 <http://127.0.0.1:18080/internal/local-draft-tasks>，在统一客服页用本机 customer1001 / customer2002 登录。选 BOT 会话（可以新建），输入订单与初始原因，点击“创建任务”；创建本身不调用模型。然后连续点击“继续此任务”：

1. “商品使用时插头发热，请核验订单与政策，整理候选草稿，不要提交。”
2. “刚才描述有误，改为外壳破损，删除插头发热的说法。重新核验后更新候选。”
3. “我已上传照片，请记录为用户声明；你没有读取照片，不要说已经核实质量。”

同一个 taskId 的 turnNo 递增，每轮 runId 不同。刷新页面通过任务 API 读取最新结果，不把浏览器消息重新拼入模型请求。用户声明已上传照片并不等于系统收到了附件，本章没有附件上传或读取接口。

每轮最新候选替换前一轮展示；事实不足、异常或接待状态变化时隐藏候选。“清理本地任务”释放当前任务及检查点，不删除正式会话、知识库或审计记录。清理后的 taskId 返回 404。

## 代码入口

文件前缀为 `src/main/java/com/example/cloudcustomerservice/` 或 `src/main/resources/`。

| 文件 | 职责 |
| --- | --- |
| `agent/DraftAgentFactory.java` | 统一真实 ReactAgent 的构造、两种提示词、两个只读工具、模型调用上限；无共享任务状态 |
| `agent/LocalDraftTaskService.java` | 内存注册表、任务所有权、并发门锁、单轮运行与检查点纯读取 |
| `agent/DraftTaskModel.java` | 外部业务任务、状态、单轮回执、检查点计数摘要 |
| `agent/LocalDraftTaskController.java` | Cookie / CSRF / 客户权限，当前 Session 身份与接待查询适配 |
| `agent/AfterSaleDraftTools.java` | 新增服务器专用 `beginTurn`：重置上轮核验缓存、模板标记、预算、调用数和工具记录 |
| `agent/DraftCandidateGuard.java` | 续写额外拦截明显换单、假称读取照片、已提交等宣称；有限规则不代表完整语义审计 |
| `draft-tasks/index.html`、`static/draft-tasks.*` | 当前账户任务列表、创建/继续/查看/清理、候选与实际调用记录 |

保留 Boot 3.5.8、Spring AI 1.1.2、Spring AI Alibaba Agent/Graph 1.1.2.2，不升级依赖。章节共用工厂，第二十章仍每次新建 Agent/MemorySaver，并通过原有测试回归。

## 四类编号与生命周期

| 编号 | 创建时机与用途 | 是否接受客户端指定 |
| --- | --- | --- |
| conversationId | 既有正式客服会话；校验所有者及 BOT 接待状态 | 创建任务时指定，但逐次验证归属 |
| taskId | 服务器创建的业务任务 UUID；定位内存运行环境 | 后续请求引用，不能自定义创建值 |
| threadId | 任务创建时绑定，稳定定位此任务的 Graph 检查点 | 不接收，也不对外返回 |
| runId | 每次接受续写时新建；日志与单轮结果关联 | 不接收 |
| checkpointId | 框架在运行中生成的状态快照标识 | 本章不开放选择/恢复任意检查点 |

一个会话可以创建多个互相隔离的任务。相同用户并不共用 threadId。知道 taskId 也不是授权，先检查租户和账户，才读取该任务的检查点。

```text
新建：当前客户 → 自己的 BOT 会话 → 新 taskId / threadId / Agent / Tools / MemorySaver
续写：当前客户 → 任务归属 → tryLock → BOT 状态版本 → 新 runId / RunnableConfig / 预算
    → beginTurn 清理 Java 字段 → agent.call(本轮补充，同一 threadId)
    → inspectAfterSale 重新核验 → readDraftTemplate → Java 检查候选
    → 重查 Session 与接待版本 → 返回最新结果
查看：当前客户 → 任务归属及会话权限 → MemorySaver.get/list → 计数摘要
清理：当前客户 → 任务归属及会话权限 → 无运行 → release(threadId) + 移除注册表
```

`MemorySaver` 保存 Graph 检查点中的消息等状态；任务注册表保存活的 Agent、工具实例和 Saver 的引用。它不会自动序列化工具对象上的 `assessment`、`templateRead` 等普通字段。`beginTurn` 重置字段但不清除 Graph 消息，因此历史能用于理解描述，本轮事实必须新查。历史里的一次 inspect/template 调用不能满足本轮 Java 校验。

每轮只调用一次 `agent.call`，内部由图完成模型/工具循环。摘要直接调用 Saver 的纯读取 API，不在 `call` 后再次 `invoke`。无 ChatMemoryAdvisor，也不在浏览器/控制器手工重放完整历史。摘要给出检查点数、消息总数及用户/助手/工具消息数，不输出完整检查点正文或模型思维链。

## API 与状态

所有 API 都在 `/internal/local-draft-tasks/tasks` 下，仅在 `local,knowledge` 同时启用时注册。页面本身可打开，操作要求客户登录；写请求需要登录后新取得的 CSRF token。

| 方法 / 路径 | 请求 / 含义 |
| --- | --- |
| POST `/tasks` | `{conversationId,orderNo,reason}`，201 返回 READY 任务；不调用模型 |
| GET `/tasks` | 当前所有者任务元数据列表；不返回候选或历史正文 |
| GET `/tasks/{taskId}` | 任务、最新一轮结果、只读检查点摘要 |
| POST `/tasks/{taskId}/turns` | `{message}`，仅当前轮 1～2000 字符；一次显式运行 |
| DELETE `/tasks/{taskId}` | 清理本地任务及检查点，204 |

上表 `/tasks` 是相对于 `/internal/local-draft-tasks` 的路径。`requests.http` 提供完整 URL 与 IDEA HTTP Client 自动保存 taskId 的示例。

订单格式为 A 加五位数字；原因支持 QUALITY_ISSUE / CHANGE_OF_MIND / UNKNOWN。身份、订单和初始原因固定在任务里，续写不能靠提示词换单或换身份。要换业务范围就创建新任务。

| 任务状态 | 行为 |
| --- | --- |
| READY | 尚未调用模型，可开始 |
| RUNNING | 正在处理，同任务的其他续写/清理返回 409；GET 只返回运行元数据 |
| CANDIDATE_UNVALIDATED | 已实际完成本轮核验与模板读取，得到未经审核的候选 |
| NEEDS_ATTENTION | 缺事实/政策/模板或候选被有限规则拦截，可补充后发起新一轮 |
| FAILED | 模型/框架错误、次数/时间上限；可能残留部分检查点，禁止普通续跑 |
| CLOSED | 接待或身份状态变化，隐藏候选及事实，禁止继续 |

GET 发现非 BOT 会话时也隐藏已有结果并显示 CLOSED。任务列表仅是内存状态概览，选择任务后重新检查当前接待状态。401/403 为认证、角色或 CSRF 问题；404 为不存在或非自己的任务/会话（含重启清空）；409 为并发、终止状态、八轮上限或非 BOT；429 为容量限制。返回 `Cache-Control: no-store`。

返回结构为 `{task,lastRun,state}`。`lastRun` 复用第二十章回执，包含候选、确定性事实、实际工具步骤、次数与耗时；submitted/refundExecuted 永远为 false。转人工/注销发生在生成期间时，迟到结果不可再展示；不向正式客服消息表追加模型文本。

## 有界资源与明确限制

- 最多保留 100 个任务，每个最多 8 轮；满额时由用户清理，没有静默淘汰。
- 第二十一章最多两个任务并行。同任务以 `tryLock` 明确拒绝并发，不排队偷偷合并两条输入；容量拒绝不消耗轮次。
- 每轮最多 6 次模型调用、8 次工具调用，90 秒整体等待，单次模型/业务查询最多等待 30 秒；1800 输出 tokens、12000 候选字符。模型计数是适配器调用次数，不等于供应商计费次数。
- 任务运行与远程等待不持有数据库事务；只有短会话权限查询使用已有数据库逻辑。
- 超时停止后续调用；底层网络操作不一定立即退出或停止计费，因此运行工作尚未结束时不能清理或再次执行任务。工作线程固定有界。
- FAILED 不自动重试、不选择失败检查点继续，更不是人工审批恢复；本章没有业务写入工具、幂等执行、提交/退款能力。
- 仅单 JVM：Map 与 MemorySaver 都是内存。重启缺失的不只是消息检查点，还有 taskId 到内部 threadId、所有者及业务状态的持久化关联。重启后不可仅凭旧 UUID 重建可用任务。
- 历史工具结果可能过时；新一轮 Java 核验始终优先。有限候选文本拦截无法证明所有语义正确，候选只供实验审阅，不能直接作为正式业务决定。
- 沿用 `app.ai.log-payload`，开启后会打印实际请求/工具 Schema/模型返回，日志关联为 `draftTask:<taskId>:<runId>`。默认关闭，本机运行按已有要求开启，不打印 API Key。

## 验证

```bash
RUN_PGVECTOR_TESTS=true RUN_ACCEPTANCE_TESTS=true \
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home ./mvnw package

# 显式真实百炼实验，使用现有 DASHSCOPE_API_KEY；仅写独立临时数据库
RUN_LIVE_ACCEPTANCE=true \
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home \
./mvnw -Dtest=DraftTaskLiveExperiment test

node --check src/main/resources/static/draft-tasks.js
```

`DraftTaskTest` 使用真实 Agent/MemorySaver，验证三轮历史与预算、新事实覆盖旧候选、跳过当轮核验被拒、跨任务/跨租户/跨用户隔离、同任务并发与清理冲突、全局容量、失败终止、超时、容量/轮次限制、清理与新服务不恢复、接待版本变化和有限文本拦截。

`DraftTaskHttpTest` 使用真实 Tomcat、Cookie/CSRF、临时 PostgreSQL 和受控模型，验证所有权、客服角色拒绝、三轮续写与刷新、清理、非 BOT 阻断、非法输入、迟到交接/注销结果隐藏；正式聊天保持原状。转人工自身合法产生的 SYSTEM 通知不算 Agent 写入。

`DraftTaskLiveExperiment` 在隔离数据库放入合成政策，三次调用真实 Qwen 与 Embedding，核对新描述、照片声明、调用记录和稳定 taskId；记录保存在忽略的 `target/chapter-21-live.json`，权限 600。

### 本次实际结果

2026-10-04：全量 `package` **370 项 Java 测试通过，0 失败、0 错误、0 跳过**，其中 71 项使用真实 PostgreSQL。本章增加 14 项框架/任务测试、6 项临时库 HTTP 测试，并扩展默认 profile 下新入口 404 的验证。后续仅增加中文注释及页面交互文案/输入清空，重新打包和 JS 语法检查通过。

另显式执行真实百炼实验 1 项，三轮结果如下；耗时是单次观测，不是性能保证。

| 轮次 | 本轮输入 | 状态 | 模型 / 工具 | 检查点 / 消息 | 耗时 |
| --- | --- | --- | --- | --- | --- |
| 1 | 插头发热，整理候选 | CANDIDATE_UNVALIDATED | 3 / 2 | 13 / 6 | 5638 ms |
| 2 | 更正为外壳破损 | CANDIDATE_UNVALIDATED | 3 / 2 | 26 / 12 | 5575 ms |
| 3 | 补充已上传照片的声明 | CANDIDATE_UNVALIDATED | 3 / 2 | 39 / 18 | 6101 ms |

三个 runId 不同，taskId 相同；第三轮候选沿用“外壳破损”，明确“用户声明已上传照片（但系统未读取、未核验）”，质量保持 UNVERIFIED，submitted/refundExecuted 都为 false。检查点数量是本版本本路径的观测值，不应写成跨版本固定协议。该实验临时库没有正式客服消息，随后清理任务成功。

18080 浏览器独立完成三轮操作及刷新：实际保留 3 条用户、9 条助手、6 条工具消息。刷新与查看后的模型请求总数仍是 9，没有额外执行图。核验结果、工具记录、候选展示均通过；清理确认的取消行为通过，实际清理与旧编号 404 由 HTTP/框架测试验证。1280 px 桌面和 390 px 手机无横向溢出，控制台无错误。

实际停止并重启应用后，原 taskId 返回 **404**，当前任务注册表为空；同一个正式客服会话仍可访问且消息数为 0。最终版本重新登录后可以创建新任务，不伪装恢复旧任务。应用运行在 **18080**，没有修改用户知识库或本地凭证。

本次运行由终端启动最终 JAR。IDEA 当前仍是其他项目，自动点击项目切换没有生效，因此没有声称 IDEA 已运行。已有 `.run/CloudCustomerServiceApplication.run.xml` 保留 JDK 项目配置、`local,knowledge`、18080 和日志开关；在 IDEA 打开本项目、刷新 Maven、使用 JDK 17 后可运行。手工切换前须先停止同项目的终端实例以释放端口。
