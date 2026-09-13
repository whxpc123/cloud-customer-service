# cloud-customer-service

根据《第一章：老板下午要看的 AI 客服》实现的 Java 学习项目。后续章节在这个项目上逐步增加能力，每章的改动与验收方式记录在 `docs/chapters/`。

当前进度：**第四章——Chat Memory，按 conversationId 维护多轮上下文**。

章节记录：[第一章](docs/chapters/01-first-chat.md) · [第二章](docs/chapters/02-system-prompt.md) · [第三章](docs/chapters/03-structured-output.md) · [第四章](docs/chapters/04-chat-memory.md)。

每章对应独立 Git 提交和 `chapter-NN` 标签，具体变化见 [CHANGELOG](CHANGELOG.md)。第 1～3 章历史根据已实现代码于 2026-09-12 补建；后续每章验收完成后提交并推送。

| 章节版本 | 新增能力 | 代码差异 |
| --- | --- | --- |
| [chapter-01](https://github.com/whxpc123/cloud-customer-service/tree/chapter-01) | Spring Boot 连接 Qwen，聊天接口 | 初始版本 |
| [chapter-02](https://github.com/whxpc123/cloud-customer-service/tree/chapter-02) | System Prompt、客服身份与行为约束 | [与第一章比较](https://github.com/whxpc123/cloud-customer-service/compare/chapter-01...chapter-02) |
| [chapter-03](https://github.com/whxpc123/cloud-customer-service/tree/chapter-03) | Structured Output、意图识别与结果校验 | [与第二章比较](https://github.com/whxpc123/cloud-customer-service/compare/chapter-02...chapter-03) |
| [chapter-04](https://github.com/whxpc123/cloud-customer-service/tree/chapter-04) | Chat Memory、会话接口、历史意图识别 | [与第三章比较](https://github.com/whxpc123/cloud-customer-service/compare/chapter-03...chapter-04) |

在 GitHub 选择对应标签查看该章完整代码，在 Compare 页面选择相邻标签查看改动。阅读历史版本可以使用独立工作目录，例如 `git worktree add ../chapter-01-view chapter-01`，避免覆盖当前开发目录。

## 版本

| 组件 | 版本 |
| --- | --- |
| JDK | 17 |
| Spring Boot | 3.5.8 |
| Spring AI | 1.1.2 |
| Spring AI Alibaba | 1.1.2.2 |
| Maven Wrapper 下载的 Maven | 3.9.11 |
| 模型 | qwen-plus |

默认端口为 **18080**（本机 8080 和 8081 已被其他服务占用）。

版本按章节锁定。项目不依赖 Lombok，也不需要数据库、Redis 或 Node.js。

## 在 IntelliJ IDEA 中启动

1. 选择 **File → Open**，打开本目录中的 `pom.xml`，选择作为项目打开，等待 Maven 依赖同步完成。也可以直接打开 `cloud-customer-service` 文件夹。
2. 在 **File → Project Structure → Project SDK** 中选择 **JDK 17**，Language Level 选择 **17**。Maven 的 Importer / Runner JDK 也选择 Project SDK。
3. 如果 IDEA 需要选择 Maven，在 **Settings → Build, Execution, Deployment → Build Tools → Maven** 中选择 **Use Maven wrapper**。第一次下载依赖需要联网。
4. 项目已附带 `CloudCustomerServiceApplication` 运行配置，导入后选择它并点击运行。也可通过启动类的 `main` 方法创建配置；使用普通 **Application** 配置即可，无需 IDEA Ultimate。
5. 应用读取已有环境变量 `DASHSCOPE_API_KEY`。本机已验证 IDEA 可以继承此变量，直接运行即可；若其他电脑未配置该变量，再在 **Run → Edit Configurations → Environment variables** 中添加。
6. 看到 `Started CloudCustomerServiceApplication` 后，访问：

   <http://localhost:18080/api/chat?message=你好>

返回的是模型生成的纯文本，具体措辞每次可能不同。现在访问根路径 <http://localhost:18080/> 可打开可视化聊天工作台。

**注意环境变量名称：本项目使用 `DASHSCOPE_API_KEY`，不是章节示例中的 `AI_DASHSCOPE_API_KEY`。它是变量名，变量值需要填写真实 Key。**

从 Dock 启动的 IDEA 不一定继承终端中 export 的变量，建议在运行配置里设置；不要把真实 Key 写入 `application.yml` 或共享配置。项目不会自动加载 `.env` 文件。

## 从终端启动（macOS / Linux）

在项目目录执行：

```bash
# macOS：选择本机已安装的 JDK 17
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
export PATH="$JAVA_HOME/bin:$PATH"

# 如果当前终端尚未设置该变量，请填写自己的 Key
export DASHSCOPE_API_KEY='你的真实百炼APIKey'

./mvnw spring-boot:run
```

Windows PowerShell：

```powershell
$env:DASHSCOPE_API_KEY = '你的真实百炼APIKey'
.\mvnw.cmd spring-boot:run
```

另开终端调用接口：

```bash
curl --get 'http://localhost:18080/api/chat' \
  --data-urlencode 'message=你好'
```

每次访问都会真实调用模型，按百炼账户计费。本章使用同步调用，等待时间取决于网络和模型。

## 测试与打包

```bash
./mvnw test
./mvnw clean package
java -jar target/cloud-customer-service-0.0.1-SNAPSHOT.jar
```

自动测试替换了 ChatModel，不访问百炼、不需要真实 Key，覆盖聊天回归、结构化转换、非法字段、异常兜底、角色隔离和日志。当前共 39 项测试。启动应用和运行 JAR 仍需真实 `DASHSCOPE_API_KEY`。

## 目录与章节对应

```text
cloud-customer-service/
├── pom.xml                         # 统一版本和依赖
├── mvnw / mvnw.cmd                 # Maven 启动脚本
├── src/main/java/com/example/cloudcustomerservice/
│   ├── CloudCustomerServiceApplication.java
│   ├── config/AiConfig.java         # 创建 ChatClient
│   └── controller/ChatController.java # GET /api/chat
├── src/main/resources/application.yml
├── src/test/java/com/example/cloudcustomerservice/
│   └── CloudCustomerServiceApplicationTest.java
└── docs/chapters/                   # 各章改动与验收记录
```

第二章使用 `AiConfig.defaultSystem(...)` 给每次请求添加客服身份和规则，Controller 仍只通过 `.user(message)` 传入当前问题。第三章新增独立的 `intentChatClient` 和 `POST /api/intents/recognize`，使用 `.entity(outputConverter)` 返回 Java 对象。第四章在此基础上增加会话记忆和统一会话接口；RAG、Tool Calling 等等待后续章节。

打开 `requests.http` 可逐组运行本章实验：身份、无关请求、退款状态、连续对话和提示词注入。Prompt 是行为指导，不能代替真实订单数据或后端权限；模型措辞和遵循程度可能随调用变化。真实回复仍可能出现未经验证的商城入口建议，详见第二章验收记录。

## 常见问题

- **找不到 `DASHSCOPE_API_KEY` / API Key 配置异常**：在当前 IDEA 运行配置或启动终端中设置环境变量，然后重启应用。
- **401 / InvalidApiKey**：检查百炼 Key 是否有效，以及所属地域是否与接口匹配。这里使用 Starter 默认的中国内地 DashScope 服务地址；其他地域的 Key 需要配套调整服务地址。
- **429 / 配额不足**：检查百炼账户额度、模型开通状态及限流。
- **18080 端口被占用**：增加环境变量 `SERVER_PORT=18081`，访问地址同步改为 18081。
- **依赖下载失败**：先检查网络和 IDEA Maven 配置，再重新加载 Maven 项目。无需改用不兼容的 Spring AI 2.x。
- **中文提问**：浏览器可直接输入中文；curl 推荐使用上面的 `--data-urlencode`。


## 第三章：意图识别

当前已增加格式日志：调用识别接口后，在 IDEA Run 控制台搜索 `[Intent JSON Schema]` 和 `[Intent Output Format]`。设置环境变量 `INTENT_SCHEMA_LOG_LEVEL=INFO` 并重启可关闭。完整说明及实际格式文件见 [第三章日志说明](docs/chapters/03-structured-output.md#查看实际-json-schema-和输出格式日志)。

```bash
curl 'http://localhost:18080/api/intents/recognize' \
  -H 'Content-Type: application/json' \
  -d '{"message":"我的订单 A10001 到哪里了？"}'
```

响应字段：`intent`、`orderNo`、`confidence`、`missingFields`。七种意图为商品咨询、订单查询、物流查询、退款申请、人工客服、OTHER 和 UNKNOWN，对应枚举见第三章说明。

`requests.http` 已附带各分类及边界实验。在 IDEA 中重启原运行配置即可使用；凭证仍读取 `DASHSCOPE_API_KEY`。

空消息、超过 4000 个 Java 字符单位的消息、模型异常和非法结果返回 HTTP 200 + `UNKNOWN / null / 0.0 / []`；无效 HTTP JSON 请求体返回 400。UNKNOWN 同时包括无法理解和识别失败，不应作为识别成功统计。此接口只做分类，不查询订单、不退款、不保留聊天历史；confidence 不是校准后的正确率。

## 查看完整提示词和模型返回

项目附带的 IDEA `CloudCustomerServiceApplication` 运行配置已设置程序参数 `--app.ai.log-payload=true`。重启后调用任一接口，在 **Run 控制台**搜索：

- `[LLM REQUEST]`：实际交给 ChatModel 的 SYSTEM / USER 文本。意图识别请求包含 Spring AI 最后追加的完整格式说明和 JSON Schema。
- `[LLM RESPONSE]`：模型返回的原始文本，发生在 `.entity()` 转换与 Java 字段校验之前。

两段日志使用相同的 `id` 配对，`client` 区分聊天和意图识别。原始返回即使不合法也会打印，因此可能与接口最终返回的 UNKNOWN 兜底不同。这里记录消息文本，不是 HTTP 请求体、请求头或 API Key。

终端 / JAR 启动默认关闭完整文本日志；本地需要时设置 `AI_LOG_PAYLOAD=true`。**IDEA 共享配置中的程序参数优先于环境变量**，关闭时在 **Run → Edit Configurations → Program arguments** 删除该参数或改为 `--app.ai.log-payload=false`，然后重启。开启后日志会包含客户原文，适合本地排查。

`INTENT_SCHEMA_LOG_LEVEL` 只控制前面的静态 Schema / 格式日志，与完整文本开关独立；完整提示词本身包含 Schema，关闭静态日志不会将它从完整提示词日志中移除。

## 第四章：使用一套会话接口完成聊天和意图识别

1. `POST /api/conversations` 创建会话，返回 `conversationId`（201）。
2. `POST /api/conversations/{conversationId}/messages`，请求 `{"message":"我的订单是 A10001。"}`。
3. 使用相同 ID 再发送 `{"message":"我想把它退掉。"}`，查看返回的 `intent.orderNo` 和 `answer`。
4. `DELETE /api/conversations/{conversationId}/memory` 清空该会话记忆（204）。

每条消息统一返回 `conversationId`、`intent`、`answer`，前端只需调用一次消息接口；后端先分类再生成回答，正常会调用 Qwen 两次。`requests.http` 已追加第四章六组实验，也可按 [第四章说明](docs/chapters/04-chat-memory.md) 使用 curl。

旧 `/api/chat` 和 `/api/intents/recognize` 保留为前几章的无状态实验。新会话接口使用内存中的最近 20 条消息，重启后失忆；同一会话需等待当前请求完成再发送下一条。当前没有登录与会话归属校验，ID 隔离仅用于本地学习，不代表用户权限隔离。

原有 `[LLM REQUEST]` / `[LLM RESPONSE]` 日志继续可用：客服请求可以看到多条历史 USER / ASSISTANT，分类请求可以看到 `<conversation_history>`，两者都使用原来的日志开关。

## 可视化聊天工作台

IDEA 启动后打开 <http://localhost:18080/>。页面随 Spring Boot 提供，不需要 Node.js、前端构建或第二个端口。

- 左侧新建、切换会话；中间输入消息，Enter 发送，Shift + Enter 换行。
- 右侧展示意图、订单号、模型自评置信度和缺失字段，可展开结构化 JSON，也可选择历史回复查看。
- 清空记忆会先确认；页面记录保留，并显示上下文重置分隔线。
- 当前标签页通过 sessionStorage 保留会话与草稿，刷新可恢复；关闭标签页后不保证保留。后端重启仍会失忆，页面记录不会自动补送给模型。
- 发送中锁定会话操作，支持等待与错误状态；手机上会话列表横向排列，识别面板折叠显示。

实现与实测说明：[第四章补充：可视化工作台](docs/chapters/04-web-workbench.md)。页面中的回复仍由模型生成，不能当作已验证的商城政策。
