# 第三章：AI 说了一大段话，Java 到底怎么读？

## 本章实现

在现有 `cloud-customer-service` 内增加独立意图识别链路：

```text
POST /api/intents/recognize
    → CustomerIntentController
    → CustomerIntentRecognizer
    → intentChatClient（temperature = 0.1）
    → Qwen
    → .entity(IntentRecognitionResult.class)
    → Java 字段校验
    → JSON 响应
```

`GET /api/chat` 继续使用第二章的客服 ChatClient；两个注入点均通过 `@Qualifier` 明确指定客户端，第二章的能力说明也保留。

## Java 文件

包名沿用当前项目的 `com.example.cloudcustomerservice`，没有改成文章示例的 `com.yunshan.customer`，保证原启动入口与包扫描一致。

| 文件 | 作用 |
| --- | --- |
| `config/AiConfig.java` | 定义客服与意图识别两个具名 ChatClient，分别克隆 Builder 构建 |
| `controller/ChatController.java` | 明确注入 customerServiceChatClient，仍返回自然语言 |
| `intent/CustomerIntent.java` | 稳定的七类业务枚举 |
| `intent/IntentRecognitionRequest.java` | 接收 message |
| `intent/IntentRecognitionResult.java` | Java record，包含结果字段及 UNKNOWN 兜底工厂 |
| `intent/CustomerIntentRecognizer.java` | 模板、低温度调用、entity 转换、结果验证和异常兜底 |
| `intent/CustomerIntentController.java` | POST 识别接口 |
| `intent/CustomerIntentRouter.java` | 文中的 switch 示例，仅返回流程说明，没有执行或接入实际业务流程 |

## 接口使用

IDEA 继续运行 `CloudCustomerServiceApplication`，Project SDK 为 `temurin-17`，凭证读取已有环境变量 `DASHSCOPE_API_KEY`。本机默认端口仍为 18080。

```bash
curl 'http://localhost:18080/api/intents/recognize' \
  -H 'Content-Type: application/json' \
  -d '{"message":"A10001 怎么三天没物流了？"}'
```

本次真实返回：

```json
{
  "intent": "LOGISTICS_QUERY",
  "orderNo": "A10001",
  "confidence": 0.95,
  "missingFields": []
}
```

也可在 IDEA 中打开根目录 `requests.http`，运行第三章各组请求。相比 `.content()` 返回 String，`.entity(...)` 使用目标 Java 类型的格式说明引导生成并转换为 record。它是 best effort，不代表模型输出永远满足结构或业务规则。

| 枚举 | 含义 |
| --- | --- |
| PRODUCT_CONSULTATION | 商品咨询 |
| ORDER_QUERY | 订单状态查询 |
| LOGISTICS_QUERY | 物流咨询 |
| REFUND_REQUEST | 退货或退款申请意图 |
| HUMAN_SERVICE | 人工客服诉求 |
| OTHER | 确定与商城客服无关 |
| UNKNOWN | 无法判断、输入不满足本章限制、模型异常或结果验证失败 |

## 对章节片段补充的处理

- `intent` 和 `confidence` 是反序列化必需字段；missingFields 为 null 时转换为空不可变列表。
- confidence 校验不仅检查 0 到 1，还拒绝 NaN / Infinity。模型自评 confidence 不是经过校准的正确率，不设置未经评测的自动业务执行阈值。
- 输入为空或超过 4000 个 Java UTF-16 字符单位时，在本地直接返回兜底，不调用模型。
- orderNo 必须非空、最长 64 个字符，且原样出现在当前输入中。这里只做基本来源校验，**不能证明它符合真实订单格式、确实存在或属于当前用户**。
- 当前只支持缺失字段 `orderNo`，其他字段名视为非法结果。订单、物流、退款意图是否缺失订单号，由 Java 根据提取结果计算。
- 补充 Prompt 约定：退款与人工同时出现时优先人工；多订单无法确定目标时选 UNKNOWN。这些语义规则仍依赖模型，不是确定性的权限或操作控制。
- 异常只记录消息长度及错误类型，不记录用户原文、异常 message 或堆栈。关闭 `BeanOutputConverter` 自带的原始输出错误日志，并通过实际转换失败测试检查没有原文泄漏。模型服务或网络栈的其他日志仍需生产环境单独审计。

## 异常与返回状态

空消息、缺少 message、超长消息、模型调用/转换异常及非法字段统一返回 HTTP 200：

```json
{"intent":"UNKNOWN","orderNo":null,"confidence":0.0,"missingFields":[]}
```

缺少或损坏 HTTP 请求体由 Spring MVC 返回 400。接口保留章节的四字段契约，未新增错误码，因此不能只凭 HTTP 200 判断模型识别成功，也无法单靠返回值精确区分“模型不确定”与“调用失败”。

## 验收（2026-09-12）

- JDK 17 执行 `./mvnw -B -ntp package`：BUILD SUCCESS，可执行 JAR 已更新。
- 27 项自动测试通过（原有聊天链路 3 项，意图识别 24 项），0 失败、0 错误。
- 自动测试使用真实 ChatClient 和 BeanOutputConverter，替换远程 ChatModel。覆盖角色隔离、温度、模板换行与花括号、每轮无历史、类型转换、非法枚举、空结果、缺失字段、非法置信度、无来源订单号、异常兜底及日志。
- IDEA 控制台确认第三章进程使用 Java 17.0.20.1、Tomcat 18080、Started 日志。应用保持在 IDEA 中运行。
- 16 组识别接口请求全部 HTTP 200，其中空输入与超长输入是本地兜底，其余调用真实 Qwen；额外执行 1 次聊天回归。原始记录见 [本次接口观察](03-live-observations.json)。

| 基础验收输入 | 本次实际意图 |
| --- | --- |
| 这个耳机支持蓝牙 5.4 吗？ | PRODUCT_CONSULTATION |
| 我的订单 A10001 支付成功了吗？ | ORDER_QUERY |
| A10001 怎么三天没物流了？ | LOGISTICS_QUERY |
| 买错了，我想退掉 | REFUND_REQUEST，orderNo=null，缺少 orderNo |
| 不想和机器人说，给我真人 | HUMAN_SERVICE |
| 帮我写一篇小说 | OTHER |
| 那个到底怎么弄？ | UNKNOWN |

边界观察：换行中的订单号正常提取；多个目标不明确的订单返回 UNKNOWN；退款加人工返回 HUMAN_SERVICE；单独代词返回 UNKNOWN；本次伪造字段指令返回 UNKNOWN。该样例不代表完整的提示词注入防御，也不能区分模型主动拒绝和转换/校验兜底。

连续请求“我的订单是 A10001。”→“我想把它退掉。”：第二次得到 REFUND_REQUEST、orderNo=null、missingFields=["orderNo"]。这体现了当前没有 Memory，不是需要在本章偷偷补上的功能。

聊天回归“你是谁？”仍回答云杉商城智能客服，没有误用结构化分类器。

七条基础样例只是本次小样本观察，不等于线上准确率。本章没有持久化聊天、建设运营统计后台、查询真实订单或执行退款；第四章等待用户提供 Chat Memory 与 conversationId 的具体内容。
