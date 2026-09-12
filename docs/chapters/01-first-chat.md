# 第一章：老板下午要看的 AI 客服

## 本章实现

```text
用户 → ChatController → ChatClient → ChatModel → DashScope → Qwen
```

- `CloudCustomerServiceApplication`：Spring Boot 启动类。
- `AiConfig`：接收 Starter 自动配置的 `ChatClient.Builder`，生成 `ChatClient` Bean。
- `ChatController`：接收 GET `/api/chat?message=...`，按 `prompt().user(message).call().content()` 同步取得回复。
- `application.yml`：从 `DASHSCOPE_API_KEY` 读取凭证，模型为 `qwen-plus`。
- `pom.xml`：锁定 Java 17 / Boot 3.5.8 / Spring AI 1.1.2 / Alibaba 1.1.2.2。

## 与章节示例的差异

1. 根据用户要求，环境变量名改为 `DASHSCOPE_API_KEY`。
2. 补齐 package、import、启动类、完整 Maven 配置以及 Wrapper，使片段成为可运行项目。
3. 接口显式指定 UTF-8 纯文本与参数名称，方便浏览器和命令行调用。
4. 增加不调用外部模型的自动测试与 IDEA 操作说明。
5. 本机 8080 和 8081 已被其他服务占用，因此默认使用 18080，可通过 `SERVER_PORT` 修改。
6. Maven Central 中的 Alibaba 1.1.2.2 BOM 没有管理 DashScope Starter，故为该依赖显式指定 `1.1.2.2`。

## 手动验收

启动后访问 `/api/chat?message=你好`，应得到模型生成的中文文本。
访问 `/api/chat`（缺少 message）应返回 400。

模型尚未设置“云杉商城客服”身份；每次调用独立，不保留历史。它无法获取企业订单或实时物流。

## 后续章节接续

保留同一个启动入口与项目。在收到下一章后，按该章需求扩展代码、增加相应验收，并在本目录新增章节记录。第二章预计加入 Prompt / System Prompt，具体以用户提供的章节为准。

## 本次验证（2026-09-12）

- 使用本机 Temurin JDK 17 执行 `./mvnw -B -ntp clean package`：BUILD SUCCESS。
- 自动测试：2 项通过，0 失败，0 错误。
- 核对 Maven 依赖树：Spring Boot 3.5.8、Spring AI 1.1.2、DashScope Starter 1.1.2.2 生效。
- 使用 JDK 17 启动打包后的 JAR：Tomcat 在 18080 端口启动成功。
- 使用当前环境中的 `DASHSCOPE_API_KEY` 实际请求 `/api/chat`，提问“你好，请用一句中文介绍你自己。”：HTTP 200，返回以“你好！我是通义千问（Qwen）”开头的中文回复。
- 验证后停止测试服务，释放端口供 IDEA 启动使用。

首次验证覆盖 Maven 构建、JAR 启动和真实模型调用。

后续 IDEA 验证（2026-09-12 22:37）：已实际导入项目，将 Project SDK 设为 temurin-17，通过 IDEA 的 Application 配置启动。运行控制台确认 Java 17.0.20.1、Tomcat 18080 和 Started 日志；请求聊天接口返回 HTTP 200 及“你好！很高兴为你提供帮助。”。IDEA 已继承现有 DASHSCOPE_API_KEY，运行配置没有写入 Key。应用保持在 IDEA 中运行。
