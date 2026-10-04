# cloud-customer-service

根据《第一章：老板下午要看的 AI 客服》实现的 Java 学习项目。后续章节在这个项目上逐步增加能力，每章的改动与验收方式记录在 `docs/chapters/`。

当前进度：**第二十九章——原事件结果核查与补记送达**。增加核查台和独立查询权限：只按原正文查询原回执，匹配时原子补记送达；未知结果继续保留待核查，并保存操作人、观察和并发版本。客服端口保持 18080，接收端口 18083。

章节记录：[第一章](docs/chapters/01-first-chat.md) · [第二章](docs/chapters/02-system-prompt.md) · [第三章](docs/chapters/03-structured-output.md) · [第四章](docs/chapters/04-chat-memory.md) · [第五章](docs/chapters/05-tool-calling.md) · [第六章](docs/chapters/06-embedding-lab.md) · [第七章](docs/chapters/07-pgvector-knowledge.md) · [第八章](docs/chapters/08-document-etl.md) · [第九章](docs/chapters/09-manual-rag.md) · [第十章](docs/chapters/10-advisor-chain.md) · [第十章补充：知识管理台](docs/chapters/10-knowledge-management.md) · [第十一章](docs/chapters/11-query-transformation.md) · [第十二章](docs/chapters/12-query-expansion.md) · [第十三章](docs/chapters/13-reranking.md) · [第十四章](docs/chapters/14-hybrid-search.md) · [第十五章](docs/chapters/15-after-sale-precheck.md) · [第十六章](docs/chapters/16-customer-routing.md) · [第十七章](docs/chapters/17-human-handoff.md) · [第十八章](docs/chapters/18-sse-streaming.md) · [第十九章](docs/chapters/19-stage1-acceptance.md) · [第二十章](docs/chapters/20-bounded-draft-agent.md) · [第二十一章](docs/chapters/21-in-process-task-memory.md) · [第二十二章](docs/chapters/22-persistent-task-checkpoints.md) · [第二十三章](docs/chapters/23-immutable-draft-confirmation.md) · [第二十四章](docs/chapters/24-human-in-the-loop.md) · [第二十五章](docs/chapters/25-executable-submission-graph.md) · [第二十六章](docs/chapters/26-idempotent-submission.md) · [第二十七章](docs/chapters/27-transactional-outbox.md) · [第二十八章](docs/chapters/28-inbox-receiver.md) · [第二十九章](docs/chapters/29-result-reconciliation.md)。

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
| [chapter-08](https://github.com/whxpc123/cloud-customer-service/tree/chapter-08) | DocumentReader、Token 切分、预览确认与后台导入 | [与第七章导入补充比较](https://github.com/whxpc123/cloud-customer-service/compare/chapter-07-import...chapter-08) |
| [chapter-09](https://github.com/whxpc123/cloud-customer-service/tree/chapter-09) | 手动 RAG、无证据拒答、真实来源与知识问答页 | [与第八章比较](https://github.com/whxpc123/cloud-customer-service/compare/chapter-08...chapter-09) |
| [chapter-10](https://github.com/whxpc123/cloud-customer-service/tree/chapter-10) | Advisor 调用链、多轮知识问答、证据拦截与审计 | [本章功能改动](https://github.com/whxpc123/cloud-customer-service/compare/ad36c22751e76c176fa1f166711299e85d9d4404...chapter-10) |
| [chapter-10-admin](https://github.com/whxpc123/cloud-customer-service/tree/chapter-10-admin) | 文档目录、原件下载、回收站、多会话知识问答、自建题集评测 | [与第十章比较](https://github.com/whxpc123/cloud-customer-service/compare/chapter-10...chapter-10-admin) |
| [chapter-11](https://github.com/whxpc123/cloud-customer-service/tree/chapter-11) | 历史补全追问、模块化 RAG、查询与检索对照实验 | [与上一章比较](https://github.com/whxpc123/cloud-customer-service/compare/chapter-10-admin...chapter-11) |
| [chapter-12](https://github.com/whxpc123/cloud-customer-service/tree/chapter-12) | 按需多查询扩展、逐路检索、按 ID 合并、单路/多路可视化对照 | [与第十一章比较](https://github.com/whxpc123/cloud-customer-service/compare/chapter-11...chapter-12) |
| [chapter-13](https://github.com/whxpc123/cloud-customer-service/tree/chapter-13) | qwen3-rerank 接口、同候选 A/B 页面、上下文预算与降级；真实精排待配置 | [与第十二章比较](https://github.com/whxpc123/cloud-customer-service/compare/chapter-12...chapter-13) |
| [chapter-14](https://github.com/whxpc123/cloud-customer-service/tree/chapter-14) | 编码精确匹配、PostgreSQL 全文检索、RRF 融合、三路对照与引用来源 | [与第十三章比较](https://github.com/whxpc123/cloud-customer-service/compare/chapter-13...chapter-14) |
| [chapter-15](https://github.com/whxpc123/cloud-customer-service/tree/chapter-15) | 只读售后预检查、适用政策版本、事实证据卡与本地会话归属 | [与第十四章比较](https://github.com/whxpc123/cloud-customer-service/compare/chapter-14...chapter-15) |
| [chapter-16](https://github.com/whxpc123/cloud-customer-service/tree/chapter-16) | 统一入口、有限路由、唯一处理器分派与会话隔离 | [与第十五章比较](https://github.com/whxpc123/cloud-customer-service/compare/chapter-15...chapter-16) |
| [chapter-17](https://github.com/whxpc123/cloud-customer-service/tree/chapter-17) | 持久化受理、客服领取/结束、正式消息与版本发布门 | [与第十六章比较](https://github.com/whxpc123/cloud-customer-service/compare/chapter-16...chapter-17) |
| [chapter-18](https://github.com/whxpc123/cloud-customer-service/tree/chapter-18) | SSE 接待快照、独立模型流、取消与协议校验 | [与第十七章比较](https://github.com/whxpc123/cloud-customer-service/compare/chapter-17...chapter-18) |
| [chapter-19](https://github.com/whxpc123/cloud-customer-service/tree/chapter-19) | 临时数据库验收、证据组评分、真实代理测试与发布门槛报告 | [与第十八章比较](https://github.com/whxpc123/cloud-customer-service/compare/chapter-18...chapter-19) |
| [chapter-20](https://github.com/whxpc123/cloud-customer-service/tree/chapter-20) | 一次运行内的有界 ReAct、只读核验、候选草稿与接待状态保护 | [与第十九章比较](https://github.com/whxpc123/cloud-customer-service/compare/chapter-19...chapter-20) |
| [chapter-21](https://github.com/whxpc123/cloud-customer-service/tree/chapter-21) | 进程内任务续写、检查点摘要、每轮重新核验、并发与失败隔离 | [与第二十章比较](https://github.com/whxpc123/cloud-customer-service/compare/chapter-20...chapter-21) |
| [chapter-22](https://github.com/whxpc123/cloud-customer-service/tree/chapter-22) | 任务与检查点双持久化、版本 CAS、跨进程续写与未完成保护 | [与第二十一章比较](https://github.com/whxpc123/cloud-customer-service/compare/chapter-21...chapter-22) |
| [chapter-23](https://github.com/whxpc123/cloud-customer-service/tree/chapter-23) | 结构化草稿、不可变版本、具体内容确认、旧版与并发保护 | [与第二十二章比较](https://github.com/whxpc123/cloud-customer-service/compare/chapter-22...chapter-23) |
| [chapter-24](https://github.com/whxpc123/cloud-customer-service/tree/chapter-24) | 工具执行前中断、明确操作审批、反馈恢复与模拟执行 | [与第二十三章比较](https://github.com/whxpc123/cloud-customer-service/compare/chapter-23...chapter-24) |
| [chapter-25](https://github.com/whxpc123/cloud-customer-service/tree/chapter-25) | StateGraph 条件分支、状态策略、真实轨迹与路线实验页 | [与第二十四章比较](https://github.com/whxpc123/cloud-customer-service/compare/chapter-24...chapter-25) |
| [chapter-26](https://github.com/whxpc123/cloud-customer-service/tree/chapter-26) | 稳定操作编号、持久化批准、同库事务创建待审核申请与回执重放 | [与第二十五章比较](https://github.com/whxpc123/cloud-customer-service/compare/chapter-25...chapter-26) |
| [chapter-27](https://github.com/whxpc123/cloud-customer-service/tree/chapter-27) | 固定同步授权、同事务 Outbox、领取租约、退避重试与远端持久化回执 | [与第二十六章比较](https://github.com/whxpc123/cloud-customer-service/compare/chapter-26...chapter-27) |
| [chapter-28](https://github.com/whxpc123/cloud-customer-service/tree/chapter-28) | 独立接收服务、JWT 来源认证、Inbox 同事务建单、固定回执回放及业务防重 | [与第二十七章比较](https://github.com/whxpc123/cloud-customer-service/compare/chapter-27...chapter-28) |
| [chapter-29](https://github.com/whxpc123/cloud-customer-service/tree/chapter-29) | 原事件只读查询、证据校验、核查审计、版本防护与原子补记送达 | [与第二十八章比较](https://github.com/whxpc123/cloud-customer-service/compare/chapter-28...chapter-29) |

在 GitHub 选择对应标签查看该章完整代码，在 Compare 页面选择相邻标签查看改动。阅读历史版本可以使用独立工作目录，例如 `git worktree add ../chapter-01-view chapter-01`，避免覆盖当前开发目录。

## 中文注释阅读入口

当前业务 Java、测试、前端 JS / CSS / HTML、构建与运行配置已补充中文说明。类注释说明职责，方法注释说明输入输出及异常边界，关键分支解释会话隔离、工具身份、向量批次、文档切分、事务和证据来源。在 IDEA 将光标放到类型或方法上查看快速文档（macOS 默认 `F1`，以个人快捷键配置为准）。

建议按 `ChatController` → `AiConfig` → `CustomerIntentRecognizer` → `CustomerConversationService` → `CustomerOrderTools` → `SemanticSimilarityService` → `KnowledgeSearchService` → `KnowledgePreparationService` / `KnowledgePreviewService` → `CustomerKnowledgeAnswerService` → `AdvisorKnowledgeAnswerService` / `ai/advisor/` 阅读，并对照同名测试中的中文场景说明。

第一至九章的中文注释补充已作为独立提交保留；后续章节新增代码继续提供中文注释。Maven Wrapper 等第三方生成文件保持原样；已发布的 Flyway SQL 迁移保持原样以维持校验和，逐项中文说明见 [数据库迁移说明](src/main/resources/db/README.md)。

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

返回的是模型生成的纯文本，具体措辞每次可能不同。现在访问根路径 <http://localhost:18080/> 可打开可视化聊天工作台。第九章知识问答在 <http://127.0.0.1:18080/internal/rag>；它使用已导入的知识，当前独立于会话和订单工具。

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

每次访问都会真实调用模型，按百炼账户计费。上述普通聊天接口使用同步调用；第十八章另提供独立模型流式实验，等待时间取决于网络和模型。

## 测试与打包

```bash
./mvnw test
./mvnw clean package
java -jar target/cloud-customer-service-0.0.1-SNAPSHOT.jar
```

自动测试在需要调用的路径替换 ChatModel / EmbeddingModel，不访问百炼、不需要真实 Key，覆盖聊天回归、结构化转换、非法字段、异常兜底、角色隔离和日志。主项目全量范围有 583 项 Java 测试，其中 169 项需要真实 PostgreSQL；通过 `RUN_PGVECTOR_TESTS=true RUN_ACCEPTANCE_TESTS=true ./mvnw package` 开启全部数据库验证，未启用时明确跳过。新增测试不需要真实模型 Key。前端 SSE 解析另有 122 项测试，通过 `node --test scripts/tests/sse-client.test.mjs` 运行（仅此开发测试需要 Node，应用启动不需要）。覆盖工具执行、会话隔离、向量数学、批次边界、知识过滤、重复导入及异常保护。集成测试步骤见第七章文档。启动应用和运行 JAR 仍需真实 `DASHSCOPE_API_KEY`。

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

第二章使用 `AiConfig.defaultSystem(...)` 给每次请求添加客服身份和规则，Controller 仍只通过 `.user(message)` 传入当前问题。第三章新增独立的 `intentChatClient` 和 `POST /api/intents/recognize`，使用 `.entity(outputConverter)` 返回 Java 对象。第四章增加会话记忆和统一会话接口；第五章在 `order/`、`tool/` 下增加模拟订单服务和只读 Tool Calling。第六章在 `embedding/` 下增加向量生成、余弦计算和语义排序；第七章在 `knowledge/` 下增加 PgVectorStore 持久化、过滤检索和独立实验页面；第八章在 `knowledge/ingestion/` 下增加文档 ETL、Token 切分与预览确认；第九章增加手动 RAG，第十章在 `ai/advisor/` 下组织可复用调用链。

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

先启动 Docker，在项目目录执行 `./scripts/start-knowledge-db.sh`，再运行 IDEA 的共享配置。打开 <http://localhost:18080/internal/knowledge>，在“导入自己的资料”中上传 TXT / Markdown / PDF / DOCX / PPTX，或粘贴正文，预览并确认导入后即可搜索、调整 Top K / 阈值、查看来源与 JSON。四条课程样例移到可选折叠区。

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

第七章补充已支持真实文件导入：文件最多 5 MB，PDF 最多 100 页，正文最多 50000 字符；扫描版 PDF 需先做 OCR。同名资料替换采用数据库事务，失败时保留旧内容。详细使用及验收见 [真实导入说明](docs/chapters/07-real-document-import.md)，[GitHub 改动](https://github.com/whxpc123/cloud-customer-service/compare/chapter-07...chapter-07-import)。原 `chapter-07` 标签保留，新版本标签为 `chapter-07-import`。


## 第八章：先预览怎么切，再导入知识

访问 <http://localhost:18080/internal/knowledge>，选择文件或粘贴正文，填写资料名称与版本，切换 200 / 500 / 1000 Token。点击“清理并预览切分”，逐块查看正文、页码、标题、来源和 Hash，再点击“确认这些知识块并导入”。可直接预览内置第八章售后制度。

预览不调用模型、不写库；确认后后台生成向量，成功再在短事务内整体替换同名旧资料。改动输入后需重新预览。任务与预览保存在有界内存中，重启会失效，已入库知识保留。当前只检索资料，尚未接入客服 RAG。

实现、限制和实验步骤见 [第八章说明](docs/chapters/08-document-etl.md)，API 示例在 `requests.http`。


## 第九章：单次知识问答

打开 <http://127.0.0.1:18080/internal/rag>，每次独立检索已导入资料并返回回答和来源，不保留会话记忆。实现记录见 [第九章](docs/chapters/09-manual-rag.md)。

## 第十章：Advisor 多轮知识问答

以下链路描述对应 `chapter-10` 标签；当前版本已在第十一章替换为 Modular RAG，并展示补全后的实际查询。

IDEA 同步 Maven 后运行原 `CloudCustomerServiceApplication` 配置，打开 <http://127.0.0.1:18080/internal/advisor-rag>。沿用 `local,knowledge`、18080 端口与 `DASHSCOPE_API_KEY`，无需新数据库迁移或前端构建。

先问“我买错了衣服，想退货，有什么条件？”，再追问“那运费呢？”。右侧展示每轮实际检索原文、来源全文、版本、分数与 requestId；点历史答复可回看该轮依据。支持新建会话、演示用户切换和清空模型记忆。

链路为 **Audit → Memory → QuestionAnswerAdvisor → Evidence Gate → ChatModel**。只执行一次 `chatClientResponse()`，同时读取回答和检索 Context；无证据直接返回 `NO_EVIDENCE`，不调用聊天模型（查询向量仍需生成）。IDEA 搜索 `[AI AUDIT]` / `[AI EVIDENCE]` 查看审计；原完整提示词日志继续受 `app.ai.log-payload` 控制。

本章仍按当前问题原文搜索，不自动改写追问。记忆按知识业务、租户、演示身份和会话隔离；刷新新建会话，重启丢失内存历史。同会话请求须串行。来源是实际检索块，不代表回答逐句正确；实测曾出现模型扩写未提供的政策细节，详见 [第十章实现与验收](docs/chapters/10-advisor-chain.md)。


## 第十章补充：知识库管理台与基础评测

开启 `local,knowledge` 后访问 <http://127.0.0.1:18080/internal/knowledge-admin>。与现有应用一起启动，无需安装前端工程。

- **文档管理**：搜索、状态/格式筛选、分页；上传 TXT / Markdown / PDF / DOCX / PPTX 或粘贴正文，先预览再导入。详情查看完整切片、原文/提取正文、版本与文件哈希，下载原件或导出知识块。
- **回收站**：移入后不参与新检索，文件和向量保留；可恢复发布。已有会话记忆和历史答案不被改写，需要清空会话后验证下架效果。同名重新导入会替换并发布该来源。
- **知识问答**：新建/切换会话、清空模型记忆、展开每轮实际来源；复用第十章接口。页面记录保存在当前标签页，后端记忆仍在内存。
- **评测分析**：每次 1–20 道自定义题，指定是否应无证据拒答和可选期望来源。保存题集、实际回答与来源、整轮耗时，支持失败/不匹配筛选、历史查看和复制题集再测。

旧资料只有切片，没有原件、原始上传时间；页面明确标注，重新导入后才可下载原文件。基础指标不代表回答正确率，完整定义、接口及验收见[补充说明](docs/chapters/10-knowledge-management.md)。


## 第十一章：多轮查询转换

打开 <http://127.0.0.1:18080/internal/query-transformation>，先建立真实会话，再观察“那运费呢？”如何补全成独立查询。可选开启 Rewrite 和两次真实向量检索的结果对照；实验本身不写入历史。

现有知识问答和管理台自动使用 **Audit → Memory → RetrievalAugmentationAdvisor（Compression → 检索 → 增强）→ Evidence Gate → 最终模型**。无历史的含糊追问返回 `NEEDS_CLARIFICATION`；转换失败有可观察的回退。原 API 增加 `transformation`，未进入检索时 `retrievalQuery` 为 null。来源过滤仍由服务端控制。

有历史的问答通常增加一次转换模型调用；Rewrite 默认关闭。详细代码入口、接口、真实模型偏差与限制见[第十一章实现与验收](docs/chapters/11-query-transformation.md)。端口统一为 **18080**，无需新增数据库迁移。


## 第十二章：多查询扩展与知识合并

打开 <http://127.0.0.1:18080/internal/query-expansion>，选择 1 / 3 / 5 条变体、是否保留补全后的完整问题，并按需开启真实检索对照。页面展示各路查询、命中来源、按 ID 去重数量、上下文规模及单路未找到的候选。

正式多轮知识问答和管理台已接入按需扩展；多轮页面可以切换 AUTO / OFF / ON。成功扩展默认 3 条变体加完整问题，每路 Top 3；单路或失败回退 Top 5，阈值均为 0.60。简单问题默认跳过扩展以减少调用。

扩展和合并不保证逐项都有依据，也不是严格任务拆解或重排。响应和页面保留实际轨迹，回答提示要求逐项说明缺失依据。代码入口、开销、失败保护与真实模型偏差见[第十二章实现与验收](docs/chapters/12-query-expansion.md)。


## 第十三章：重排序与上下文预算

打开 <http://127.0.0.1:18080/internal/rerank>，对同一批候选查看重排前后名次、向量/重排两种分数、最终完整知识块与预算排除原因。正式多轮问答默认启用该后处理链，页面可关闭重排以作对照。

新正式链每路 Top 6、阈值 0.45，合并后最多 24 条进入重排输入筛选，最终最多 6 块、正文估算 5000 tokens。扩展实验页继续保留第十二章历史参数。

沿用 `DASHSCOPE_API_KEY`，另需 `DASHSCOPE_RERANK_BASE_URL` 指定百炼业务空间根地址。本机目前未配置，因此明确显示 `FALLBACK_NOT_CONFIGURED`，原顺序仍可继续问答。不能把降级结果或离线分数当成云端重排验收。配置方式、边界和测试见[第十三章说明](docs/chapters/13-reranking.md)。


## 第十四章：精确编码与混合检索

打开 <http://127.0.0.1:18080/internal/hybrid-search>，比较向量、关键词、编码精确匹配及 RRF 融合。正式知识问答默认使用新召回链，旧章节实验保持原对照语义。导入资料里的 CPN / SKU / POLICY 编码由数据库自动提取和索引，无须重建向量。

精确命中优先，引用展示真实召回来源和不同阶段分数；归档资料退出所有召回。PostgreSQL simple 不提供中文分词，不称作 BM25；订单实时状态仍需业务工具。见 [第十四章实现与验收](docs/chapters/14-hybrid-search.md)。


## 第十五章：政策与订单事实的只读预检查

打开 <http://127.0.0.1:18080/internal/after-sale>，固定本地教学账户 1001，可尝试 A10001 质量问题/个人原因退货、A10002 不可访问、A10005 缺少适用版本。页面分别展示用户诉求、订单事实、政策来源和程序结论。

本入口只在回环地址及 local,knowledge 模式开放，用服务器 Session 注册表验证会话归属，不接受演示身份头切换。没有接入真实登录/订单系统，不能作为生产权限体系。没有创建申请、批准或执行退款；模型解释与确定性检查状态分开。详见 [第十五章实现与验收](docs/chapters/15-after-sale-precheck.md)。

## 第十六章：统一客服入口

以下说明对应 `chapter-16`；当前入口已升级为下节第十七章的登录及持久化接待。打开 <http://127.0.0.1:18080/internal/routing>，在同一个聊天窗口咨询政策、查询订单或进行只读售后预检查。右侧显示实际路由、规则/模型来源、原因、分类耗时与完整业务证据；也可以只诊断当前这一句。

快捷问候和明确转人工不调用分类模型；缺订单号由选中的业务处理器追问；多个独立事项先澄清顺序。未接入人工坐席时明确显示未连接，不假装排队。分类器只读服务器近期历史，实际对话保存最近十轮，先验证 Session 归属再分派；固定演示账户不是生产登录。

本章 273 项测试全通过；真实 Qwen 固定路由样本本次 15/15 匹配，并完成订单转售后追问、知识来源及不可访问/缺政策验证。这不是总体准确率。详见 [第十六章实现与验收](docs/chapters/16-customer-routing.md)，在线复测可显式运行 `python3 scripts/check-routing.py`（会消耗模型额度）。


## 第十七章：人工接待与正式记录

打开 <http://127.0.0.1:18080/internal/routing>。首次运行 `python3 scripts/setup-handoff-accounts.py`，在本机 `.local/handoff-accounts.properties` 查看随机密码，使用 `customer1001` 或 `customer2002` 登录。已有账户文件不会覆盖，也不提交 Git。IDEA 沿用原运行配置，刷新 Maven 后启动 `local,knowledge`、18080；密钥仍使用 `DASHSCOPE_API_KEY`。

客户申请立即取得数据库受理编号，按 `BOT → WAITING_HUMAN → HUMAN_ACTIVE → CLOSED` 流转。support9001 / support9002 使用真实密码登录后才能调用领取与结束 API。重复申请、同客服重试幂等，两位客服同时领取只有一位成功；正式消息按归属查询，刷新和重新登录均可恢复。生成途中申请人工后，迟到候选不会成为正式机器人消息。

已完成 294 项测试和真实 HTTP / 浏览器 / 重启验收。实际运行由终端启动，IDEA 自动切换项目失败，未声称 IDEA 内运行成功。没有真人聊天通道、在线状态或预计等待时间。接口、代码与验证详见[第十七章说明](docs/chapters/17-human-handoff.md)。


## 第十八章：SSE 状态订阅与模型流式实验

统一客服页现在通过 GET SSE 自动显示已保存的接待状态，首次立即返回当前快照，后续服务器顺序查询并仅发送版本变化；断线重连只恢复当前状态，不重放历史事件。正式消息仍以数据库为准。

打开 <http://127.0.0.1:18080/internal/stream-lab>，使用统一客服页已登录的本地账户，观察模型真实片段、首片段耗时与完成事件，也可以停止接收并保留草稿。这是没有 Memory / RAG / 业务工具的独立实验，正式客服链保留原有证据检查和发布门。

318 项 Java 测试及 122 项前端解析测试通过；真实模型、浏览器状态自动更新、取消和桌面/手机布局已验收。当前 18080 由终端启动，IDEA 自动切换仍失败；Nginx 仅提供参考配置，未做实际代理验收。协议、代码入口、资源边界和复测命令见[第十八章说明](docs/chapters/18-sse-streaming.md)。


## 第十九章：第一阶段统一验收

在项目终端执行 `python3 scripts/accept-stage1.py --proxy --live`，以 JDK 17、Docker、专用测试库、本机 Nginx 和现有 `DASHSCOPE_API_KEY` 进行完整验收。`--live` 会实际调用百炼；省略两个开关时只跑离线回归，报告将其余项目标为未执行。

结果写入 `.local/acceptance/<运行编号>/`，含 Markdown/JSON、配置与 Prompt 内容哈希、迁移信息、逐题检索轨迹、结构化业务结果和耗时。报告评分器另有 11 项 Python 标准库测试，运行 `python3 -m unittest discover -s scripts/tests -p 'test_*.py'`；不新增应用运行依赖。

本章复用正式客服链与已有页面。硬门槛不允许被平均通过率抵消；语义人工评审、真实订单/生产身份、云端精排和生产容量等未完成条件保留为发布阻塞。详见[实现与实际验收](docs/chapters/19-stage1-acceptance.md)及[公开汇总](docs/acceptance/chapter-19-summary.json)。


## 第二十章：一次任务内的候选草稿

打开 <http://127.0.0.1:18080/internal/draft-agent>，先在统一客服页登录，再选择 BOT 会话和明确订单。Agent 自主选择核验工具、模板或停止；页面分开展示程序事实、候选文本和实际工具轨迹，也可以勾选只检查。

每轮最多六次模型、八次工具调用，总等待上限 90 秒；工具身份和订单由服务器绑定。候选不进入正式记录，转人工或注销后迟到结果会隐藏。每次请求都是新运行，没有持久化任务、恢复、提交或退款能力。代码、接口、真实验证与边界见[第二十章说明](docs/chapters/20-bounded-draft-agent.md)。


## 第二十一章：接着同一个任务继续

内存对照实验现位于 <http://127.0.0.1:18080/internal/local-draft-tasks>（`chapter-21` 标签仍使用原地址）。用已有客户账户登录，创建一次任务，再逐轮补充或修改。页面显示稳定 taskId、递增轮次、每轮 runId 和只读检查点摘要；历史帮助理解，订单和政策每轮重新查询。

同任务并发返回 409，异常后禁止盲目续跑。最多 100 个任务、每个 8 轮；清理只释放本地任务与检查点。应用重启会丢失任务，下一章再讨论持久化。接口、实现和验证见[第二十一章说明](docs/chapters/21-in-process-task-memory.md)。


## 第二十二章：重启后继续同一任务

主入口 <http://127.0.0.1:18080/internal/draft-tasks> 现在使用 PostgreSQL 保存任务归属、业务范围、版本、最后正常结果及 Graph 检查点。创建任务不调用模型；完成一轮后重启应用，重新登录同一账户即可读取原结果，再提交本轮修改。

每轮重建 Agent / Tools / PostgresSaver，同一任务使用稳定的内部 threadId；通过新保存器确认检查点已实际落库后才保存完成状态。续写必须携带当前 `expectedVersion`，旧版本或并发请求返回 409。模型错误、存储不一致、超时或崩溃不会自动重跑；RUNNING / RECOVERY_REQUIRED 保留供核查。结束任务保留数据库历史。

391 项 Java 测试通过，含 92 项真实数据库验证及两个独立 JVM 的强制终止/恢复验证。仍是本地教学系统，没有自动故障接管、任意节点恢复、正式版本化草稿、审批或业务提交。代码、API、真实模型重启验证和运行边界见[第二十二章说明](docs/chapters/22-persistent-task-checkpoints.md)。


## 第二十三章：确认用户实际看见的版本

在 <http://127.0.0.1:18080/internal/draft-tasks> 正常完成一轮候选后，点击“整理并保存新版本”。模型只提取问题描述和申请诉求，Java 显式校验；订单和预检查快照由服务端填入。页面显示具体版本、事实时间、政策来源，再由用户勾选并确认。

新正文总是新版本，旧确认不会继承。两个窗口的旧版本确认返回 409，页面加载新内容供重新核对，绝不自动确认另一版。当前同版重复请求返回同一回执；开始新的 Agent 轮次立即让旧确认失效，历史正文和回执仍保留。

420 项 Java 测试全部通过，含 112 项真实数据库验证。真实 Qwen 双窗口验收确认：旧 V1 被拒绝后，V2 仍未确认；显式核对 V2 才保存回执，实际应用重启后正文与回执一致，读取不调用模型。最终服务通过终端运行在 18080，IDEA 配置保留，但本次未在 IDEA 内启动。

章节 API、数据库锁、模型边界、测试及真实页面验收见[第二十三章说明](docs/chapters/23-immutable-draft-confirmation.md)。这只是内容确认基础，未实现工具执行前审批、正式售后提交或退款。


## 第二十四章：内容确认之后，审批具体操作

打开 <http://127.0.0.1:18080/internal/draft-tasks/hitl>，选择当前已确认草稿。启动后必须看到真实 WAITING_APPROVAL，执行次数为 0；批准本次模拟操作后才变为 1，拒绝保持 0。原准备任务的版本与 runId 不会因启动审批而改变。

审批精确绑定工具调用编号、参数、草稿与确认；旧版、过期、跨账户、重复决策都不能再次执行。仅开放 APPROVE/REJECT，客户端不能提交图状态或修改操作参数。使用实际 Alibaba 1.1.2.2 的 HumanInTheLoopHook 和 ReturnDirectModelHook。

这是一场进程内实验，重启后审批丢失，数据库草稿保留。真实提交、持久化授权、原子消费、业务幂等与恢复协议仍需后续实现。接口、边界和验证见[第二十四章说明](docs/chapters/24-human-in-the-loop.md)。


验证：全量 450 项 Java 测试通过（123 项真实数据库验证）；最终提示词调整后，30 项审批专项测试与 JAR 打包再次通过。真实模型页面完成暂停、拒绝 0 次、批准 1 次及只读刷新；应用重启后旧审批消失、V2 草稿和确认回执保留。当前 18080 由终端运行，本章未在 IDEA 内启动。


## 第二十五章：让图成为执行流程

打开 <http://127.0.0.1:18080/internal/draft-tasks/flow>，用现有客户账号登录后，选择八种服务器预设场景。每次点击执行一张真实 StateGraph，页面高亮实际路径，并分别展示审批读取、模拟方法调用和模拟副作用计数。可以点击节点查看职责、下载实际编译图生成的 Mermaid。

本页是独立的确定性编排实验：使用虚构任务与审批标识，不读取真实草稿，不调用模型，不创建申请或退款。第 24 章审批流程保持原行为。WAITING_APPROVAL 只表示本次检查返回等待，END 只表示本次图结束；UNKNOWN 明确进入核查，没有自动重试边。

验证：全量 482 项测试通过（含 123 项真实数据库验证）；页面八个场景、刷新只读、键盘操作、Mermaid 下载与 390 px 布局已验收。当前 18080 由终端启动，本章未在 IDEA 内启动。

Java 代码与详细边界见[第二十五章说明](docs/chapters/25-executable-submission-graph.md)。


## 第二十六章：本地申请受理与回执重放

打开 <http://127.0.0.1:18080/internal/draft-tasks/submission>，或从已确认的具体草稿版本进入。准备稳定操作 → 核对固定正文并明确批准 → 执行 → 查询/回放同一回执。首次执行创建真实 PostgreSQL 待审核申请，重复执行保持相同申请编号与创建时间。

批准、有效期、草稿与接待范围在服务端复核；创建申请、操作成功和任务关闭同事务提交。申请受理不代表审核通过或退款，不向外部系统投递。详情与验收见[第二十六章说明](docs/chapters/26-idempotent-submission.md)。

验证：全量 502 项 Java 测试通过（143 项真实数据库验证），本章新增 20 项；浏览器创建与回放、只读刷新、390 px 布局及独立 JVM 重启验证通过。当前 18080 为终端启动，IDEA 项目切换未生效。


## 第二十七章：本地回执与外部同步

继续打开 <http://127.0.0.1:18080/internal/draft-tasks/submission>。新准备操作默认仅本地，可明确选择“创建并同步至售后平台 V1”；页面先展示接收方和发送字段，再由客户批准。范围绑定操作，旧 LOCAL_ONLY 操作不扩大授权，历史申请不补发。

本地申请、Outbox、操作成功和任务关闭同事务提交。后台一次领取一条，30 秒租约，固定 eventId 与正文，最多 8 次指数退避并抖动；有效远端回执才记 DELIVERED，其余分别等待重试或 REVIEW 核查。页面保留本地创建回执，增加独立同步记录及当前客户的积压、耗时和领取分布。

默认 `local,knowledge` 只保存队列。发送须显式增加 `outbox-delivery` 并配置固定 endpoint/token；新增 IDEA 运行配置 `CloudCustomerServiceOutboxDemo` 可配合 `scripts/start-outbox-demo.sh --drop-first-ack` 验证“先落盘、丢第一次回执、重发获得同一结果”。本机模拟接收器不是正式售后平台或生产 Inbox。完整步骤、授权字段和边界见[第二十七章说明](docs/chapters/27-transactional-outbox.md)。

验证：544 项 Java 测试全部通过（含 156 项真实数据库验证）；停用/恢复投递、首次回执丢失后的幂等重发、三 JVM 崩溃恢复、浏览器双回执及 390 px 无横向溢出均已验收。第 27 章验收时，主程序 18080 和教学接收器 18081 由终端运行；IDEA 项目切换未生效，本次未验证 IDEA 内启动。


## 第二十八章：接收方 Inbox 与固定回执回放

独立项目位于 `after-sale-receiver/`：接收库 `after_sale_receiver` 保存 `rx_inbox` 与 `rx_after_sale_application`，不使用客服数据库或模型。来源身份通过真实 JWT 验证后固定映射；同一事件的正文一致才返回原 PERSISTED 回执。换 eventId 复用来源申请/operationId 返回 409，事务失败则两表一起回滚。

在仓库根目录先执行 `./scripts/prepare-receiver-local.sh`，再执行 `./scripts/start-after-sale-receiver.sh` 启动 18083。客服 IDEA 配置选 `CloudCustomerServiceInbox`，仍用 18080；接收子项目单独导入 IDEA 并运行 `AfterSaleReceiverApplication`。本机签发工具只写 `.local`，令牌 8 小时有效；真实环境使用实际服务身份提供方。

页面沿用 <http://127.0.0.1:18080/internal/draft-tasks/submission>，补充重复事件和原回执说明。第 27 章文件模拟器、历史回执与默认不开启投递的运行方式保留。新接收库不接管旧演示结果，不补发历史申请。

子项目须单独执行 `RUN_RECEIVER_TESTS=true ./mvnw -f after-sale-receiver/pom.xml package`，不能把根项目测试通过当成接收服务也验证完毕。完整运行和验证记录见[第二十八章说明](docs/chapters/28-inbox-receiver.md)。

本章已验证：主项目 544 项 + 接收项目 49 项，共 **593 项测试通过**（183 项真实数据库）；浏览器新建申请完成两库投递，再回放三次仍只有一份接收方申请。本次服务通过终端运行，IDEA 菜单切换未生效，未宣称已在 IDEA 内启动。


## 第二十九章：核查原结果，不重复提交

打开[同步核查台](http://127.0.0.1:18080/internal/outbox-reconciliation)，用已配置的 `support9001` 账户登录。此账户默认具有独立 `support:reconcile` 权限；普通客户和 `support9002` 无权查询租户事件。页面支持状态筛选、分页、原身份链、核查发现与应用结果、最近 50 次审计和累计指标。刷新只读，不自动再次核查。

接收服务新增 `/integration/after-sales/applications/lookup`，需要 `after-sale.reconcile`。运行 `java scripts/LocalServiceToken.java .` 刷新本机令牌，并重启客服以加载。保持既有 IDEA `CloudCustomerServiceInbox` 配置，端口仍为 18080。完整启动命令、请求合同与故障测试见[第二十九章说明](docs/chapters/29-result-reconciliation.md)。

原回执匹配才补记 DELIVERED；查询异常、未观察到结果、正文冲突都不重发。每个新鲜观察推进核查版本，晚到的旧结果只记 STALE。审计与状态修复同事务，进程中断留下 STARTED，重启不自动创建或核查。补记送达不代表审核通过或退款。

本章完整构建：主项目 583 项 + 接收服务 65 项，共 **648 项测试通过**，含 212 项真实数据库验证。双服务故障联调实测核查后仍只有一份远端申请，390 px 页面通过；当前两服务由终端运行，IDEA 项目切换未生效。
