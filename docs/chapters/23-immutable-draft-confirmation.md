# 第二十三章：不可变草稿与具体版本确认

用户确认的是 `taskId + draftVersion` 指向的固定正文。后台不会在点击之后替换成数据库“最新版本”，也不会用 `confirmed=true` 把确认移到另一份草稿。本章只确认问题描述和申请诉求，仍不批准售后、不创建申请、不执行退款。

## 页面与操作

沿用 <http://127.0.0.1:18080/internal/draft-tasks>，页面增加“草稿版本与内容确认”审阅区。

1. 使用统一客服页现有客户账号登录，创建任务并正常完成一轮候选。
2. 点击“整理并保存新版本”。本次额外调用一次模型，只提取问题描述和申请诉求，校验后保存 V1。
3. 核对具体版本的两项文字、订单、事实查询时间与政策来源；勾选同意，再点击“确认本版问题描述和申请诉求”。
4. 要修改内容，继续任务生成新候选，再整理保存 V2。V1 原文和历史确认保留，V2 不继承确认。
5. 在版本选择框查看历史，区分“曾经确认”和“当前有效”。旧页面确认过期版本会遇到 409；页面加载新内容供重新核对，不自动确认或重试。

在聊天框中输入“我确认”只是一条新的任务输入，不能代替这个程序确认接口。重启服务后重新登录，正文和确认记录从数据库读取，不重新调用模型。

## 模型负责的字段

`DraftTextExtractor` 使用新建的普通 ChatClient，`.call().entity(ProposedText.class)` 生成格式说明并转换结构化输出。它没有 Agent、工具、RAG 或记忆 Advisor；发现底层模型配置了全局工具就拒绝执行。输入取自数据库中已完成候选，不接受客户端提交事实。

```java
record ProposedText(
    @NotBlank @Size(max = 1000) String userDescription,
    @NotBlank @Size(max = 300) String requestedHandling) {}
```

转换之后显式执行 `validator.validate(...)`。新增 Boot 管理版本的 `spring-boot-starter-validation`，其余框架版本不变：JDK 17、Boot 3.5.8、Spring AI 1.1.2、Spring AI Alibaba 1.1.2.2。

`Body` 的 schemaVersion、orderNo、checkedSnapshot 和 notice 由 Java 组装。预检查直接复制第二十二章真实 `Completed.run().assessment()`，校验 runId、候选状态、订单、原因、时间和证据；不让模型重新抄写事实。模型只能产生两个陈述字段，不能设置确认人、确认时间、审核或退款状态。

结构和长度校验不能证明语义正确。比如“尚未激活”与“已经激活”都可能是合法字符串，页面仍要求用户核对这份具体内容。确认不刷新订单快照，页面保留当时查询时间和政策来源。

整理每实例最多两次并行，固定两个工作线程，最多等待 30 秒；提示输出上限 1200 tokens，原始输出 16000 字符。超时返回失败，迟到结果不会回调业务写入，实际工作退出后才归还容量。远程网络调用不保证立即取消，不自动重试模型或发布。

## 代码与数据库

Java 包前缀为 `com.example.cloudcustomerservice`。

| 文件 | 职责 |
| --- | --- |
| `draft/DraftModels.java` | 两个模型字段、不可变正文、版本视图与确认回执 |
| `draft/DraftTextExtractor.java` | 独立结构化转换、显式 Bean Validation、无工具和有界等待 |
| `draft/DraftApplicationService.java` | 当前授权、验证完成结果、模型前后版本与身份检查 |
| `draft/DraftVersionStore.java` | 真实短事务、行锁、追加版本、精确确认与历史有效性 |
| `draft/DraftRevisionController.java` | 客户权限、Cookie/CSRF、具体版本 API，注销后阻止迟到发布 |
| `db/migration/V7__immutable_draft_revisions.sql` | 任务草稿计数、不可变草稿与确认表 |
| `persistent-draft-tasks/index.html`、`static/persistent-draft-tasks.js`、`static/draft-revisions.css` | 原任务页面内的版本审阅区 |

V7 增加 `ai.cs_draft_task.draft_version`，创建：

- `ai.cs_draft_revision`：`(task_id,draft_version)` 主键、basis_run_id、body_json、数据库创建时间。
- `ai.cs_draft_confirmation`：confirmation_id、task_id、draft_version、confirmed_by、数据库 confirmed_at、固定 `DRAFT_CONTENT_ONLY` 范围。
- `(task_id,draft_version)` 唯一确认约束与外键：一条确认只能指向实际存在的版本，同一版最多一个回执。
- 两张新增表均禁止普通 UPDATE 原地改写；正文修订必须 INSERT 新版本。未开放删除 API，当前本地数据库账户仍有管理能力，生产最小权限、保留和合法删除流程需要单独设计。

V1～V6 不改。已有第二十二章任务自动获得 draft_version=0；旧候选不自动转换，用户显式点击后才调用整理模型。Graph 检查点、last_result_json 与草稿版本各司其职，草稿不会替代图恢复历史。

## 两种版本与竞争规则

正常过程示例：

```text
创建任务          task.version=0   draft_version=0
开始 Agent        task.version=1
完成 Agent        task.version=2
保存草稿 V1       task.version=3   draft_version=1
确认 V1           task.version=4   draft_version=1
重复确认 V1       task.version=4   回执不变
开始新一轮        task.version=5   旧 V1 确认立即失去当前效力
完成新候选        task.version=6   旧 V1 的 basisRunId 仍不同
保存草稿 V2       task.version=7   draft_version=2，V2 尚未确认
```

发布和确认都在独立 Store Bean 的三秒短事务中执行。先验证所有权，按“会话行 → 父任务行”的顺序加锁；会话当前必须为 BOT 且版本未变。父任务 `FOR UPDATE` 与第二十二章的任务领取 UPDATE 竞争同一行，模型等待不在事务里。

生成草稿时先读取源版本，事务结束后调用模型；随后重查 Session，再锁行验证 task.version、runId、候选状态和契约未变，才插入新正文并增加两个版本。另一个窗口在模型期间确认、发布、继续任务或结束任务，会使旧整理请求返回 409。发布中途失败，两张表更新一并回滚，没有半份草稿。

确认先锁定父任务并检查具体版本是否当前有效，再查已有确认。因此同版重复点击返回相同编号、时间和确认人；过期版本即使曾经确认，也返回 409，不用历史成功掩盖当前失效。

当前效力同时要求：

```text
当前任务状态 == CANDIDATE_UNVALIDATED
且 Agent 契约兼容
且 task.draft_version == 此版 draftVersion
且 task.run_id == 此版 basisRunId
且有该版本的确认记录
```

任务开始新一轮、变为未完成/需核查/结束，或发布新版后，旧版确认都会失去当前效力，但原回执不删除。不新增 CONFIRMED 任务状态，Agent 执行状态和用户内容确认是不同维度。仅改语气或再次保存相同文本，也生成新版本并重新确认；本章没有语义等价继承机制或额外 Hash。

每个任务最多保存 100 版，版本目录最多 100 项；达到上限需要结束并新建任务，历史不静默删除。原每任务 8 轮 Agent 上限保持不变，文字整理不增加 Agent 轮次。

## API

沿用现有路径 `/internal/draft-tasks/tasks/{taskId}`，适配文章 `/api/tasks` 示例，没有新增无鉴权入口。API 仅在 `local,knowledge` 注册，必须是客户角色；写请求需要最新 CSRF。

| 方法及后缀 | 请求 / 返回 |
| --- | --- |
| POST `/drafts` | `{expectedTaskVersion:2}`，201 返回新版本正文；不接受自定义事实 |
| GET `/drafts` | 版本元数据目录，不调用模型 |
| GET `/drafts/{version}` | 具体正文、basisRunId、当前效力、历史确认；不调用模型 |
| POST `/draft-confirmations` | `{draftVersion:1,accepted:true}`，200 返回实际回执；不调用模型 |

确认响应包含 confirmationId、taskId、draftVersion、confirmedBy、confirmedAt 和 scope。页面发送的是显示中的 draftVersion，不在点击后查询最新版替换。POST 不自动重试；响应丢失先 GET 读取该版本的真实确认记录。

400 表示缺少版本/明确接受或无效输入；401/403 表示登录、角色或 CSRF；404 对不存在和他人任务一致；409 表示版本/任务/接待冲突；429 表示容量限制；503 表示整理、格式或存储未确认。非 BOT 会话不能继续读取或操作草稿；同一 BOT 会话中仍可查看 CLOSED / RUNNING 任务的历史版本，但无当前确认效力。

新增接口全部 `Cache-Control: no-store`。`DASHSCOPE_API_KEY` 仍从环境读取，`app.ai.log-payload` 沿用现有开关，文字整理请求/Schema/原始返回的日志名为 `draftText:<taskId>`，不输出 Key。

## 验证

```bash
RUN_PGVECTOR_TESTS=true RUN_ACCEPTANCE_TESTS=true \
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home ./mvnw package
node --check src/main/resources/static/persistent-draft-tasks.js
```

新增验证包括：真实结构化转换、空字段/超长/解析失败、并发与超时；真实 PostgreSQL 行锁竞争、不可变触发器、版本冲突、同版幂等、回滚、跨账户/租户、HTTP/CSRF、模型期间任务变化/确认/交接/注销，以及独立 JVM 重启后正文与回执不变、GET 不新增模型调用。测试全部使用专用临时数据库，不清空用户工作数据库。

2026-10-04 实际结果：

- 全量构建 **420 项 Java 测试通过，0 失败、0 错误、0 跳过**；其中 112 项真实数据库测试。本章增加 9 项文字整理测试、19 项数据库/HTTP 测试及 1 项跨 JVM 重启测试。JS 语法检查与 `git diff --check` 通过。
- 独立进程测试先保存并确认 V1，终止进程再由新 PID 恢复，正文和回执一致、读取不调用模型；新一轮后保留旧回执但失效，V2 尚未确认。受控模型总调用 8 次；报告在忽略目录 `target/chapter-23-process-restart.json`。
- 本机真实 Qwen 实验：第一轮反馈“右侧外壳开裂”并保存 V1；第二窗口更正为“不是外壳开裂，是右侧按钮按不动”，继续候选并保存 V2。共 6 次 Agent 模型调用、2 次文字整理调用。
- 原窗口仍显示 V1 时勾选并确认，服务拒绝过期版本；页面加载 V2、取消勾选，V2 的 confirmation 仍为 null，按钮不可提交。重新核对并显式勾选 V2 后才生成 `DRAFT_CONTENT_ONLY` 回执。历史 V1 的 body 与修改前逐项一致。
- 实际应用 PID 79901 → 80532 重启后重新登录，同任务 V2 的 body 与 confirmation 完全一致，当前确认效力仍为 true；重启后的日志中模型请求为 0。页面读取的是数据库正文，没有重新生成。
- 1280 px 桌面检查通过；390 px 断点的页面 scrollWidth 为 390、工作区宽 354，无横向溢出。最终页面无浏览器控制台错误，临时视口覆盖已恢复。
- V7 已在本机现有数据库实际应用，既有任务保留。最终打包版本在 **18080 由终端启动**；IDEA 仍显示另一个项目，本次未在 IDEA 内启动。仓库 `.run/CloudCustomerServiceApplication.run.xml` 的 Java 17 / local,knowledge / 18080 配置沿用；若从 IDEA 接管，应先停止终端中的同端口进程。

本章不是工具执行前暂停/批准/拒绝/恢复的完整 Human-in-the-Loop，没有提交工具。未来真正执行业务仍需重新校验实时订单、当前授权、具体操作参数和确认效力，不能仅信任曾经返回的 confirmationEffective=true。
