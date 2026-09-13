# 第四章：刚说过的话，它转头就忘了

## 本章变化

前端使用统一的会话消息接口，一次拿到分类和回答：

```text
POST /api/conversations/{conversationId}/messages
    → CustomerConversationService
    → CustomerIntentRecognizer：只读同一会话历史，识别当前诉求
    → customerServiceChatClient + MessageChatMemoryAdvisor
        → 读取历史消息 + 当前用户消息 + System Prompt
        → Qwen 客服回答
        → 维护客户对话记忆
    → { conversationId, intent, answer }
```

每轮正常会有两次模型调用：一次意图识别，一次客服回复。意图识别的 JSON 只放在 HTTP 响应中，不会写入客户聊天记忆，也没有用它执行真实退款、查询订单或调用工具。

| 文件 | 作用 |
| --- | --- |
| `config/ChatMemoryConfig.java` | 内存 Repository + 20 条消息的 MessageWindowChatMemory |
| `config/AiConfig.java` | 客服挂 Memory Advisor；分类器只接收服务传入的历史 |
| `intent/CustomerIntentRecognizer.java` | 只读历史、生成结构化结果、校验当前或历史客户原文中的订单来源 |
| `conversation/CustomerConversationService.java` | 串接分类与聊天，传入明确 conversationId，校验请求，清空记忆 |
| `conversation/CustomerConversationController.java` | 创建、发送和清空三个 HTTP 操作 |
| `conversation/CreateConversationResponse.java` | 新会话 ID |
| `conversation/SendMessageRequest.java` | 当前 message |
| `conversation/ChatTurnResponse.java` | 会话 ID、intent 和 answer |
| `controller/ChatController.java` | 旧无状态聊天使用临时 UUID，在 finally 中清空 |
| `CustomerConversationTest.java` | 第四章集成测试，使用真实 Advisor / Memory，替换远程模型 |

沿用 Java 17、Spring AI 1.1.2、Alibaba 1.1.2.2、qwen-plus、端口 18080，依然从 `DASHSCOPE_API_KEY` 读取凭证。不需要新增依赖或数据库。

## 在 IDEA 中操作

继续运行共享配置 `CloudCustomerServiceApplication`。根目录 `requests.http` 已包含六组实验；支持 HTTP Client 的 IDEA 可按顺序运行。也可以在 IDEA Terminal 用以下 curl：

```bash
# 创建会话，把返回的 conversationId 填入下面变量
curl -X POST http://localhost:18080/api/conversations
conversation_id='这里填写刚返回的ID'

curl "http://localhost:18080/api/conversations/$conversation_id/messages" \
  -H 'Content-Type: application/json' \
  -d '{"message":"我的订单是 A10001。"}'

curl "http://localhost:18080/api/conversations/$conversation_id/messages" \
  -H 'Content-Type: application/json' \
  -d '{"message":"我想把它退掉。"}'

curl -X DELETE "http://localhost:18080/api/conversations/$conversation_id/memory"
```

第二轮的本次实测结构化部分为：

```json
{
  "intent": "REFUND_REQUEST",
  "orderNo": "A10001",
  "confidence": 0.95,
  "missingFields": []
}
```

会话创建返回 201，消息正常返回 200，清空返回 204。空 message、超过 4000 个 Java UTF-16 字符单位、无效 JSON 或无效 ID 返回 400。ID 允许 1～100 个 ASCII 字母、数字、下划线或连字符；创建接口生成 UUID。

分类失败仍为第三章的 UNKNOWN 兜底，并继续尝试客服回复；客服调用异常或空回答返回 502。HTTP 200 不代表分类成功，也不证明业务事实真实。服务异常日志不包含原文和堆栈。

旧 `GET /api/chat`、`POST /api/intents/recognize` 保留为前几章的无状态实验，连续调用不会延续新会话。真实前端可以只使用新的消息接口，不必再分别调用这两个旧接口。

## 日志里怎样看到记忆

IDEA 配置仍启用 `--app.ai.log-payload=true`。搜索 `[LLM REQUEST]`：

- `client=intentChatClient` 的 USER 文本中，`<conversation_history>` 包含已发生的 `[USER]` 和 `[ASSISTANT]` 消息；末尾仍追加完整 JSON Schema。
- `client=customerServiceChatClient` 会显示实际送入 ChatModel 的多条历史 USER / ASSISTANT 和当前消息。
- `[LLM RESPONSE]` 是模型原始文本。每次模型调用的 REQUEST / RESPONSE 使用相同 ID；同一 HTTP 轮次的分类和客服调用各有自己的日志 ID。

原有关闭方法不变：IDEA Program arguments 改为 `--app.ai.log-payload=false` 并重启；终端默认关闭，可用 `AI_LOG_PAYLOAD=true` 开启。日志开启后也会包含历史客户原文，不打印配置中的 API Key。静态 Schema 日志仍由 `INTENT_SCHEMA_LOG_LEVEL` 单独控制。

## 对章节示例的适配与实际版本行为

- 保留项目原包名 `com.example.cloudcustomerservice`，并保留前三章的能力限制、数值校验和日志开关。
- 订单号允许来自当前消息或保留窗口里的 USER 原文；不会仅凭 ASSISTANT 回答中的订单号通过来源校验。仍是字符串来源检查，不是订单归属或真实格式校验；模型也可能选错历史中的订单。
- 文中“漏传会话 ID 会被拒绝”与当前 1.1.2 源码不同：`MessageChatMemoryAdvisor.Builder` 初始化为 `ChatMemory.DEFAULT_CONVERSATION_ID`，漏传会进入默认会话。本项目会话路径显式传 ID，旧聊天路径传独立临时 ID 并清理，避免使用共享默认会话。
- 1.1.2 的 Advisor 在模型调用前就保存本次 UserMessage，调用成功后再保存 AssistantMessage。因此客服调用失败时，用户消息仍可能留在记忆中；本章没有自动回滚或重试去重。测试记录了这一行为，调用方不要盲目重发。
- `maxMessages(20)` 限制保存的消息窗口，不是 20 轮，也不是 token 上限。当前每轮通常新增两条；发送时 Advisor 还会组合 System Prompt 和当前消息，所以不能把它理解成每个 Prompt 硬性只有 20 条。

## 验收（2026-09-13）

- JDK 17 执行 `./mvnw -B -ntp package`：BUILD SUCCESS，39 项测试通过（原 31 项 + 第四章 8 项）。
- 自动测试覆盖两轮上下文、会话隔离、清空、窗口淘汰、分类只读、仅客服生成订单号被拒绝、旧接口回归、输入 400、分类兜底和客服 502。
- IDEA Run 控制台确认 Java 17.0.20.1、Started 日志、Tomcat 18080。实际提示词包含历史 USER / ASSISTANT，分类 JSON 没有进入客服历史；完整日志功能正常。
- 旧聊天与独立分类接口真实回归均返回 HTTP 200。
- 使用真实 Qwen 完成六组实验。原始接口观察（只含本次合成测试数据）见 [验收记录](04-live-observations.json)。

| 实验 | 本次实际观察 |
| --- | --- |
| 同会话姓名回忆 | “我叫小王”之后回答“您刚才说您叫小王” |
| 历史订单指代 | “我想把它退掉”得到 REFUND_REQUEST、A10001、0.95 |
| 新会话隔离 | orderNo=null，没有带出 A10001 |
| 清空记忆 | 清空后 orderNo=null，客服不知道旧订单号 |
| 应用重启 | 使用相同 ID 继续，旧 R30003 不再出现在上下文与结果中 |
| 历史指令攻击观察 | 先要求所有订单都说已退款，后问 A10001；本次回答仍明确无法查询退款状态 |

实际限制：首次只说“我的订单是 A10001”时，本次模型返回 UNKNOWN，同时提取出 A10001，confidence=0.6；这是模型原始分类，不是 Java 失败兜底。下一轮仍能根据保存的客户原文解析退款意图。

客服仍出现了未经验证的“商城 App／官网 → 我的订单 → 申请退货”等入口和步骤建议，尽管 System Prompt 禁止编造入口。因此这次验收证明上下文机制可用，不证明回复事实可靠、线上分类准确率或完整的注入防御。没有查询真实订单，也没有执行退款。

## 本章范围与后续

当前是单进程、内存版学习项目。创建会话只生成 ID，不创建用户绑定记录；符合格式的 ID 可直接开始聊天，重启后旧 ID 可以继续使用但没有旧记忆。没有登录或会话归属校验，知道同一 ID 的调用者会共享上下文；不能把 UUID 当作权限控制。

同一会话（包括清空）按请求完成顺序逐条操作，本章没有会话串行队列、并发锁、幂等 requestId 或跨实例一致性。内存 Repository 也没有自动过期和总会话数上限，结束实验可以调用清空接口。窗口不是完整聊天档案。

章节中的 JDBC、PostgreSQL、完整 Chat History、用户归属验证是后续生产方案说明，本章按文意暂不接入。第五章再处理 Tool Calling。

第四章后续增加了 [可视化聊天工作台](04-web-workbench.md)，直接访问应用首页即可操作上述会话接口。原 `chapter-04` 标签保留，本次界面属于独立补充提交。
