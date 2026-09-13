# cloud-customer-service

根据《第一章：老板下午要看的 AI 客服》实现的 Java 学习项目。后续章节在这个项目上逐步增加能力，每章的改动与验收方式记录在 `docs/chapters/`。

当前进度：**第七章——PgVectorStore、知识持久化与元数据过滤检索**。

章节记录：[第一章](docs/chapters/01-first-chat.md) · [第二章](docs/chapters/02-system-prompt.md) · [第三章](docs/chapters/03-structured-output.md) · [第四章](docs/chapters/04-chat-memory.md) · [第五章](docs/chapters/05-tool-calling.md) · [第六章](docs/chapters/06-embedding-lab.md) · [第七章](docs/chapters/07-pgvector-knowledge.md)。

每章对应独立 Git 提交和 `chapter-NN` 标签，具体变化见 [CHANGELOG](CHANGELOG.md)。第 1～3 章历史根据已实现代码于 2026-09-12 补建；后续每章验收完成后提交并推送。

| 章节版本 | 新增能力 | 代码差异 |
| --- | --- | --- |
| [chapter-01](https://github.com/whxpc123/cloud-customer-service/tree/chapter-01) | Spring Boot 连接 Qwen，聊天接口 | 初始版本 |
| [chapter-02](https://github.com/whxpc123/cloud-customer-service/tree/chapter-02) | System Prompt、客服身份与行为约束 | [与第一章比较](https://github.com/whxpc123/cloud-customer-service/compare/chapter-01...chapter-02) |
| [chapter-03](https://github.com/whxpc123/cloud-customer-service/tree/chapter-03) | Structured Output、意图识别与结果校验 | [与第二章比较](https://github.com/whxpc123/cloud-customer-service/compare/chapter-02...chapter-03) |
| [chapter-04](https://github.com/whxpc123/cloud-customer-service/tree/chapter-04) | Chat Memory、会话接口、历史意图识别 | [与第三章比较](https://github.com/whxpc123/cloud-customer-service/compare/chapter-03...chapter-04) |
| [chapter-05](https://github.com/whxpc123/cloud-customer-service/tree/chapter-05) | Tool Calling、订单归属查询、页面实际工具结果 | [与第四章比较](https://github.com/whxpc123/cloud-customer-service/compare/chapter-04...chapter-05) |
| [chapter-06](https://github.com/whxpc123/cloud-customer-service/tree/chapter-06) | Embedding、余弦相似度、语义实验室 | [与第五章比较](https://github.com/whxpc123/cloud-customer-service/compare/chapter-05...chapter-06) |
| [chapter-07](https://github.com/whxpc123/cloud-customer-service/tree/chapter-07) | PgVectorStore、Flyway、持久化知识检索与页面 | [与第六章比较](https://github.com/whxpc123/cloud-customer-service/compare/chapter-06...chapter-07) |

在 GitHub 选择对应标签查看该章完整代码，在 Compare 页面选择相邻标签查看改动。阅读历史版本可以使用独立工作目录，例如 `git worktree add ../chapter-01-view chapter-01`，避免覆盖当前开发目录。

## 版本

| 组件 | 版本 |
| --- | --- |
| JDK | 17 |
| Spring Boot | 3.5.8 |
| Spring AI | 1.1.2 |
| Spring AI Alibaba | 1.1.2.2 |
| Maven Wrapper 下载的 Maven | 3.9.11 |
| 聊天模型 | qwen-plus |
| 向量模型 | text-embedding-v4，1024 维；第六章 document，第七章入库 document / 检索 query |
| 知识库 | PostgreSQL 17.10 + pgvector 0.8.2，Docker 镜像摘要固定 |

默认端口为 **18080**（本机 8080 和 8081 已被其他服务占用）。

版本按章节锁定。项目不依赖 Lombok、Redis 或 Node.js。第七章需要 Docker 中的 PostgreSQL + pgvector；不启用 knowledge 时，前六章仍无需数据库。

## 在 IntelliJ IDEA 中启动

1. 选择 **File → Open**，打开本目录中的 `pom.xml`，选择作为项目打开，等待 Maven 依赖同步完成。也可以直接打开 `cloud-customer-service` 文件夹。
2. 在 **File → Project Structure → Project SDK** 中选择 **JDK 17**，Language Level 选择 **17**。Maven 的 Importer / Runner JDK 也选择 Project SDK。
3. 如果 IDEA 需要选择 Maven，在 **Settings → Build, Execution, Deployment → Build Tools → Maven** 中选择 **Use Maven wrapper**。第一次下载依赖需要联网。
4. 项目已附带 `CloudCustomerServiceApplication` 运行配置，导入后选择它并点击运行。此配置已开启 `local,knowledge`。**首次运行前先启动 Docker，再在项目终端执行 `./scripts/start-knowledge-db.sh`**，生成本地数据库配置并等待数据库就绪。只运行前六章可将 profiles 改为 `local`。也可通过启动类的 `main` 方法创建配置；使用普通 **Application** 配置即可，无需 IDEA Ultimate。
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

自动测试在需要调用的路径替换 ChatModel / EmbeddingModel，不访问百炼、不需要真实 Key，覆盖聊天回归、结构化转换、非法字段、异常兜底、角色隔离和日志。当前共有 85 项测试：普通运行通过 81 项、跳过 4 项需真实 pgvector 的集成测试；启用专用测试库后 85 项全部通过。覆盖工具执行、会话隔离、向量数学、批次边界、知识过滤、重复导入及异常保护。集成测试步骤见第七章文档。启动应用和运行 JAR 仍需真实 `DASHSCOPE_API_KEY`。

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

第二章使用 `AiConfig.defaultSystem(...)` 给每次请求添加客服身份和规则，Controller 仍只通过 `.user(message)` 传入当前问题。第三章新增独立的 `intentChatClient` 和 `POST /api/intents/recognize`，使用 `.entity(outputConverter)` 返回 Java 对象。第四章增加会话记忆和统一会话接口；第五章在 `order/`、`tool/` 下增加模拟订单服务和只读 Tool Calling。第六章在 `embedding/` 下增加向量生成、余弦计算和语义排序；第七章在 `knowledge/` 下增加 PgVectorStore 持久化、过滤检索和独立实验页面；文档 ETL 与 RAG 等待后续章节。

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

每条消息统一返回 `conversationId`、`intent`、`answer`，第五章另增加 `orderLookups`，前端只需调用一次消息接口；后端先分类再生成回答，通常先分类再调用客服模型；第五章执行工具后，还会继续请求模型生成回答。`requests.http` 已追加第四章六组实验，也可按 [第四章说明](docs/chapters/04-chat-memory.md) 使用 curl。

旧 `/api/chat` 和 `/api/intents/recognize` 保留为前几章的无状态实验。新会话接口使用内存中的最近 20 条消息，重启后失忆；同一会话需等待当前请求完成再发送下一条。第五章按演示用户与会话 ID 共同隔离记忆；无请求头仍为访客。演示请求头可以被调用方修改，当前没有真实登录认证或会话归属校验，不能作为生产权限边界。

原有 `[LLM REQUEST]` / `[LLM RESPONSE]` 日志继续可用：客服请求可以看到多条历史 USER / ASSISTANT，分类请求可以看到 `<conversation_history>`，两者都使用原来的日志开关。

## 可视化聊天工作台

IDEA 启动后打开 <http://localhost:18080/>。页面随 Spring Boot 提供，不需要 Node.js、前端构建或第二个端口。

- 左侧选择演示用户 1001 / 2002 / 访客，切换用户会新建会话；点回旧会话时恢复它的身份。中间输入消息，Enter 发送，Shift + Enter 换行。
- 右侧展示意图、订单号、模型自评置信度和缺失字段；新增订单查询面板，展示本轮实际执行次数与工具 JSON。可选择历史回复查看。
- 清空记忆会先确认；页面记录保留，并显示上下文重置分隔线。
- 当前标签页通过 sessionStorage 保留会话与草稿，刷新可恢复；关闭标签页后不保证保留。后端重启仍会失忆，页面记录不会自动补送给模型。
- 发送中锁定会话操作，支持等待与错误状态；手机上会话列表横向排列，识别面板折叠显示。

实现与实测说明：[第四章补充：可视化工作台](docs/chapters/04-web-workbench.md)。页面中的回复仍由模型生成，不能当作已验证的商城政策。


## 第五章：让模型调用订单查询工具

在页面选择 **用户 1001**，发送“ A10001 发货了吗？”，再问“它现在是什么状态？请重新查一下。”。切换 **用户 2002** 后，查询 A10001 应得到 `NOT_FOUND`，查询 A20002 可返回 `PACKING`。

| 演示用户 | 自有订单 | 固定状态 | 固定样例预计送达 |
| --- | --- | --- | --- |
| 1001 | A10001 | SHIPPED（已发货） | 2026-08-20 |
| 2002 | A20002 | PACKING（打包中） | 2026-08-22 |

以上为本地模拟数据，日期沿用文章，不能视为当前配送承诺。没有接入生产订单系统、退款办理或实时物流轨迹。

仍使用 `POST /api/conversations/{conversationId}/messages`，只增加可选请求头 `X-Demo-User-Id: 1001`。清空记忆时携带相同请求头；缺省为访客，订单工具返回 `AUTHENTICATION_REQUIRED`。非正整数或非法请求头返回 400。模型工具参数只有 `orderNo`，身份从应用传入的 `ToolContext` 读取；真实项目应将演示请求头替换为认证后的服务端身份。

`orderLookups` 是 Java 工具实际执行记录，空数组表示本轮未执行。实测中模型会在部分追问中复用历史日期，甚至给出未经验证的入口建议；Prompt 不能保证调用或措辞可靠。以工具结果面板为准，不把自然语言当作最新查询凭据。

IDEA 控制台除原日志外，可搜索 `[TOOL DEFINITION]`（实际名称、说明、输入 Schema）、`[TOOL REQUEST]`、`[TOOL RESULT]`。沿用 `app.ai.log-payload` 开关。DashScope 在内部完成多轮工具调用，当前装饰器记录初始完整 Prompt 和最终文本，**不包含每一次中间 HTTP 报文**；工具执行输入和输出由 Java 单独记录，不打印 ToolContext 或请求头。

详见 [第五章实现与验收](docs/chapters/05-tool-calling.md)，接口实验在 `requests.http`。


## 第六章：语义实验室

IDEA 使用共享运行配置启动后，点击工作台顶部的“语义实验室”，或打开 <http://localhost:18080/internal/embedding-lab>。不需要新增依赖、前端构建或数据库。

- **两句话比较**：输入两段文本，返回实际维度和 −1 到 1 的余弦相似度；内置相近表达、无关话题、相反结论、不同订单号四组样例。
- **候选文本排序**：输入查询和 1–20 条候选，按分数降序展示全部候选；同分时保持输入顺序。
- 每段最多 2000 个 Java 字符单位。只返回摘要与候选文本，不返回完整向量，也不把实验政策写入客服对话。

接口：`POST /internal/embedding-lab/compare` 接收 `{"left":"我要退货","right":"东西不想要了"}`；`POST /internal/embedding-lab/rank` 接收 `{"query":"想寄回去","candidates":["退货申请流程","物流异常处理"]}`。

实验页面和两个接口仅在 `local` profile 注册。当前共享 IDEA 参数为 `--spring.profiles.active=local,knowledge`；只使用第六章时可改为 `local`。终端运行可使用 `SPRING_PROFILES_ACTIVE=local ./mvnw spring-boot:run`。普通启动未启用 local 时，实验页面与 API 返回 404，客服接口仍可用。Profile 是环境开关，不是身份认证。

向量服务仍读取 `DASHSCOPE_API_KEY`。单次比较通常一批；20 条候选加查询分为 10 + 10 + 1 条，避免超出 v4 单批限制。每次排序重新向量化所有候选，不保存向量；第七章的知识库页面则将文档向量持久化，搜索时只向量化问题。

IDEA 搜索 `[EMBEDDING RESULT]` 可看输入数量、批数、实际维度和耗时；失败记录 `[EMBEDDING ERROR]` 的异常类型。此日志不包含原文或向量，独立于聊天的完整提示词日志开关。非法业务输入返回 400，上游异常或非法向量返回稳定 502。

本次真实模型观察：相近表达约 **0.6130**，无关天气约 **0.2998**；相反结论约 **0.8672**，不同订单号约 **0.9711**。分数不是概率或业务结论，不设置未经评测的匹配阈值。

详见 [第六章实现与验收](docs/chapters/06-embedding-lab.md) 和 [真实实验记录](docs/chapters/06-live-observations.json)。


## 第七章：持久化知识库

先启动 Docker，在项目目录执行 `./scripts/start-knowledge-db.sh`，再运行 IDEA 的共享配置。打开 <http://localhost:18080/internal/knowledge>，点击“导入 / 更新 4 条知识”，即可搜索课程示例政策、调整 Top K / 阈值、查看来源与 JSON。

数据库绑定 `127.0.0.1:15432`，随机密码只保存于被忽略的 `.local/`；命名卷保存知识与向量。保留数据卷时也须保留原密码文件。重启数据库和 IDEA 后无需重新导入，聊天的内存历史仍会清空。

终端启动第七章（先配置 Java 17 和已有的 `DASHSCOPE_API_KEY`）：

```bash
./scripts/start-knowledge-db.sh
SPRING_PROFILES_ACTIVE=local,knowledge ./mvnw spring-boot:run \
  -Dspring-boot.run.jvmArguments='-DsocksNonProxyHosts=localhost|127.*|[::1]'
```

IDEA 已配置同一 JVM 参数，确保本机 SOCKS 代理环境下 PostgreSQL 回环连接正常。普通终端启动未启用 knowledge 时仍不连接数据库。

真实验收：重复导入仍为 4 行，1024 维；数据库和应用重启后内容、元数据、向量指纹一致。物流 / 发票在默认 0.60 阈值命中；“衣服买错了，想寄回去”默认无结果，降到 0.50 返回两条退货知识。阈值尚未校准，分数不是概率。

这一步返回知识片段与来源，尚未将知识加入客服 Prompt；RAG 留待后续章节。详见 [第七章实现、测试及限制](docs/chapters/07-pgvector-knowledge.md)。
