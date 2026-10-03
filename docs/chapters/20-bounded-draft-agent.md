# 第二十章：有界售后草稿 Agent

本章从一次问答推进到一次任务：在同一轮运行内观察工具结果、决定是否继续、读取草稿要求、整理候选。保留第一阶段客服、RAG、只读订单核验与人工接待；不增加退款、提交、审批或持久化任务接口。

## 打开与运行

页面：<http://127.0.0.1:18080/internal/draft-agent>。先在统一客服页使用本机 `customer1001` / `customer2002` 登录，再选择 BOT 会话或新建会话。本地密码仍在忽略文件 `.local/handoff-accounts.properties`；API Key 仍只读 `DASHSCOPE_API_KEY`。

IDEA 刷新 Maven，使用 JDK 17 和已有 `CloudCustomerServiceApplication` 配置（`local,knowledge`，18080）。必须先停止占用端口的同项目终端进程，不能让两份应用同时绑定 18080。本次实际启动与浏览器验收见下方记录；不把终端启动说成 IDEA 已运行。

## 代码入口

| 文件 | 职责 |
| --- | --- |
| `agent/AfterSaleDraftAgentService.java` | 每次构造真实 ReactAgent、MemorySaver、UUID threadId，注册两个工具与调用预算 |
| `agent/AfterSaleDraftTools.java` | 绑定服务器 Actor / 订单 / 用户原因，核验缓存和模板前置条件 |
| `agent/DraftAgentController.java` | 登录、CSRF、归属与 BOT 状态检查，返回前重查 Session 和接待版本 |
| `agent/DraftRunBudget.java` | 单调时钟截止时间和停止标记，停止后禁止后续模型和工具调用 |
| `agent/DraftCandidateGuard.java` | 拦截部分明确的提交、批准、退款宣称，不是语义审核器 |
| `agent/DraftRun.java` | 候选与事实分离、实际调用记录、固定 false 的业务写入标记 |
| `draft-agent/index.html`、`static/draft-agent.*` | 独立实验审阅页，安全纯文本渲染，不保存浏览器草稿或正式消息 |

文件前缀为 `src/main/java/com/example/cloudcustomerservice/` 或 `src/main/resources/`。新增 Java 文件和关键前端逻辑均有中文注释。

## 实际执行链

```text
Cookie 登录 + CSRF
  → 服务器 Actor → 会话归属 → BOT 状态/版本快照
  → 每次新 Agent + 新 MemorySaver + 新工具对象 + 新 runId
  → 模型选择 inspectAfterSale / readDraftTemplate / 停止
  → Java 检查实际工具结果、模板已读、候选非空和有限危险宣称
  → 重查 Session + 会话 BOT 状态及版本
  → 实验页候选 / 确定性检查结果 / 缺口 / 未完成 / 隐藏迟到结果
```

`inspectAfterSale` 复用第十五章：归属 → 订单事实 → 来源/版本精确约束的适用政策 → Java 规则。两个工具没有模型可控参数，模型无法换租户、账户或订单。质量投诉始终是用户诉求；`UNVERIFIED` 不升级成质量已成立。

模板只有在事实可访问且政策非空时才可读。失败与成功查询都在本轮缓存；重复调用仍消耗预算。只检查开关由 Java 强制阻止读取模板，只返回程序事实与结论。自然语言只要求检查时，模型也可以检查后停止；未读取模板不会产生候选。

框架和 Graph 都是 `1.1.2.2`，所有 Spring AI 模块仍为 `1.1.2`，Boot 为 `3.5.8`。Agent 关闭模型内部工具执行，由图编排实际工具循环；顺序执行工具，不依赖并行调用的偶然顺序。使用 `org.springframework.ai.support.ToolCallbacks`，不能照搬错误包名。实际源码还表明 builder 按供应商选项类型合并参数，因此使用与现有模型一致的 `DashScopeChatOptions`，测试也采用此选项类型。

## HTTP 契约

```http
POST /internal/draft-agent/runs
Content-Type: application/json
X-CSRF-TOKEN: <登录后重新取得的 CSRF token>
Cookie: <本机登录会话>

{
  "conversationId": "<当前账户自己的 BOT 会话 UUID>",
  "orderNo": "A10001",
  "reason": "QUALITY_ISSUE",
  "task": "先核验事实和政策，整理候选草稿并列出待确认事项，不要提交。",
  "inspectionOnly": false
}
```

订单为 A 加五位数字，小写会归一化；任务 1～2000 字符；原因支持 `QUALITY_ISSUE` / `CHANGE_OF_MIND` / `UNKNOWN`。不接受正文中的身份覆盖。401/403 为登录、CSRF 或权限问题；404 为不可访问会话；409 为接待模式不允许；429 为本机运行槽已满。接口返回 `Cache-Control: no-store`。

响应含 `runId`、`status`、`message`、`candidateText`、`assessment`、`executedSteps`、`modelCalls`、`toolCalls`、`elapsedMs`、`submitted:false`、`refundExecuted:false`。

| 状态 | 含义 |
| --- | --- |
| `CANDIDATE_UNVALIDATED` | 已实际取得核验和模板，得到候选；仍未经事实与语义审核 |
| `INSPECTED` | 用户开启只检查模式，已返回程序检查结论，没有草稿 |
| `NEEDS_ATTENTION` | 缺依据/模板，或模型文本为空、过长、含有限规则识别的危险宣称 |
| `RUN_FAILED` | 达到次数/时间上限、模型故障或图执行未完成，没有候选 |
| `STATE_CHANGED` | 返回前身份或接待状态变化，隐藏候选、事实和工具轨迹 |

`executedSteps` 只保存实际发生的工具动作，例如 `inspectAfterSale:NEED_QUALITY_VERIFICATION`、`readDraftTemplate:READ`、`inspectAfterSale:CACHED`、`readDraftTemplate:BLOCKED`，不展示模型内部推理。`modelCalls` 统计模型装饰器边界的调用尝试，不等于 SDK 内部重试次数或供应商计费请求数。`runId` 不是持久化任务编号，不能用于恢复或“继续上次”。

## 有界运行与限制

- `ModelCallLimitHook.runLimit(6)` + `ExitBehavior.ERROR`，另在模型装饰器设置调用保护；框架真实循环测试验证只调用六次。
- 工具最多接受八次调用，第九次尝试拒绝，`toolCalls` 包含被拒绝的尝试；缓存也消耗调用预算。
- 每轮最多等待 90 秒，每个模型/只读查询最多等待 30 秒。每次模型请求最多输出 1800 tokens；候选文本最多 12000 字符。
- 本机最多两个任务同时运行、无任务排队；底层最多两个线程、两项短暂排队。超时发出取消信号并禁止本轮继续，但不保证供应商立即停止计算/计费；卡住的底层线程不会被无限替换。
- 运行在事务之外；不调用正式聊天 `begin/finish`，不追加模型历史或业务消息。返回前重新检查接待版本，不声称它能阻止响应返回之后再发生的状态变化。
- 页面仅为实验审阅；有限正则不可能证明所有语言的业务正确性。没有完整事实验证器、人工审核流、持久化草稿、断点恢复、提交 API、补偿和幂等执行。本章不能直接上线。
- 本地完整日志开关沿用 `app.ai.log-payload`，会输出请求、工具定义/Schema 和模型响应；`client=draftAgent:<runId>` 便于串起一轮。默认配置关闭正文日志，本机原 IDEA 配置显式开启；不记录密钥。

## 验证命令

```bash
# 全量离线模型 + 专用 PostgreSQL + 临时容器回归
RUN_PGVECTOR_TESTS=true RUN_ACCEPTANCE_TESTS=true \
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home ./mvnw package

# 显式真实百炼与 Embedding，会消耗现有 API Key 额度；只写临时数据库
RUN_LIVE_ACCEPTANCE=true \
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home \
./mvnw -Dtest=DraftAgentLiveExperiment test

node --check src/main/resources/static/draft-agent.js
```

新增受控模型测试真实执行 ReactAgent，不 mock 整个 Agent；HTTP 测试使用 Cookie/CSRF、真实 Tomcat、正式 Flyway 和独立 Testcontainers 数据库。并发交接使用闩锁确认“模型已开始 → 状态事务提交 → 模型返回”。真实模型结果写 `target/chapter-20-live.json`（忽略、权限 600），不把用户资料或原始日志提交到 Git。

### 本次验收记录

2026-10-04，本章全量 `package`：**350 项 Java 测试，0 失败、0 错误、0 跳过**；包含 65 项真实数据库测试。本章新增 20 项框架/工具/预算测试、5 项临时库 HTTP 测试；非 local profile 下新入口也验证为 404。JS 语法检查通过。

另显式运行 1 项真实 Qwen + Embedding 实验，内含以下五个场景，全部契约断言通过。耗时为该次运行观测值，不代表性能保证；两个写操作标记均为 false。

| 固定场景 | 实际状态 | 模型/工具调用 | 耗时 |
| --- | --- | --- | --- |
| A10001 质量问题候选 | CANDIDATE_UNVALIDATED；质量仍 UNVERIFIED | 3 / 2 | 5899 ms |
| A10002 不可访问 | NEEDS_ATTENTION；无订单事实、无候选 | 2 / 1 | 1784 ms |
| A10005 缺适用政策 | NEEDS_ATTENTION；模板调用被 Java 拒绝 | 3 / 2 | 2316 ms |
| A10001 只检查 | INSPECTED；未读取模板，无候选 | 2 / 1 | 5078 ms |
| 直接要求提交退款 | NEEDS_ATTENTION；没有业务工具调用 | 1 / 0 | 1680 ms |

真实实验隔离库的正式消息数仍为 0。缺政策场景中模型曾尝试读取模板，Java 确实拒绝了它，而不是依靠提示词碰巧跳过。

应用 JAR 已在 **18080** 实际启动，浏览器完成登录、新建 BOT 会话、完整草稿运行、只检查模式、响应 JSON 和事实/工具记录展示。页面完整运行样例为 3 次模型、2 次工具、约 6.5 秒；1280 px 桌面双栏及 390 px 手机单栏无横向溢出、无控制台错误。界面不会把 `UNVERIFIED` 改成获批。

本次仍由终端启动。IDEA 项目菜单可读，但点击客服项目再次报 `AXError.invalidUIElement`，自动切换项目未成功；原运行配置保留，可手工刷新 Maven 后运行。没有声称已在 IDEA 内启动，也没有生产部署。
