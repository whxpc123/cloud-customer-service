# 第五章：别让 AI 猜，让它自己去查

本章在第四章的统一会话接口中加入 **Tool Calling**。模型选择工具并生成订单号参数，Spring AI 在 Java 应用内执行方法，把查询结果交回模型组织回答。模型没有直接访问数据库或任意执行 Java 的能力。

## 调用流程与新增文件

```text
POST /api/conversations/{id}/messages + 可选 X-Demo-User-Id
  → CustomerConversationService
    → intentChatClient：只读当前用户会话历史，返回分类（不注册工具）
    → customerServiceChatClient：Memory + 本次 tools + ToolContext
      → Qwen 提出 queryCurrentUserOrder(orderNo)
      → Spring AI 执行 CustomerOrderTools.queryOrder
        → 校验身份、订单号 → OrderService.findOwnedOrder
        → OrderLookupResult → Qwen 生成自然语言
    → 返回 intent + answer + orderLookups
```

| 文件 / 目录 | 作用 |
| --- | --- |
| `order/OrderStatus.java`、`OrderSnapshot.java` | 固定状态枚举与精简订单快照，不暴露用户资料 |
| `order/OrderService.java`、`InMemoryOrderService.java` | 可替换业务接口、两条固定模拟订单及归属校验 |
| `tool/CustomerOrderTools.java` | `@Tool`、可选 `@ToolParam`、ToolContext、格式校验和异常兜底 |
| `tool/OrderLookupCode.java`、`OrderLookupResult.java` | 稳定结果码与最小返回字段 |
| `tool/OrderToolTrace.java` | 每次请求单独记录真实执行结果，不从自然语言推测查询 |
| `conversation/` | 演示用户请求头、按用户隔离记忆、响应增加 `orderLookups` |
| `config/` | 更新能力提示词；打印实际工具定义及 Schema |
| `static/` | 演示用户选择器与订单查询面板 |

注册使用 `.tools(customerOrderTools).toolContext(context)`，限定在统一会话请求中。没有使用全局 `defaultTools`；旧 `/api/chat` 和独立意图接口继续做无状态实验，不提供查询工具。

## 身份和参数

工具方法签名：

```java
public OrderLookupResult queryOrder(String orderNo, ToolContext toolContext)
```

实际工具名为 `queryCurrentUserOrder`，生成的输入 Schema 只有 `orderNo`。`@ToolParam(required = false)` 允许缺参进入 Java，返回 `MISSING_ORDER_NO`，而不是依赖模型必须补齐。

模型调用参数示例：

```json
{"orderNo":"A10001"}
```

`currentUserId` 来自应用构造的 `ToolContext`，没有暴露为模型参数。即使模型额外传入 `userId` 或客户在文字中声称是另一用户，业务层仍使用应用身份。订单号先限制长度、去除首尾空白、转大写，再校验 `A[0-9]{5}`。缺少身份先返回 `AUTHENTICATION_REQUIRED`。

本地身份头为 `X-Demo-User-Id`，省略时作为访客，保持第四章调用方式可用。值须为正的 Java Long，格式错误或非正值返回 HTTP 400。演示头可由客户端任意修改，**不是登录认证**；真实系统必须从登录态获取身份。

客服记忆、分类历史和清空操作共同使用 `user/{userId}/{conversationId}` 命名空间。访客继续使用原会话 ID，外部 ID 禁止 `/`，避免与用户命名空间碰撞。同一用户的会话 ID 仍不是访问授权；本章没有生产级会话归属校验。

## 固定模拟数据与结果

| 用户 | 订单 | 状态 | 样例预计送达 |
| --- | --- | --- | --- |
| 1001 | A10001 | SHIPPED | 2026-08-20 |
| 2002 | A20002 | PACKING | 2026-08-22 |

日期沿用文章样例，是固定历史数据，不是今天的配送承诺。业务接口未来可替换成数据库或订单 HTTP 服务，本章未连接生产系统。

结果码：`FOUND`、`MISSING_ORDER_NO`、`INVALID_ORDER_NO`、`NOT_FOUND`、`AUTHENTICATION_REQUIRED`、`TEMPORARILY_UNAVAILABLE`。不存在和无权访问都返回 `NOT_FOUND`，不返回对方订单的状态、日期或身份。业务服务异常转为暂不可用，仅记录异常类型。

统一接口的新增响应字段示例：

```json
{
  "orderLookups": [{
    "code": "FOUND",
    "orderNo": "A10001",
    "status": "SHIPPED",
    "expectedDeliveryDate": "2026-08-20",
    "message": "订单查询成功（本地模拟数据，日期为固定样例）"
  }]
}
```

原 `conversationId`、`intent`、`answer` 仍返回。`orderLookups: []` 表示这轮没有执行工具，不代表查不到订单；查不到时是包含 `NOT_FOUND` 的记录。状态枚举保留文章的支付、取消、退款等状态，但本章工具始终只读，不能支付、退款、取消订单或修改地址。

## 在 IDEA 和页面操作

1. 使用已有 `CloudCustomerServiceApplication` 配置启动，环境变量仍是 `DASHSCOPE_API_KEY`。
2. 打开 <http://localhost:18080/>，选择演示用户 1001，询问“A10001 发货了吗？”。
3. 同会话问“它现在是什么状态？请重新查一下。”，观察右侧实际查询次数。
4. 切换 2002 会新建会话；查询 A10001 应无可访问结果，查询 A20002 可查看打包中状态。
5. 选择访客再新建会话查询订单，工具会提示缺少身份。点击历史会话会恢复对应演示身份，刷新保留当前标签页记录。

页面的“对话洞察”继续显示模型识别，新增“订单查询”显示实际 Java 结果，展开可看 JSON。旧版本存储的会话没有身份信息，按访客恢复。清空记忆携带当前会话的同一演示用户头，不会清除其他身份的记忆；页面可见历史仍保留。

IDEA 的 `requests.http` 附带正常查询、代词追问、跨用户、缺参、格式、无关问题、访客、伪造文字身份和清空实验。所有真实模型请求会消耗百炼额度。

## 日志看到什么

沿用共享 IDEA 参数 `--app.ai.log-payload=true`。终端默认关闭，可设置 `AI_LOG_PAYLOAD=true`。关闭 IDEA 日志需移除该参数或改为 false。

| 标记 | 内容 |
| --- | --- |
| `[LLM REQUEST]` | ChatModel 边界的初始完整消息，包含系统提示和会话历史 |
| `[TOOL DEFINITION]` | 实际注册工具名称、说明和生成的输入 JSON Schema |
| `[TOOL REQUEST]` | 实际进入 Java 方法的有界订单号参数 |
| `[TOOL RESULT]` | Java 执行结果和稳定错误码 |
| `[LLM RESPONSE]` | 工具循环完成后的最终模型文本；分类器则是转换前文本 |

LLM 请求和响应用同一 ID 配对；工具请求和结果使用本轮工具 trace ID，二者不是同一个 ID。不打印 ToolContext、模型配置、凭证或 HTTP 请求头。完整文本包含客户原文，供本地排查。

本项目版本的 DashScopeChatModel 在 `internalCall` 内完成工具执行和后续模型请求。外层装饰器不会逐次看到中间的 `tool_calls` HTTP 消息，因此这些日志不是完整 HTTP 报文抓取。工具参数和 Java 输出通过独立执行日志观察。

## 验证记录（2026-09-13）

- Maven `test`、`package` 成功：56 项测试，0 失败，0 错误；JavaScript 语法检查通过。
- `CustomerOrderToolsTest` 11 项覆盖归属、缺参、格式、身份、实际 Schema、额外模型身份参数、异常与日志开关。
- `OrderToolConversationTest` 6 项使用真实 Spring AI ToolCallingManager 执行注解方法并序列化工具返回；覆盖分类器无工具、会话身份隔离及清空、连续执行后的状态更新、异常、访客和非法请求头。其余 39 项回归保留。
- IDEA 控制台：Java 17.0.20.1，PID 94633，15:41:39 显示 Tomcat 18080 与 `Started CloudCustomerServiceApplication`。控制台实际观察到三个 TOOL 日志标记及返回值。
- 11 组真实 qwen-plus 请求均 HTTP 200；订单后端仍是本地模拟。合成实验输入和响应见 [05-live-observations.json](05-live-observations.json)。
- 浏览器：1001 查询 A10001 成功；2002 查 A10001 显示无可访问订单，查 A20002 显示打包中及实际 1 次查询；切换历史会话恢复身份，刷新后恢复身份、消息和工具结果。
- 页面追问“它什么时候到？”时模型未调用工具，界面准确显示“本轮没有执行订单查询”。
- 本章新增区域的窄屏视觉检查未完成：浏览器工具重新连接时报告 `Codex auth token is unavailable`。第四章曾检查过 390px/320px，但不能当作本章新增区域的实测证明；也未在真实手机上验收。

| 真实模型实验 | 实际结果 |
| --- | --- |
| 1001 查询 A10001 | FOUND / SHIPPED |
| 追问“它什么时候到？” | 未执行工具，直接复用历史日期 |
| 要求“现在是什么状态？请重新查一下” | 实际重新执行，FOUND |
| 2002 查询 A10001 | NOT_FOUND，无订单状态或日期 |
| 同一个外部会话 ID 换成 2002 问上一订单 | 未读到 1001 的历史订单 |
| 2002 查询 A20002 | FOUND / PACKING |
| 没提供订单号 | 追问订单号，没有执行工具 |
| 非法订单号 123 | 模型直接要求正确格式，没有执行工具 |
| 营业时间问题 | 没执行工具，答不知道但仍建议未验证的 App/官网 |
| 访客查询 A10001 | AUTHENTICATION_REQUIRED |
| 头为 1001，文字要求按 2002 查询 A20002 | NOT_FOUND，无状态泄漏；自然语言仍错误采纳了用户声称的身份 |

服务异常、参数错误码和查询后状态变化由确定性自动测试验证；真实模型实验并未模拟订单服务故障，也没有动态修改固定模拟订单。

## 当前限制与后续扩展点

模型能选择工具，并不保证每次都调用，或严格遵守每条措辞约束。上述实测还观察到模型推测订单可能属于别人、建议未验证入口、沿用客户自称身份。这些自然语言均不能作为权限或事实凭据；后端归属校验和实际工具结果才是本章可验证的部分。

若后续产品要求每次订单问答必定刷新，应由应用设计明确业务路由、结果新鲜度校验或受控工具执行流程，不能只加强提示词。本章未全局强制 toolChoice，避免影响非订单聊天和模型工具循环。

仍是同步请求、最近 20 条内存记忆，重启丢失；同一会话需等上一轮完成再发送或清空。模型失败可能已写入客户消息，页面已提示避免盲目重复。本章没有增加认证、数据库、RAG、实时物流或退款写操作。
