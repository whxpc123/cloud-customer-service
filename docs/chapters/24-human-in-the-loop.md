# 第二十四章：工具执行前的人工审批

本章在已确认的草稿之上增加独立的本地 HITL 实验。内容确认 `DRAFT_CONTENT_ONLY` 不会自动变成操作授权；用户还要审查一张具体操作卡，明确批准或拒绝。受保护工具只记录模拟执行次数，始终返回 `actualSubmitted=false`、`refundExecuted=false`，没有真实申请编号。

## 页面与操作

入口：<http://127.0.0.1:18080/internal/draft-tasks/hitl>。也可在第二十三章草稿页的有效确认区域点击“进入本版模拟审批”。

1. 在统一客服页使用现有客户账户登录。
2. 准备任务正常完成候选，保存具体草稿版本并确认内容。
3. 选择该任务和版本，阅读左侧草稿预览，点击“启动审批实验”。这是让 Agent 提出操作，还不是批准。
4. 页面显示真正的 `WAITING_APPROVAL`：工具调用编号、准确参数、固定草稿、内容确认编号、审批范围与截止时间；执行次数应为 0。
5. 点击“批准本次模拟操作”后，服务器对原调用构造 APPROVED 反馈并恢复。实际模拟执行完成时计数为 1。
6. 另起一场实验点击“拒绝本次操作”，对应调用不执行。拒绝后若模型又提出新调用，显示 `NEW_APPROVAL_REQUIRED` 并停止，不给新调用沿用旧许可。
7. 审批期间在草稿页修订或继续任务，旧操作不能执行。失败或响应丢失后先刷新查询，不自动重试决策。

实验列表、审批与 MemorySaver 检查点只在当前 JVM 中。页面刷新可以通过 executionId 重新读取，但应用重启后返回 404；原准备任务、草稿与内容确认仍在 PostgreSQL。清理实验只释放该实验内存，不删除数据库历史。本章不是跨进程审批系统。

## 真正的暂停与恢复

框架版本保持 JDK 17、Boot 3.5.8、Spring AI 1.1.2、Spring AI Alibaba 1.1.2.2。没有复制文章示例的包名或升级依赖。核对了本机 Maven 的同版本 sources JAR：

- `HumanInTheLoopHook` 位于 AFTER_MODEL，对目标工具构造 `InterruptionMetadata`；匹配反馈时检查工具名与调用 ID。
- 首次使用 `invokeAndGetOutput(...)`，必须返回真实中断，且计数为 0，才能展示审批卡。
- 服务端保留原始调用及中断。恢复使用同一个内部 threadId，通过 `HUMAN_FEEDBACK_METADATA_KEY` 传入从原调用构造的 ToolFeedback；普通聊天“批准”不能替代它。
- **实际测试发现 1.1.2.2 还需要显式注册 `ReturnDirectModelHook`**：`@Tool(returnDirect=true)` 让工具节点写入结束标记，配套 Hook 才跳到图结束。补上后，批准分支不再调用模型润色模拟结果。

反馈构造方式可对照[官方 HITL 示例](https://github.com/alibaba/spring-ai-alibaba/blob/main/examples/documentation/src/main/java/com/alibaba/cloud/ai/examples/documentation/framework/advanced/HumanInTheLoopExample.java)；该链接是主分支示例，本项目兼容性依据为本机固定 1.1.2.2 源码与真实框架测试。

```text
准备任务与确认草稿（PostgreSQL，保持不变）
           ↓ 固定 taskId / draftVersion / confirmationId / taskVersion
独立 submission-lab 执行线程
           ↓
Agent 提出一个模拟工具调用
           ↓
HumanInTheLoopHook → WAITING_APPROVAL，执行 0 次
           ↓ 显式决策，服务器构造反馈
同一线程恢复
   ├─ REJECT → 工具不执行；新请求不继承授权
   └─ APPROVE → 重新核对 → 模拟工具执行 → 直接返回实际计数
```

## 服务与约束

| 文件 | 责任 |
| --- | --- |
| `hitl/SubmissionProbe.java` | 每实验独立许可与执行计数；参数匹配、执行前复核、撤销许可；没有正式写操作依赖 |
| `hitl/HitlLabSession.java` | 真实 Agent、Hook、MemorySaver、精确调用校验、状态与单次决策门锁 |
| `hitl/HitlLabService.java` | 当前身份、草稿数据库复核、单进程注册表、统一工作池与容量限制 |
| `hitl/HitlLabController.java` | 本机 Cookie/CSRF/客户权限、严格请求 DTO、业务卡片响应 |
| `hitl-lab/index.html`、`static/hitl-lab.js`、`static/hitl-lab.css` | 审阅、批准/拒绝、实际计数、刷新与清理 |

Java 新增类、主要操作方法与关键控制点包含中文说明。调用模型时不持有数据库事务，也不占用原准备任务的执行状态；每次当前授权与草稿复核由原独立短事务完成。

开始审批要求当前草稿有效、内容已确认，且 taskVersion 与页面预览一致。批准前、每次模型调用前后、工具执行前，重新读取当前请求的 Session、所有权、BOT 接待状态、接待版本、准备任务版本、草稿版本与内容确认编号。等待人工期间不把 HttpSession 或身份回调存入图状态或长期运行对象；回调仅存在于当前执行段，结束即撤销。

同场决策使用 tryLock 和 expectedVersion，只有 `WAITING_APPROVAL` 可以消费。即使同一个人重复点击，也返回 409，不再恢复。确认的历史回执不会被此次操作删除。不同用户/租户统一 404；客服角色不能替客户审批。

模型只能得到一个模拟工具，底层全局工具配置会被拒绝。输出仅允许一条调用，必须为指定工具名、非空调用 ID 和准确的 `{taskId,draftVersion}`；错误版本、额外参数、重复 JSON 键、尾随 JSON、未知工具或多次调用均停止。客户端无法传图状态、threadId、checkpointId、任意 arguments 或 approverId；额外字段以及 EDITED 决策返回 400。

许可只在 APPROVE 恢复期间安装，finally 总会撤销。工具检查与计数之间也检查许可是否已被撤销。模拟计数最多 1，不表示生产系统的事务幂等：正式提交仍需要持久化操作授权、原子消费、业务幂等键、实时订单复核和结果查询。

最多保留 100 场本地实验，两段图运行并发；每场最多 4 次模型调用，每段最多等待 45 秒，审批卡有效期 10 分钟。远端超时不保证立即停止计费，但实际工作退出前不归还线程容量，迟到结果不能新增审批卡或放行工具。达到容量或执行异常时保留 `RECOVERY_REQUIRED`，不自动重试。

## API

页面壳允许匿名加载，所有数据和操作均要求本机客户登录。以下路径前缀为 `/internal/draft-tasks/hitl`，沿用现有安全过滤链，写请求携带当前 CSRF；数据响应 `Cache-Control: no-store`。

| 方法与路径 | 请求 / 作用 |
| --- | --- |
| POST `/executions` | `{taskId,draftVersion,expectedTaskVersion}`，201 返回本场状态；必须检查 phase，201 不等于执行成功 |
| GET `/executions` | 当前账户的内存实验摘要 |
| GET `/executions/{executionId}` | 审批卡、决策和实际计数，不恢复图 |
| POST `/executions/{executionId}/decision` | `{approvalId,expectedVersion,decision:"APPROVE"或"REJECT"}`，只消费当前卡 |
| DELETE `/executions/{executionId}` | 清理非运行中的本地实验；不删除持久化草稿 |

`version` 是本场实验状态版本，不是 `draftVersion`，也不是源任务的 `taskVersion`。决策响应分开保留 `decision` 和 `phase/simulatedExecutions`；批准过但恢复失败只能说明“批准过”，不能说明执行成功。不得使用客户端传入的身份或在新请求中替换原待审参数。

## 验证

```bash
RUN_PGVECTOR_TESTS=true RUN_ACCEPTANCE_TESTS=true \
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home ./mvnw package
node --check src/main/resources/static/hitl-lab.js
node --check src/main/resources/static/persistent-draft-tasks.js
```

新增测试使用真实 ReactAgent、HITL 与 MemorySaver；数据库/HTTP 测试还使用真实 PostgreSQL 草稿、权限和 CSRF，仅模型/订单适配器受控。覆盖：暂停零次、批准一次、拒绝零次、再次中断、错误参数、多调用、重复 JSON、过期、错审批编号、跨用户/租户、重复/并发决策、草稿变化、准备新轮、接待变化、模型期间注销、全局工具、超时容量、迟到结果和清理范围。

2026-10-04 全量构建：450 项测试通过，0 失败、0 错误、0 跳过，其中 123 项使用真实 PostgreSQL。本章新增 30 项（17 项真实图/Hook、2 项慢调用/许可撤销、11 项数据库/HTTP）。首次全量运行中，旧 Chapter 22 重启测试遇到测试容器初始数据库连接 EOF；未改变业务代码，该项独立复跑及随后的全量构建均通过。

真实 Qwen 首次批准验证得到 WAITING_APPROVAL / 0 次，明确批准后 SIMULATION_COMPLETED / 1 次，模型调用总数保持 1。另一场初始提示词实验只返回“等待审批”的文字，系统保留 RECOVERY_REQUIRED / 0 次、没有伪造审批卡。根据实际结果修改提示词，明确要求模型先提出工具调用，由 Hook 暂停，而不是让模型用文字询问批准；后续验证结果见下方。


调整仅涉及首轮提示词，之后重新运行上述 30 项专项测试与 JAR 打包，均通过。最终真实模型验收：

| 检查 | 实际结果 |
| --- | --- |
| 明确拒绝 | `REJECTED`，模拟执行 0 次，模型调用 2 次（提出调用、处理拒绝） |
| 明确批准 | `SIMULATION_COMPLETED`，模拟执行 1 次，模型调用 1 次，恢复后直接返回工具结果 |
| 浏览器刷新 | 保留同一场实验和决策，模拟执行仍为 1，模型调用仍为 1 |
| 实际 JVM 重启 | 旧 executionId 读取 404、实验列表为空；重新登录后固定 V2 正文、确认编号和 taskVersion 与重启前完全一致 |
| 登录失效 | 刷新后左右两栏都隐藏旧草稿与审批正文，按钮禁用 |
| 页面 | 桌面流程通过；390 px 下文档宽度为 390 px，无横向溢出；浏览器没有记录到 JavaScript 错误 |

批准/拒绝使用同一份第 23 章教学验收草稿，各自创建独立实验。没有修改草稿、确认回执或准备任务的运行编号。真实模型仍可能不按预期提出调用；应用会停止并显示需要核查，不把普通文字伪装成审批或成功，不自动重试。

最终应用通过终端运行在 18080，IDEA 中仍为另一个项目，本章未在 IDEA 内启动。仓库的共享 `CloudCustomerServiceApplication` 运行配置保持可用，使用 JDK 17 和环境变量 `DASHSCOPE_API_KEY`；IDEA 运行前需先停止占用同一端口的终端实例。
