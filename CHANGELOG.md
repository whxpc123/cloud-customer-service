# 章节变更记录

本项目每章对应独立 Git 提交和 `chapter-NN` 标签。可以在 GitHub 的 Commits 页面看提交，或比较相邻标签查看代码差异。

**历史说明：**第 1～3 章最初在同一工作目录中递进开发，尚未使用 Git。以下三个版本于 2026-09-12 根据本任务中已实现的代码补建，提交时间是补建时间。各标签的代码已分别执行构建验证。

## 第七章补充 · `chapter-07-import` · 真实文件与正文导入

- 修复知识库只有固定样例导入、无法上传自有资料的问题，增加 TXT / Markdown / PDF 文件上传和粘贴正文入口。
- PDFBox 提取文字；校验格式、大小、页数、编码及正文长度；无文字 PDF 明确提示 OCR。
- 正文分块后调用真实 Embedding 并持久化，结果增加可读的资料名称；同名替换使用事务与来源锁，清理旧块，失败回滚。
- 92 项测试全部通过，IDEA Java 17 实际运行；浏览器实测四种输入、检索、同名更新和 PDF 错误提示，SQL 验证 1024 维与重导不增行，窄屏无横向溢出。
- 保留原 chapter-07；未实现 OCR、Word / Excel 或 RAG。
- 详情：[真实导入实现与验收](docs/chapters/07-real-document-import.md)。

## 第七章 · `chapter-07` · PgVectorStore 与持久化知识检索

- 增加 PostgreSQL + pgvector Docker 配置、随机本地密码启动脚本和 Flyway 迁移，创建 vector(1024) 表及 HNSW 余弦索引。
- 增加四条带稳定 UUID / 来源 / 版本的课程知识；通过 VectorStore.add upsert，重复导入不增行，向量化失败不预先删除旧数据。
- 入库使用 document、搜索使用 query，最多十条一批；只嵌入正文并验证模型返回数量、索引、维度和数值。
- 服务端固定演示租户，按租户 / 发布状态 / 知识库 / 语言过滤；支持 Top K、阈值、来源追踪及真实空结果。
- 新增 local + knowledge 环境的可视化知识库页面，前六章无数据库启动路径保留。
- 85 项测试及打包通过（其中 4 项使用专用 PostgreSQL 测试库）；IDEA Java 17 实际运行，真实百炼检索、重复导入、过滤与数据库/应用重启持久化通过，桌面及 390px / 320px 浏览器检查通过。
- 退款查询默认阈值 0.60 无命中，降到 0.50 命中两条；四条数据未验证八万条性能、HNSW 召回或生产认证，尚未接入 RAG。
- 详情：[第七章实现与验收](docs/chapters/07-pgvector-knowledge.md)。

## 第六章 · `chapter-06` · Embedding 与语义排序

- 配置已有 Starter 的 text-embedding-v4 / 1024 / document，继续使用 DASHSCOPE_API_KEY。
- 增加 TextEmbeddingService、纯 Java 余弦计算、相似度比较与最多 20 条候选排序；按 10 条分批且保持对应关系。
- 新增 local 环境专属实验 API 与可视化页面，展示实际维度、分数、排序和摘要 JSON；IDEA 共享配置启用 local。
- 校验文本、候选数、向量数量/维度/有限数值；输入错误 400，模型异常 502。向量日志仅记录元数据。
- 71 项自动测试与 Maven 打包通过；IDEA Java 17 / local / 18080 启动，真实相似度、排序与 20 候选分批调用通过，浏览器桌面/390px/320px 检查通过。
- 相反政策结论和不同订单号仍得到高分；保留原始观察，不把语义分数当作业务结论。尚未实现向量持久化、VectorStore 或 RAG。
- 详情：[第六章实现与验收](docs/chapters/06-embedding-lab.md)。

## 第五章 · `chapter-05` · Tool Calling 与订单归属查询

- 新增可替换的 `OrderService`、两条本地模拟订单，以及 `queryCurrentUserOrder` 只读工具。
- 模型只提供订单号，应用通过 ToolContext 传入演示身份；后端校验格式、订单归属，返回稳定错误码。
- 会话按演示用户隔离记忆；会话 API 增加可选演示用户头和实际 `orderLookups`。
- 页面新增演示用户切换、实际工具结果及 JSON；日志新增工具 Schema、执行参数和结果。
- 56 项自动测试与 Maven 打包通过；IDEA Java 17 启动，11 组真实 Qwen 请求完成，浏览器验证查询、身份切换和刷新恢复。
- 实测模型在部分追问中未重新执行查询，并可能推测入口或身份；模拟数据与演示请求头不等于生产订单服务或登录认证。新增区域窄屏检查受浏览器工具认证连接错误阻断，未完成。
- 详情：[第五章实现与验收](docs/chapters/05-tool-calling.md)。

## 第四章补充 · 可视化聊天工作台

- 新增 Spring Boot 静态首页，包含会话导航、聊天区与意图识别面板，无需单独启动前端。
- 支持创建/切换会话、连续聊天、查看历史识别 JSON、清空记忆确认、草稿与页面记录恢复。
- 完成桌面、390px 和 320px 窄屏检查；真实接口验证多轮订单、会话隔离、清空和断网错误状态。
- 保留后端 39 项测试通过；本次单独提交，不移动 `chapter-04` 标签。
- 详情：[前端使用与验收](docs/chapters/04-web-workbench.md)。

## 第四章 · `chapter-04` · Chat Memory

- 增加内存版 MessageWindowChatMemory，最多保留 20 条消息；客服 ChatClient 使用 MessageChatMemoryAdvisor。
- 新增创建会话、发送消息和清空记忆接口；单次消息响应同时提供意图识别和客服回答。
- 分类器只读同会话历史，不写入分类 JSON；订单来源校验允许历史客户原文，拒绝仅由客服回答生成的订单号。
- 保留第三章结构化校验、完整提示词日志，以及旧无状态接口；旧聊天使用临时会话并在 finally 中清空。
- 输入错误返回 400，客服模型失败返回 502；分类失败仍为 UNKNOWN。
- 39 项自动测试通过，覆盖多轮、隔离、清空、窗口淘汰、历史来源、输入与异常边界。
- 实际调用和 IDEA 验收结果见 [第四章](docs/chapters/04-chat-memory.md)。

## 第三章补充 · 打印完整提示词与模型原始返回

- 两个 ChatClient 增加 ChatModel 装饰器，记录最终 SYSTEM / USER 文本及转换前的模型返回，使用请求 ID 配对。
- 意图识别日志包含 Spring AI 追加的完整 JSON Schema / 格式说明；不打印请求头、模型配置或 API Key。
- 完整文本默认关闭，`AI_LOG_PAYLOAD=true` 可开启；共享 IDEA 配置显式开启，关闭需移除或修改程序参数。
- 31 项测试通过，覆盖实际提示词、原始非法返回、关闭开关、聊天回归与流式透传；IDEA Java 17 实测两个接口 HTTP 200，完整输入输出日志配对成功。
- 本次为第三章补充提交，保留原 `chapter-03` 标签不动。

## 第三章补充 · 查看 JSON Schema 与格式日志

- 显式复用 BeanOutputConverter，打印实际 getJsonSchema() / getFormat()，并将同一个转换器传入 entity。
- 默认开启本地学习 DEBUG 日志，设置 `INTENT_SCHEMA_LOG_LEVEL=INFO` 可关闭；日志不含客户原文和凭证。
- 保存实际生成的 Schema / 格式说明，并解释普通 Prompt 约束与原生结构化输出的区别。
- 验证日志格式与实际发给 ChatModel 的格式一致，27 项测试通过。
- 本次为第三章补充提交，保留原 `chapter-03` 标签不动。

## 第三章 · `chapter-03` · Structured Output

- 新增 `CustomerIntent` 七类枚举和请求、结果 record。
- 新增 `intentChatClient`，通过 `@Qualifier` 区分聊天与分类。
- 新增 `POST /api/intents/recognize`，使用 `.entity(...)` 返回 Java 对象。
- 增加输入限制、结果验证、UNKNOWN 兜底和不含原文的异常日志。
- 增加用于说明 Java switch 路由的示例，不执行真实退款或订单操作。
- 验证：27 项自动测试通过；七类基础样例真实调用符合预期；IDEA JDK 17 启动通过。
- 详情：[第三章](docs/chapters/03-structured-output.md)。

## 第二章 · `chapter-02` · 客服身份与 System Prompt

- 在 `AiConfig` 统一配置商城客服身份、风格和事实要求。
- System Message 与当前 User Message 分开传递。
- 根据真实模型表现补充未接入订单查询和退款办理的能力说明。
- 验证：3 项自动测试通过；身份、越界、退款及连续对话样例已实测；IDEA JDK 17 启动通过。
- 限制：没有 Memory，Prompt 不能保证事实正确。
- 详情：[第二章](docs/chapters/02-system-prompt.md)。

## 第一章 · `chapter-01` · Spring Boot 首次连接 Qwen

- 创建单个 Java 17 Maven 项目，锁定 Spring Boot 3.5.8、Spring AI 1.1.2、Alibaba 1.1.2.2。
- 新增启动类、`AiConfig`、`GET /api/chat`。
- 从 `DASHSCOPE_API_KEY` 读取凭证，默认模型 qwen-plus、端口 18080。
- 补充 Maven Wrapper、IDEA Application 运行配置及启动说明。
- 验证：2 项自动测试通过；真实聊天返回 HTTP 200；IDEA JDK 17 启动通过。
- 详情：[第一章](docs/chapters/01-first-chat.md)。
