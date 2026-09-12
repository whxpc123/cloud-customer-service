# 章节变更记录

本项目每章对应独立 Git 提交和 `chapter-NN` 标签。可以在 GitHub 的 Commits 页面看提交，或比较相邻标签查看代码差异。

**历史说明：**第 1～3 章最初在同一工作目录中递进开发，尚未使用 Git。以下三个版本于 2026-09-12 根据本任务中已实现的代码补建，提交时间是补建时间。各标签的代码已分别执行构建验证。

## 第一章 · `chapter-01` · Spring Boot 首次连接 Qwen

- 创建单个 Java 17 Maven 项目，锁定 Spring Boot 3.5.8、Spring AI 1.1.2、Alibaba 1.1.2.2。
- 新增启动类、`AiConfig`、`GET /api/chat`。
- 从 `DASHSCOPE_API_KEY` 读取凭证，默认模型 qwen-plus、端口 18080。
- 补充 Maven Wrapper、IDEA Application 运行配置及启动说明。
- 验证：2 项自动测试通过；真实聊天返回 HTTP 200；IDEA JDK 17 启动通过。
- 详情：[第一章](docs/chapters/01-first-chat.md)。
