# 第十五章：政策与订单事实的只读售后预检查

本章在同一 Spring Boot 项目里新增 `/internal/after-sale`。不是新的独立 Demo，也没有修改第五章的订单结构；新增用途明确的 `OrderFactsReader` 接口和本地教学实现。没有创建售后申请、批准退款或操作支付的代码。

## 如何运行与体验

使用既有 IDEA 共享配置 `CloudCustomerServiceApplication`（JDK 17、`local,knowledge`、18080）或在项目根目录运行打包 JAR，继续从环境读取 `DASHSCOPE_API_KEY`。

打开 <http://127.0.0.1:18080/internal/after-sale>。页面自动建立本地浏览器 Session 与服务器登记的会话，固定演示账户 1001。按教学场景切换问题，再点击“查询并预检查”；下拉框本身不会收费调用模型。

| 教学订单 | 固定数据及预期路径 |
| --- | --- |
| A10001 | 账户 1001，普通商品，质量未核验，适用 refund-policy / 3.2；质量诉求返回待核验，个人原因返回无理由期限已过 |
| A10002 | 另一个教学账户的订单；当前会话得到统一的 NOT_ACCESSIBLE，不返回事实或政策，也不暴露归属 |
| A10003 | 已激活软件，指定的软件政策版本未加载；返回 NO_EVIDENCE，不借普通商品政策解释 |
| A10004 | 账户 1001，质量核验已确认；仍需审核，不自动批准退款 |
| A10005 | 账户 1001，指定的 not-loaded 版本未加载；有订单事实但没有适用证据 |

样例签收时间为 `2026-08-20T10:00:00+08:00`、无理由截止时间为 `2026-08-27T23:59:59+08:00`。运行时使用 `Clock.systemUTC()`，它们不是“现在签收十天”的实时订单。测试才固定时钟为 `2026-08-30T02:00:00Z`。这些数据和政策均为教学资料，不作为现实权益判断。

普通商品需要已有第七章样例 `refund-policy / 3.2` 处于已发布状态。若本机未导入、被归档或仅有其他版本，返回 NO_EVIDENCE；本章不自动覆盖用户知识库，不植入新政策。软件版本缺失是故意保留的安全失败示例。

## 固定执行顺序

```text
浏览器本地 Session + 会话所有权检查
→ 只装配 Memory 的 afterSaleChatClient
→ 模型请求 inspectReturnRequest
→ Java 校验服务端 Actor 与用户实际指定的订单号
→ OrderFactsReader 同时检查 tenantId、userId、orderNo
→ 根据真实返回的 productType / policySourceId / policyVersion 检索
→ ReturnPrecheckRules 执行有限规则
→ 本轮 Assessment 证据包
→ 模型解释 + 有限输出防错
→ 页面确定性状态与真实来源
```

政策检索在工具内部进行，不给此客户端叠加入口 RAG Advisor。沿用真实 VectorStore，Top 4、阈值 0.50，并精确过滤租户、中文售后知识库、PUBLISHED、sourceId、sourceVersion。返回后再复核范围与版本，异常结果不能用于解释。每块最多 12000 字符，超出上限报依赖不可用，不静默截断政策。

向量 Query 只包含受控商品类型、用户诉求及质量核验枚举，不包含订单号、用户 ID、地址或手机号。特殊商品保留已激活/未激活软件的区别。政策版本与截止时间来自业务适配器，不允许模型填写或从政策文字临时算出退款资格。

本章没有复用第十四章全库混合召回，因为适用版本范围必须先确定；未来可以在该精确范围内增加 Hybrid/Rerank。第十三章云端重排配置待办不阻塞本章的工具内部检索。

## 代码阅读顺序

- `AfterSaleModel`：Actor、用户原因、质量核验、订单事实、政策证据和 Assessment 契约；没有 APPROVED/REFUNDED。
- `LocalOrderFactsReader`：固定本地样例，同时校验三个归属字段，不修改第五章数据。
- `ApplicablePolicyRetriever`：取得订单后检索适用版本，范围和返回数据二次复核。
- `ReturnPrecheckRules`：纯 Java 规则与期限边界；质量分支独立于无理由期限。
- `ReturnAssessmentService`：固定顺序编排，未授权不检索、无证据不推断资格。
- `AfterSaleTools`：注解只读工具、服务端 ToolContext、每请求证据列表与三次查询上限。
- `AfterSaleToolResultConverter`：向模型序列化带时区 ISO 日期，避免让模型自行换算数值时间。
- `AfterSaleChatService`：隔离记忆、含糊订单澄清、每轮工具实例、自然语言与实际执行记录分离。
- `AfterSaleExplanationGuard`：有限拦截批准承诺、错误状态/ISO 日期时间和已观察到的无依据流程；不是完整事实判定器。
- `LocalAfterSaleController`：回环限制、Origin/Host 校验、Session CSRF 与会话登记/所有权检查。

## 身份、会话与执行边界

目前没有真实登录系统，所以此入口**仅供受控本地教学**，不能连接生产订单或直接部署公网。`local & knowledge` 外不注册；只允许回环地址和 localhost/127.0.0.1/IPv6 回环 Host，拒绝跨站 Origin。身份固定为服务器创建的 `Actor(tenant-yunshan,1001)`，不接受请求正文、模型参数或 `X-Demo-User-Id` 修改。

会话不是仅校验 UUID 格式：服务器在当前 HttpSession 中登记创建的会话及私有记忆键。其他 Session 即使知道 UUID 也不能发消息或清空记忆，统一返回 404。修改请求必须携带会话的 CSRF token；最多十个会话，30 分钟空闲后过期并清理记忆。这是实际的本地会话所有权检查，**不是生产用户认证**；将来需要用真实登录 principal 构造 Actor 并实现持久化所有权与业务授权。

用户当前明确编号优先；当前没有编号时只从用户历史取，不从助手猜测中取。历史存在多笔订单且当前只说“它”，Java 直接追问，不调用模型或订单工具。模型猜测用户没有提过的订单也会被拒绝，即使该订单恰好属于当前账户。

每轮都创建新的工具对象并重新查订单。工具 Schema 只有 orderNo、claimedReason；身份、允许编号和 requestId 经 ToolContext 注入。只有实际工具结果才进入 assessments。无工具结果时，用 Java 提示替换模型结论；模型在工具执行后失败时仍保留实际已完成的证据包，返回 EXPLANATION_UNAVAILABLE。

`claimedReason=QUALITY_ISSUE` 不会修改 `qualityVerification`。期限在截止瞬间尚未超过，后一秒才过期。签收缺失/未来时间需要补充信息，截止早于签收等异常和特殊商品进入进一步审核。在期限内也只是一个条件成立，不代表完整资格。

无权限、无适用证据、依赖失败的模型解释一律替换为 Java 固定结论。其他分支对已观察到的批准承诺、状态、ISO 日期/时间和流程词做有限校验；失败时返回实际 Assessment.explanation，并把最终展示的版本写入记忆，避免拦截文本污染后续对话。仍不能保证每种自然语言表达都正确，任何模型解释都不能用作写操作授权。

## 接口

使用同一 Cookie jar，按顺序请求（IDEA `requests.http` 已提供响应变量脚本）：

1. `GET /internal/after-sale/session` → 本地 Session Cookie、csrfToken、LOCAL_FIXTURE 说明。
2. `POST /internal/after-sale/conversations`，携带 `X-AfterSale-CSRF` → 服务器登记 conversationId。
3. `POST /internal/after-sale/conversations/{id}/messages`，同 Cookie 与 CSRF，正文 `{"message":"A10001 的商品有质量问题，我能退吗？"}`。
4. `DELETE /internal/after-sale/conversations/{id}/memory`，同 Cookie 与 CSRF → 204。

响应包括 `requestId/status/answer/assessments/explanationFiltered/dataMode`。每个 Assessment 包含 `verifiedFacts/claimedReason/evidence/checkedAt/refundExecuted`，其中 `refundExecuted` 永远 false。外层 CHECKED 表示有本轮工具记录，具体是否依据不足、需核验等以每个 Assessment.status 为准。

缺少 Session 返回 401，CSRF/来源错误 403，会话不属于当前 Session 返回 404，十个会话上限返回 429。400 用于空问题或超过 4000 字符的输入。响应不泄露依赖异常正文。

## 验证（2026-09-24）

`RUN_PGVECTOR_TESTS=true ./mvnw package`（JDK 17）共 **234 项通过、0 失败、0 错误、0 跳过**，包含 **21 项真实 PostgreSQL 集成测试**。本章新增 20 项，涵盖：

- 期限前一秒/当时/后一秒，质量未核验/确认/驳回，特殊商品和缺失/异常事实；
- 未授权立即停止、租户与用户同时约束、真实 pgvector 精确版本/状态/范围过滤；
- 实际 Spring ToolCallingManager 调用注解工具，Schema 不含 Actor，重复追问重新读取；
- Session 归属、跨站/回环限制、CSRF、伪造身份头、多订单含糊追问；
- 无工具的批准幻觉、查询故障、工具预算、模型失败保留证据、ISO 日期与有限输出防错。

普通测试不访问百炼；不启用真实数据库测试时，21 项集成测试按环境跳过。

在线首轮观察到模型把样例日期说错一天，并编造拍照/寄回与自动重试流程。增加日期序列化与输出保护后重新验证，不能把初次自然语言回答描述为正确。核心结构化状态在初次与后续验证中均正确。

本次只读取用户现有政策，没有修改或归档任何资料，没有产生退款记录。打包 JAR 实际由终端在 18080 启动；IDEA 共享配置仍可使用，当前未宣称 IDEA 自动启动。


### 实际在线结果

| 场景 | 实际结构化结果 |
| --- | --- |
| A10001 质量问题 | NEED_QUALITY_VERIFICATION；系统仍为 UNVERIFIED，返回 refund-policy / 3.2 的 2 个证据块 |
| A10001 个人原因退货 | NO_REASON_WINDOW_EXPIRED；保留其他售后渠道的边界，没有批准或退款 |
| A10002 | NOT_ACCESSIBLE；事实为空、政策证据为空 |
| A10005 | NO_EVIDENCE；保留已查到的样例订单事实，不采用相似的 3.2 版本替代 not-loaded |
| 以上多订单历史后只问“它” | NOT_CHECKED；Java 要求明确编号，没有工具记录 |

最终打包版本的质量问题在线调用触发解释保护，页面实际展示 Java 的“尚未核验、未提交申请或执行退款”，而不是把原模型解释当成已验证文本。

页面已验证来源展开、会话清空和桌面/390px 宽度布局；窄屏 scrollWidth 与视口均为 390，控制台无错误。没有测试或宣称生产订单系统已接通，也不以小样例通过代替总体准确率评测。
