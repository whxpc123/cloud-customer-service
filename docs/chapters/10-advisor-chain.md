# 第十章：Advisor 调用链与多轮知识问答

本章把模型调用前后的记忆、检索、证据检查与审计组成可复用的同步链。沿用 Java 17、Spring AI 1.1.2 和 Spring AI Alibaba 1.1.2.2；新增 `spring-ai-advisors-vector-store` 依赖，版本由原 BOM 管理。API Key 仍只读取 `DASHSCOPE_API_KEY`。

第九章 `CustomerKnowledgeAnswerService` 和 `/internal/rag` 保留为手动 RAG 对照；第十章通过独立的 `knowledgeConversationChatClient` 展示 Advisor 方式。普通客服、意图分类和订单工具仍使用原客户端。前端每轮只发一次知识消息请求，不需要再调用检索接口拼装回答。

## 一、从哪里阅读代码

所有路径相对 `src/main/java/com/example/cloudcustomerservice/`，新增类、方法与关键分支都附中文注释。

| 文件 | 职责 |
| --- | --- |
| `ai/advisor/CustomerAdvisorOrders` | 集中定义顺序，避免只依赖注册先后 |
| `ai/advisor/CustomerAdvisorContextKeys` | 本次调用的 requestId、tenantId、userId 常量 |
| `ai/advisor/RequestAuditAdvisor` | 验证 Context，审计耗时、结果与异常类型 |
| `ai/advisor/CustomerAdvisorConfiguration` | 装配四个 Advisor，复用已有 ChatMemory、VectorStore |
| `ai/advisor/EvidenceRequiredAdvisor` | 在模型边界前检查真实文档与范围，无证据阻断 |
| `config/KnowledgeChatClientConfiguration` | 稳定系统规则、低温度、默认 Advisor 和现有正文日志开关 |
| `knowledge/KnowledgeFilterFactory` | 校验租户，构造已发布中文售后知识的动态过滤器 |
| `rag/AdvisorKnowledgeAnswerService` | 构造隔离键，只触发一次终结方法，从同一结果取回答和来源 |
| `rag/AdvisorKnowledgeAnswerResponse` | 增加会话 ID、requestId、实际检索原文，复用第九章状态和来源结构 |
| `rag/LocalAdvisorKnowledgeController` | 服务器确定演示租户，提供创建、发送、清空和页面接口 |
| `rag/NoKnowledgeEvidenceException` | 将预期的空证据分支传回 Service，转换为 NO_EVIDENCE |

页面由 Spring Boot 提供：`resources/advisor-lab/index.html`、`resources/static/advisor-lab.js` 和 `advisor-lab.css`，复用已有实验室样式，没有新增 Node.js 项目。

## 二、调用顺序

```text
请求：Audit → Memory → QuestionAnswerAdvisor → Evidence Gate → ChatModel
响应：Audit ← Memory ← QuestionAnswerAdvisor ← Evidence Gate ← ChatModel
```

`order` 越小越早进入，越晚退出；四层分别使用 `HIGHEST_PRECEDENCE + 10 / 100 / 200 / 300`。自动测试用打乱注册顺序的 A/B/C 验证 `A-before, B-before, C-before, C-after, B-after, A-after`，还故意把 Gate 排在检索前，验证它会因 Context 尚无文档而拒答。

- Audit 先校验非空、有长度及字符限制的 requestId、tenantId、显式知识会话键以及完全匹配的过滤表达式；失败时不能进入 Memory、VectorStore 或模型。
- Memory 读取本会话历史，并在调用后续链之前保存本次原始客户问题。
- 官方 QuestionAnswerAdvisor 按当前 UserMessage 搜索，固定 Top K 5、阈值 0.60；将正文拼入 Prompt，把 Document 列表放入 `QuestionAnswerAdvisor.RETRIEVED_DOCUMENTS`。
- Gate 检查同一 Context 中的 Document，不二次检索。缺失、空列表、全部无正文返回 NO_EVIDENCE；不合法类型、无效分数、越范围文档或混合空白与有效正文视为异常，返回 TEMPORARILY_UNAVAILABLE。异常文档不会进入 ChatModel 和正文日志。
- ChatModel 仅在有合范围的正文证据时执行。低温度和提示词只指导表达，不能证明生成事实正确。

这里的“无证据不调用模型”准确指**不调用聊天生成模型**。搜索之前已经使用 Embedding 模型生成查询向量，因此无命中并不等于完全没有模型费用。

Service 只调用一次：

```java
ChatClientResponse response = client.prompt().user(query)
        .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, memoryId)
                .param(QuestionAnswerAdvisor.FILTER_EXPRESSION, filter)
                .param(CustomerAdvisorContextKeys.REQUEST_ID, requestId)
                .param(CustomerAdvisorContextKeys.TENANT_ID, tenantId)
                .param(CustomerAdvisorContextKeys.USER_ID, userId))
        .call().chatClientResponse();
```

随后从 `response.chatResponse()` 取文本，从 `response.context()` 取来源；不能为了获取不同字段再执行 `.content()`，否则会再次触发整个调用链。测试同时验证一次向量搜索和一次 ChatModel.call。

## 三、Context、身份与过滤

Advisor Context 是应用内部的单次调用数据，不是 System Prompt，也不会因为 `.param(...)` 自动变成模型文本。QAA 会显式把检索正文写入 Prompt。`ToolContext` 则是第五章 Java 工具执行时读取身份的机制，两者不能直接互换；本章没有绑定订单工具。

内部记忆键为：

```text
knowledge/<tenantId>/<guest 或 user-N>/<externalConversationId>
```

相同外部会话编号在不同租户、用户、访客和普通客服之间使用不同键。清空也使用同一个完整键，不能只拿外部会话 ID 清理。Advisor 单例中不保存当前用户、租户或会话等可变字段；请求数据只在局部变量和 Context 中流转。

当前控制器固定租户 `tenant-yunshan`。`X-Tenant-Id` 或 JSON 中的 tenantId 不改变服务器范围。过滤条件为：

```text
tenantId == 'tenant-yunshan' && status == 'PUBLISHED'
&& knowledgeBase == 'after-sales' && language == 'zh-CN'
```

`X-Demo-User-Id` 可选，缺省为访客，只接受正整数。这是本地演示身份，调用者能自行修改，不是生产登录或会话归属校验。真实部署需要认证后的服务端身份、会话所有权检查和访问控制；profile 只是环境开关。

## 四、与文章示例核对后的版本语义

本项目以锁定的 Spring AI 1.1.2 实现与真实调用链测试为准：

1. **框架存在默认 conversationId。** 不能依赖“未传 ID 框架自然报错”；Audit 必须检查显式隔离键，阻止不同客户落到默认历史。缺 ID 测试验证仓库与模型不被触发。
2. **Memory 在 before 阶段写入客户原文。** 后续无证据、网络异常或生成失败时，这条原文可能已经进入窗口；这里没有自动回滚。正常返回时再保存助手消息。UI 说明此行为，避免把重发当成没有副作用的重试。
3. **Memory 排在 RAG 前，不意味着自动 Query Rewrite。** QAA 搜索仍然读取当前问题，历史仅进入模型上下文。响应的 `retrievalQuery` 是去除首尾空白后的当前原文。
4. **QAA 的 Prompt 增强拼接的是 Document 正文。** 本实现没有像第九章手动格式化器那样把来源版本等元数据一起放进模型输入。完整 Document 留在 Context，由 Java 返回来源名称、版本、块号、相似度和正文。这些是检索来源，不能冒充逐句验证后的引用。
5. **Memory 保存原始问题，而不是本轮拼接的知识正文。** 当前 Advisor 顺序避免把整份增强 Prompt 写回历史；旧的模型答复仍然会保存，其中也可能有错误。每轮提示明确旧答复不是本次证据。

模板保留框架要求的 `{query}` 和 `{question_answer_context}` 两个占位符。正文与问题是字面输入，角色要求和资料中的命令不能授权改变业务权限；但本章没有证明提示注入不可成功。

## 五、IDEA 与浏览器操作

先按第七章启动现有数据库：`./scripts/start-knowledge-db.sh`。在 IDEA 同步 Maven，运行共享配置 `CloudCustomerServiceApplication`，profiles 为 `local,knowledge`，打开 <http://127.0.0.1:18080/internal/advisor-rag>。

1. 如果没有知识，先从“导入与检索”导入售后资料；本页不会自动导入固定答案。
2. 选择演示身份，页面自动创建会话；先问完整的退货问题，再追问“那运费呢？”。
3. 右侧查看实际查询、requestId、来源全文和 JSON。历史答复的“查看这轮依据”只改变展示，不重新检索或调用模型。
4. “无证据实验”填入咖啡问题，点击发送后观察是否 NO_EVIDENCE，具体命中取决于当前知识库内容。
5. 清空模型记忆先确认；取消不发删除请求，确认成功才显示分界。之前页面记录保留，可回看旧来源。
6. 刷新页面或新建会话会获得新 ID；切换用户会新建会话。发送时锁定状态变更，后端同一会话仍要求调用方串行发送/清空；多标签页或多个 HTTP 调用者并不具有服务端整轮互斥。

页面顶端展示的是配置顺序，不是假装逐项实时运行的进度。当前不持久化页面会话，服务端使用最多 20 条消息的内存窗口，重启丢失历史。

### HTTP 合约

| 方法与路径 | 作用 / 返回 |
| --- | --- |
| GET `/internal/advisor-rag` | 页面，200 |
| POST `/internal/advisor-rag/conversations` | 创建 UUID，201，不调用模型 |
| POST `/internal/advisor-rag/conversations/{id}/messages` | JSON `{"question":"那运费呢？"}`，返回一轮结果 |
| DELETE `/internal/advisor-rag/conversations/{id}/memory` | 清空当前身份下记忆，204 |

成功处理消息返回 HTTP 200，业务状态为 ANSWERED / NO_EVIDENCE / TEMPORARILY_UNAVAILABLE。ANSWERED 只表示取得非空模型文本，也可能是模型拒答或冲突提示。问题空白、超过 2000 个 Java 字符单位、非法会话或身份返回 400。未启用所需 profiles 时页面与接口 404。可直接使用根目录 `requests.http` 的第十章请求组。

### 日志

IDEA 搜索 `[AI AUDIT]`：requestId、tenantId、durationMs、hasResponse、status 和必要的异常类型；搜索 `[AI EVIDENCE]`：真实证据数量。无证据审计明确记录 `modelCalled=false`。这些审计行不包含完整问题、资料、会话键、异常正文或 API Key。

原有 `[LLM REQUEST]` / `[LLM RESPONSE]` 仍受 `app.ai.log-payload` 控制；当前共享 IDEA 配置显式开启，故控制台整体仍会出现完整 Prompt 和结果。审计 requestId 与正文装饰器的 id 是两套标识，不能假定相等。源码默认关闭正文日志，关闭 IDEA 参数后重启生效。

## 六、验收记录（2026-09-14）

### 自动测试与打包：PASS

最终提示词微调后执行：

```bash
RUN_PGVECTOR_TESTS=true \
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home \
./mvnw package
```

结果：**135 项、0 失败、0 错误、0 跳过，BUILD SUCCESS**，09:14:07 +08:00 完成。包括新增 23 项 Advisor 测试和原有 7 项真实 pgvector 集成测试。自动测试不调用百炼；pgvector 使用专用测试库及模拟 Embedding。JS `node --check` 通过。

新增覆盖包括：正向/反向顺序、错误顺序反例、缺上下文短路、严格过滤、范围异常、一次终结调用、完整来源、原文记忆与失败语义、身份/租户/旧客服隔离、跨租户并发、清空、HTTP 参数与 profile 边界。

### IDEA 主流程启动与在线问答：已执行

IDEA 控制台观察到 Java 17 进程 **57286**，`local,knowledge` 启用；09:07:10.595 Tomcat 在 18080 启动，09:07:10.604 打印 `Started CloudCustomerServiceApplication`，用时 2.432 秒。第十章页面 HTTP 200。

| 实验 | 实际观察 |
| --- | --- |
| 首问“我买错了衣服，想退货，有什么条件？” | ANSWERED；3 个知识块，分数约 0.6872 / 0.6616 / 0.6523 |
| 同会话“那运费呢？” | ANSWERED；实际查询仍是这句简短追问，召回 refund-policy v3.2 第 2 块，分数 0.6016916；返回质量原因商城承担、个人原因原则上消费者承担 |
| “你们老板最喜欢什么咖啡？” | NO_EVIDENCE，references=[]；审计 requestId `b3727850-8c70-4763-9ac4-18ef2b23bea0`，durationMs=143，hasResponse=false，modelCalled=false |
| 清空弹窗取消，再确认 | 取消保留状态；确认后显示“记忆已清空”，同会话 ID 保留，旧页面记录仍在，右侧当前依据清空 |
| 浏览器控制台 | 当时检查无 error 日志 |

首次生成**没有完全遵守证据约束**：增加了资料未写的“吊牌未拆、包装齐全”，并把“按质量问题售后流程处理”推断成“不受七日和完好条件限制”。这不是检索来源已核验的结论，不能作为通过案例。来源卡片让这个问题可被发现，但 Gate 不执行回答逐句事实核查。

随后在系统提示中增加“不把概括条件扩写成细则、不把质量售后推断为豁免所有限制、只说明相关资料缺口”。该最终微调已编译、测试、打包通过，**在线复验尚未完成**：再次操作 IDEA 时桌面控制无响应，随后浏览器控制返回 `Codex auth token is unavailable`；最后端口检查仍为旧进程 57286。下一次在 IDEA 重启后才会加载微调，不声称它已消除上述问题。

本轮未完成用户切换的浏览器实测、最终完整页面截图或移动设备验收；身份隔离和清空后的历史行为有自动化调用链测试，但不能等同于这些浏览器/设备检查。

## 七、本章边界

这里只实现同步 `CallAdvisor`，不能把 `.call()` 改成 `.stream()` 就声称审计和 Gate 自动支持 SSE。Query Rewrite、RetrievalAugmentationAdvisor、混合检索、重排序、生产认证和订单 Tool + RAG 协作留待对应章节。

阈值 0.60 未经召回评测校准；追问这次恰好命中运费资料，不能证明所有省略主语的追问都能命中。模型生成质量仍需核对证据与后续评测。Advisor 负责调用过程，不替代退款、支付或订单业务服务。
