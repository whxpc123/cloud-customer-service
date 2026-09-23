# 第十一章：多轮查询转换与模块化 RAG

## 本章解决的问题

第十章会记住聊天，但 QuestionAnswerAdvisor 仍用当前原文做向量检索。“那运费呢？”缺少退货原因，不能只靠最终回答模型看到历史来修正检索。本章在**原有知识会话接口**中加入历史补全；普通客服、订单工具和第九章无记忆 RAG 保留。

打开 <http://127.0.0.1:18080/internal/query-transformation>。左侧发送完整问题建立真实知识会话；右侧观察原始追问和独立查询，可以显式启用 Rewrite、比较两条查询的 Top 5。实验本身不生成客服答复、不写入会话历史。

## 代码入口与执行顺序

| 代码 | 职责 |
| --- | --- |
| `rag/config/CustomerModularRagConfiguration` | 独立温度 0 的转换客户端、官方 Compression / Rewrite、检索器、增强器与 RAA |
| `rag/query/SafeQueryTransformer` | 历史裁剪、无历史优化、有限输出校验、回退与澄清 |
| `rag/query/QueryTransformationTrace` | 每次请求独立的转换轨迹和真实检索文本 |
| `rag/query/LocalQueryTransformationService` | 同身份会话的只读实验、可选 Rewrite / 检索比较 |
| `rag/AdvisorKnowledgeAnswerService` | 原接口、隔离键、状态与来源响应 |
| `ai/advisor/EvidenceRequiredAdvisor` | 转换澄清和空证据阻断、来源范围复核 |
| `static/query-lab.js` | 不依赖 Node 的原生前端，所有动态内容按文本呈现 |
| `QueryTransformationTest` | 官方 Spring AI 链 + 替代收费模型的回归测试 |

正式链：

```text
RequestAuditAdvisor
  → MessageChatMemoryAdvisor
  → RetrievalAugmentationAdvisor
      → CompressionQueryTransformer（独立模型调用）
      → VectorStoreDocumentRetriever（查询 Embedding → pgvector）
      → ContextualQueryAugmenter
  → EvidenceRequiredAdvisor
  → 最终回答 ChatModel
```

默认没有 Rewrite。实验开启后，顺序为 Compression → Rewrite。两个转换器使用独立 `ChatClient.Builder`，不挂记忆、工具或 RAG，避免递归和重复记忆写入。温度 0 不能保证语义完全正确。

按锁定的 **Spring AI 1.1.2 源码**接入：RAA 初始 Query.history 包括系统消息和当前问题，适配层移除末尾当前问题，再保留最近 20 条用户/助手消息。最终 QueryAugmenter 使用框架保留的原始问题；记忆保存客户原话，不把改写结果当作客户说过的话。Context Map 可能被框架复制，故使用每请求独立的 trace 容器传递观察状态，不能放到单例 Advisor 字段。

检索参数仍为 Top K 5、阈值 0.60。过滤参数改用 `VectorStoreDocumentRetriever.FILTER_EXPRESSION`；来源读取改用 `RetrievalAugmentationAdvisor.DOCUMENT_CONTEXT`。过滤器由 Java 固定为当前租户、`PUBLISHED`、`after-sales`、`zh-CN`，转换结果不能覆盖权限。

单查询用 `SyncTaskExecutor` 执行检索；同知识会话的发送、清空和实验使用共享的 256 个分段锁，防止读取半轮历史。锁碰撞可能让少量不同会话串行，不会混用数据；未来并行多查询需重新设计线程池与轨迹。

## API 与观察字段

仍通过 `POST /internal/advisor-rag/conversations` 创建 ID；先向 `POST /internal/advisor-rag/conversations/{id}/messages` 发送上文，然后调用：

```http
POST /internal/query-transformation/{id}/compress
Content-Type: application/json
X-Demo-User-Id: 1001

{"question":"那运费呢？","rewrite":false,"compare":true}
```

`X-Demo-User-Id` 必须与建立历史时一致；它只是本地演示身份，不是真实认证。租户由服务端固定，客户端不能提供任意历史或过滤表达式。

响应含 `requestId`、`conversationId`、`transformation`、`originalSearch`、`transformedSearch`、`comparisonStatus`。转换轨迹示例：

```json
{
  "originalQuery": "那运费呢？",
  "transformedQuery": "因个人原因无理由退货时，退货运费由谁承担？",
  "historyMessageCount": 2,
  "clarificationRequired": false,
  "stages": [{"name":"COMPRESSION","status":"TRANSFORMED","durationMs":617}]
}
```

这是一次在线观察的文本与耗时，不是固定输出。compare 关闭时两侧搜索为 null；开启时真实检索两次。`FAILED` 表示比较失败，不能解读为零命中。`SKIPPED_CLARIFICATION` 表示未搜索。

正式问答响应新增 `transformation`；`retrievalQuery` 仅在进入检索器后赋值，可能为 null。原管理台历史评测记录允许没有 transformation。管理台和多轮实验页展示原话、查询、转换阶段，且支持新状态 `NEEDS_CLARIFICATION`。

## 回退、证据与成本

- 无历史且问题完整：`SKIPPED_NO_HISTORY`，省去 Compression 模型调用。
- 无历史的明显含糊追问：`NEEDS_CLARIFICATION`，不搜索、不调用最终回答模型。
- 转换失败、空结果、过长结果、明显新增数字/改订单号/否定状态反转：回退输入。若输入仍依赖指代，则要求澄清；完整问题可按原文检索。
- 在线发现的“从助手话里借用七日无理由”另加有限规则拦截；规则仅覆盖已定义数字、订单格式和政策词，不是完整的自然语言事实校验。
- Context 以不可修改 Map 交给 delegate，且只采用 delegate 的文本；其 history/context 不被采纳。
- `allowEmptyContext(false)` 不是硬阻断，实际靠 Java 证据门停止最终生成。无证据时前面可能已调用查询转换模型，因此日志写 `generationCalled=false`。
- 评测遇到 `NEEDS_CLARIFICATION` 单列错误，不计入 NO_EVIDENCE 匹配或来源命中分母。

普通有历史的知识问答通常多一次 Compression 模型调用；可选 Rewrite 再多一次。比较实验还多两次 Embedding/向量查询。阶段耗时不等于整轮耗时；逻辑阶段数量不包含底层 SDK 重试，也不是 token、费用或首 token 延迟统计。应用不额外重试转换失败；底层供应商重试按其配置执行。

默认摘要日志 `[QUERY TRANSFORM]` 仅输出 requestId、阶段、状态、历史条数、文本长度与耗时。已有 `app.ai.log-payload=true` 会额外打印 `queryTransformer` 的完整请求/结果，可能包含用户历史；IDEA 的显式日志开关继续有效。API Key 仍只从 `DASHSCOPE_API_KEY` 读取。

## 验证记录（2026-09-24）

构建命令：`RUN_PGVECTOR_TESTS=true JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home ./mvnw package`。**158 项测试，0 失败、0 错误、0 跳过**，包含 14 项真实 PostgreSQL 测试。随后仅细化提示样例，另行回归查询转换和多轮链的 27 项测试，全部通过。

自动化覆盖实际转换→检索传参、记忆原文、温度与提示隔离、无历史优化、转换异常、非法输出、否定词/编号、政策限定词、空证据、实验读写边界、两个转换的顺序、跨身份、有限历史、并发清空、HTTP 校验和非知识 profile 不开放页面。真实 PostgreSQL 集成测试仍使用专用测试库，不清空用户知识库。

在线初次观察：

| 用户上文 / 追问 | 结果 |
| --- | --- |
| 买错衣服、无理由退货 / 那运费呢 | 补成个人原因无理由退货运费问题；正式回答返回实际来源 |
| 商品坏了、退货 / 那运费呢 | 补成质量问题退货运费问题；可选 Rewrite 保持语义 |
| 未激活软件 / 它可以退吗 | 保留“未激活”，但首次错误增加“七日无理由”；已增加约束和回归拦截 |
| 订单 A10001 / 它现在到哪了 | 补成订单 A10001 的物流状态问题，仅实验转换，没有调用订单工具 |
| 无历史 / 那运费呢 | 0 ms 跳过转换模型并要求澄清，retrievalQuery 为 null |

浏览器初次对比中，原“那运费呢？”命中 1 块（0.6017），补全后命中 5 块（首块 0.7761）；这只是当前数据与阈值下的单例，不代表总体召回率提高。软件场景复测已补成“未激活的软件可以退吗？”，耗时 436 ms，不再增加政策条件。旧页面回归还观察到“买错衣服、退货”被模型扩成“无理由退货”后被保护层拦截，因此补充了该场景的明确提示样例；补充后最终浏览器复测补成“因买错衣服申请退货，运费由谁承担？”，耗时 535 ms，比较检索完成。随后通过原知识问答接口复测同一追问，状态 ANSWERED、实际检索文本一致，返回 2 块来源，Compression 耗时 537 ms。该类误拦截仍需持续评测。

用户批准停止占用 18080 的 `invoice_backend` 容器，保留容器和数据。IDEA 自动重启仍遇到 `noWindowsAvailable`，因此实际验收由终端运行打包 JAR（`local,knowledge`，18080）。IDEA 原运行配置也使用 18080；切换到 IDEA 运行前先停止终端的本项目进程，避免双实例争端口。恢复发票后端也需要先释放 18080。

浏览器检查了真实上文发送、转换前后检索对照、无历史澄清和管理台新状态；控制台未发现错误。390px 视口下单列布局的文档宽度/滚动宽度均为 390px，未出现横向溢出，验收后恢复原视口。页面返回资料与模型回答均使用安全文本展示。

## 本章边界

未实现 MultiQueryExpander、重排、SSE、真实登录、多实例会话锁、持久化聊天历史或完整语义等价评分。检索来源不证明最终回答逐句正确；转换成功也不保证没有遗漏条件，需要固定数据集持续评测。无历史指代检测与输出校验是有限规则，可能漏判或误判。没有修改已发布数据库迁移，也没有改变向量模型或重新导入用户文档。
