# 第十七章：持久化人工接待与会话交接

本章把“要求人工”变成可以查询和恢复的数据库状态。申请只有在事务提交后才返回受理编号；已有客服领取才显示 HUMAN_ACTIVE。机器人在生成途中遇到交接，候选答案会被发布门拦截。本章提供客户页面与坐席 API，没有真人聊天通道，不能将“领取成功”解释成已建立真人聊天连接。

## 启动与登录

沿用 Java 17、Spring Boot 3.5.8、Spring AI 1.1.2、Alibaba 1.1.2.2、PostgreSQL 和 18080。新增 Spring Security 依赖，版本由 Boot 管理。API Key 仍只读取 `DASHSCOPE_API_KEY`。

首次运行：

```bash
python3 scripts/setup-handoff-accounts.py
```

脚本只创建 `.local/handoff-accounts.properties`，权限 600；若已存在则保持原密码。文件被 Git 忽略，不打印口令。`local,knowledge` 配置启动时读取它，并在内存生成 BCrypt 密码摘要。缺密码的账号不注册，绝不设置默认密码。

| 本地账号 | 服务端固定身份 | 权限 |
| --- | --- | --- |
| customer1001 | tenant-yunshan / 1001 | customer:chat |
| customer2002 | tenant-yunshan / 2002 | customer:chat |
| support9001 | tenant-yunshan / 9001 | support:serve |
| support9002 | tenant-yunshan / 9002 | support:serve |

IDEA 使用已有 `CloudCustomerServiceApplication` 共享运行配置，工作目录是项目根目录，JDK 17，profiles 为 `local,knowledge`。先启动原 PostgreSQL。新增依赖后刷新 Maven。运行前确认 18080 没有另一个本项目实例。

打开 <http://127.0.0.1:18080/internal/routing>，使用 customer 账号和本机文件中的密码登录。浏览器恢复当前账号最近 50 个会话；新会话立即持久化。support 账号通过本文坐席 API 或 `requests.http` 操作，本章未制作坐席工作台。正式记录和模型窗口分离，页面“重置上下文”保留记录，并且不能撤销人工申请。

2026-10-03 实际验收运行的是终端启动的打包 JAR，包含一次真实进程重启。IDEA 打开的是另一个项目，点击最近项目时窗口控制返回无效 UI 元素，未完成 IDEA 内运行验证；这里没有把终端启动冒充 IDEA 启动成功。

## 状态是事实，话术是解释

```text
BOT --客户申请--> WAITING_HUMAN --有权限的客服领取--> HUMAN_ACTIVE
                                                        |
                                                   当前客服结束
                                                        v
                                                      CLOSED
```

| 状态 | 必须存在的数据 | 客户页面的含义 |
| --- | --- | --- |
| BOT | 尚无受理号、领取人和人工阶段时间 | 智能客服可以处理 |
| WAITING_HUMAN | handoffId、requestedAt | 申请已受理，等待领取 |
| HUMAN_ACTIVE | 另有 assignedAgentId、acceptedAt | 已有客服领取；未接入真人消息通道 |
| CLOSED | 另有 closedAt | 本次接待结束，需要新建会话 |

同一会话只进行一次人工接待。重复申请返回同一个受理号；领取和结束的同客服重试也幂等。CLOSED 再申请不会重新排队。已由其他客服领取时不能抢领，只有当前领取客服能结束。没有自动恢复机器人、转派、取消、排队位置、预计等待时间或在线人数。

`V5__human_handoff.sql` 的 CHECK 约束确保字段与模式一致；不修改 V1～V4。待接待队列由 `ai.cs_conversation` 按租户、WAITING_HUMAN、申请时间查询，单次返回前 50 条，不另建进程内队列。所有跨用户和跨租户未知记录统一返回 404。

## 短事务与发布边界

`HumanHandoffService` 是真正的 Spring Bean，由控制器或 `HandoffChatService` 经代理调用，方法权限和事务同时生效。

1. `begin` 锁会话行，校验客户归属，保存原始 USER 消息。BOT 时领取 generationId 租约，读取最多 20 条正式 USER/BOT 历史；等待/接待状态只保存消息，不调用路由或模型。
2. 事务结束后 `HandoffChatService` 调用原 `RoutedCustomerService`，取得候选答案。模型与 RAG 调用不占数据库事务或会话行锁。
3. `finish` 在另一短事务里锁同一行，同时检查 `mode == BOT`、`version` 未改变、generationId 相同。符合时保存 BOT 消息和完整业务 payload 后返回；不符合则返回 `published=false / STALE_DISCARDED`，不返回候选正文和 payload。
4. 客户直接申请或输入完整“我要转人工”等命令时，立即锁行变更状态、生成受理号、递增版本、清除租约，并写入 SYSTEM 记录。此路径不依赖模型或 AI 摘要。
5. 若非快捷命令经分类器判为 HUMAN_SERVICE，`finish` 仍进入同一数据库申请过程，不发布“需要确认”这段候选话术。

申请、领取、结束和 SYSTEM 消息在同一事务提交；消息写入失败则状态一起回滚。数据库变更已经提交、HTTP 响应却丢失时，客户端不能认定申请失败，应查询回执或重复同会话申请。服务返回 503 时只说“暂时无法确认”。

前端对同一会话仅接受不小于当前版本的回执；历史分页快照落后时不推进游标，下一次重新读取。会话切换使用页面代次丢弃旧请求。当前状态和历史业务详情分开展示，保存早于交接的机器人消息仍是合法历史。不会把这些历史消息当成刚发布的答案。

生成租约使用数据库时间，五分钟内拒绝同会话第二个普通机器人生成；转人工不等待租约。进程异常中断后，客户可立即申请人工；或者五分钟后以新消息编号重新提问，旧租约被替换，旧生成不能再发布。租约不是预计人工等待时间，也不是强制取消模型的截止时间。

## 正式记录与权限

`ai.cs_message` 保存 USER/BOT/SYSTEM 原文、作者、时间、状态版本和正式 BOT 的业务 JSON。知识来源、订单结果、售后检查的原结构都保留；未提交的候选内容不进入正式记录。消息查询按 ID 游标每页最多 100 条，历史长度不受模型的 20 条窗口限制。

`clientMessageId` 是客户发送时生成的 UUID，在每个会话内唯一。网络不明时同 ID 同正文重试只返回 REPLAY，不重复追加或再次调用模型；同 ID 换正文返回 409。如果只收到用户消息而生成中断，同 ID 重试不会重新收费生成，需按照租约恢复方式发起新消息。页面在同正文重试时保留原 UUID。可选 ID 的旧客户端每次会由服务器生成新 ID，因此旧客户端不具备跨 HTTP 重试去重保证。

登录使用 Spring Security Session、密码校验、CSRF、会话固定防护；身份由 `HandoffPrincipal` 提供，忽略请求中的 userId、mode、history 和 X-Demo-User-Id。方法权限 `support:serve` 不依赖页面按钮。新入口限制回环地址、localhost Host 和同源 Origin。重启后 Session 失效，重新登录同账号才能恢复原记录。

这里只接入本地固定账户，未接入生产 IAM、账号管理、审计/限流、多节点登录 Session、真实订单或人工消息。第一至十五章的教学接口保留原行为，并非整个应用都已完成生产安全改造，不能把本地 profile 直接部署到公网。

## API

`requests.http` 有完整登录、CSRF、客户消息、申请、客服领取及结束例子。密码放在被忽略的 `http-client.private.env.json` 或本机 HTTP Client 私有变量，不能写入公共请求文件。

| API | 作用 |
| --- | --- |
| GET `/internal/handoff/session` | 登录状态与 CSRF；不会自动授予身份 |
| POST `/internal/handoff/login` | 表单 username/password；成功 204，随后重新读取 CSRF |
| POST `/internal/handoff/logout` | 退出登录 |
| POST / GET `/api/handoff/conversations` | 创建会话 / 当前客户最近 50 个会话 |
| POST / GET `/api/handoff/conversations/{id}/handoff` | 申请 / 查询真实接待回执 |
| GET `/api/handoff/conversations/{id}/messages?after=0` | 客户正式记录 |
| POST `/internal/routing/conversations/{id}/messages` | 统一入口，正文为 message、clientMessageId，返回 Delivery |
| DELETE `/internal/routing/conversations/{id}/memory` | BOT 空闲时重置模型窗口，保留正式记录 |
| POST `/internal/routing/decide` | 仅诊断，既不执行业务也不申请人工 |
| GET `/api/support/queue` | 当前客服租户的待领取队列 |
| POST `/api/support/conversations/{id}/accept` | 有权限的客服领取 |
| POST `/api/support/conversations/{id}/close` | 当前领取客服结束 |
| GET `/api/support/conversations/{id}/messages?after=0` | 等待中可读；领取后只有当前领取人能读 |

采用 `/api/handoff/conversations` 是为了避免覆盖第四章已存在的 `/api/conversations`。旧 `/internal/routing/session` 已移除，CSRF 头改为 Spring Security 返回的 `X-CSRF-TOKEN`。路由统计只计算经过分类的请求；直接人工申请和等待期间保存消息不纳入分类计数。

## 代码入口

| 文件 | 职责 |
| --- | --- |
| handoff/HandoffModel.java | 身份、状态、回执、消息及发布结果契约 |
| handoff/HumanHandoffService.java | 归属与权限、行锁事务、幂等状态转换、正式记录、发布门 |
| handoff/HandoffChatService.java | 事务外生成候选，事务内发布 |
| handoff/HumanHandoffController.java | 客户与坐席 HTTP 适配，不接受调用方身份 |
| security/HandoffSecurityConfiguration.java | 本地密码账户、请求权限、CSRF、回环与同源检查 |
| security/HandoffIdentity.java | 认证身份适配，阻止 Java 调用者伪造 Actor |
| routing/LocalRoutingController.java | 原统一页面接入真实会话，不再维护 Session UUID 注册表 |
| routing/RoutingConversation.java | 单轮候选生成窗口，不拥有接待状态 |
| resources/static/routing-lab.js | 登录、恢复会话、正式消息、受理卡、版本检查及 HTTP 轮询 |

Java 路径相对于 `src/main/java/com/example/cloudcustomerservice/`。

## 验证记录（2026-10-03）

```bash
RUN_PGVECTOR_TESTS=true JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home ./mvnw package
node --check src/main/resources/static/routing-lab.js
# 以下需要运行中的本机应用，会保留一条完整教学接待记录；--model 调用实际分类模型。
python3 scripts/check-handoff.py --model
# 重启应用以后执行，只查询上次输出文件对应的记录：
python3 scripts/check-handoff.py --verify-restart
```

- 全量 **294 项通过，0 失败、0 错误、0 跳过**；含 **44 项真实 PostgreSQL 测试**。不启用 RUN_PGVECTOR_TESTS 时这 44 项跳过。新增 23 项交接集成测试、3 项账户离线测试，替换 4 项旧 Session 测试和 1 项旧内存接管测试。
- 集成测试覆盖请求幂等、两个请求并发申请、两个客服竞争、租户/客户/方法权限、合法状态约束、状态与系统消息一起回滚、上下文与正式历史分离、消息重试、旧租约、分页以及真实登录/CSRF。
- 使用 CountDownLatch 阻塞候选生成，断言模型不在事务中；在其完成前提交人工申请，再放行模型，确认正式历史没有该 BOT 候选。没有用固定 sleep 推测竞态。
- 真实 HTTP 使用两个客户及两个客服分别登录。Qwen 将 “A10001 发货了吗？”分到 ORDER_QUERY；重复申请回执不变；两位客服并发领取只有一个成功，另一位 409；非归属客户 404、客户访问队列 403；只有领取人能结束。
- 实际停止并重新启动进程，再登录：CLOSED 的受理号、版本和 9 条记录一致；另一条 WAITING_HUMAN 的受理号、版本、6 条记录及队列仍一致。
- 浏览器完成密码登录、新建、问候、重置上下文、真实知识回答生成途中申请人工、丢弃候选、等待中保存补充消息及刷新恢复。页面显示“等待领取”，没有冒称已连接真人。1280 和 390 宽度无横向溢出，当前验收页面无控制台错误。

本次真实模型与浏览器验收是小样本行为证据，不是分类总体准确率、真实真人接通率或生产可靠性证明。未实现坐席聊天界面、真人消息发送、AI 摘要、SSE/WebSocket、工单外部通知和会话多次交接；新代码中的中文注释说明了这些边界。
