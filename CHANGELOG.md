# 章节变更记录

本项目每章对应独立 Git 提交和 `chapter-NN` 标签。可以在 GitHub 的 Commits 页面看提交，或比较相邻标签查看代码差异。

**历史说明：**第 1～3 章最初在同一工作目录中递进开发，尚未使用 Git。以下三个版本于 2026-09-12 根据本任务中已实现的代码补建，提交时间是补建时间。各标签的代码已分别执行构建验证。

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
