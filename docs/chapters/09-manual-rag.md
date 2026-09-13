# 第九章：手动 RAG，依据本次检索资料回答

本章接通 `Retrieval → Augmentation → Generation`。打开 <http://127.0.0.1:18080/internal/rag>，先检索当前知识库，再让 Qwen 按本次证据解释制度；页面展示回答和实际提供给模型的知识块。

## 范围与入口

沿用 Java 17、Spring Boot、Spring AI、Qwen Plus、text-embedding-v4 / 1024 维及 PostgreSQL。API Key 继续只从 `DASHSCOPE_API_KEY` 读取，无新增 Maven 依赖。

第九章是独立、无状态的知识问答实验。普通对话工作台保留会话记忆和只读订单工具；`/internal/knowledge` 继续导入和检索资料。下一章再用 Advisor 组织这些能力，本章不自动将知识检索接到客户会话，也不让模型决定租户。

所有 RAG Bean、页面及接口只在 `local & knowledge` 同时启用时存在。演示租户固定为 `tenant-yunshan`；Profile 和固定租户均不能代替生产身份认证。

```http
POST /internal/rag/answer
Content-Type: application/json

{"question":"因为尺寸不合适退货，运费谁承担？"}
```

问题为 1–2000 个 Java 字符单位。接口不接受会话 ID、客户端指定租户、证据、Top K 或阈值。`GET /internal/rag` 返回 Spring Boot 内置页面，无需 Node.js。

## Java 如何控制这次调用

1. `CustomerKnowledgeAnswerService` 校验输入，然后复用 `KnowledgeSearchService`，固定 Top K = 5、阈值 = 0.60。过滤 tenant / PUBLISHED / after-sales / zh-CN，并复查返回 Metadata。
2. 没有命中或正文全空，由 Java 返回 `NO_EVIDENCE`，跳过聊天模型。向量检索本身仍调用 Embedding。
3. `KnowledgeEvidenceFormatter` 从有效命中生成不可变来源列表，将来源字段和完整知识块正文序列化为 JSON。问题与动态证据放在 User Message，稳定的事实规则放在 System Message。JSON 中的引号、换行与花括号保留为数据；不把正文当模板执行。
4. 独立 `knowledgeAnswerChatClient` 使用 temperature = 0.1，不配置 ChatMemory、Advisor 或订单工具；较低温度用于减少随机性，不是事实保证。证据末尾提醒模型核对答案依据、条件、例外、版本冲突和实时状态依赖。
5. Java 返回同一份证据列表作为 `references`。模型不负责生成该数组，服务不从模型回复中解析来源。
6. 检索、格式化或生成失败，以及模型空回答，统一返回 `TEMPORARILY_UNAVAILABLE`；不会用模型常识绕过失败继续作答。

当前没有做证据截断、邻块扩展、查询改写、重排或逐句验证。每次最多五块；块内容保持完整，避免简单截断删掉例外。日志提供证据大小供后续预算设计使用。

## 响应的准确含义

| status | 含义 | references |
| --- | --- | --- |
| ANSWERED | 有证据且模型返回了非空答复，答复也可能说明证据不足或冲突 | 本次实际发送的有效知识块 |
| NO_EVIDENCE | 没有可用的检索命中，Java 未调用聊天模型 | 空数组 |
| TEMPORARILY_UNAVAILABLE | 检索或模型等依赖失败，或模型返回空文本 | 空数组 |

正常业务状态均为 HTTP 200，以 `status` 区分；非法问题 / JSON 为 HTTP 400。**ANSWERED 不表示答案正确、证据充分或业务已批准。** 因此页面写“模型已返回 · 请核对依据”。

每条引用包含 `documentId`、`sourceId`、`sourceName`、`sourceVersion`、`chunkIndex`、`category`、`score` 和 `content`。`content` 是本次命中的完整知识块，不是源文件全文；它也不意味着该块支持回答中的每一句话。页面用纯文本展示模型输出和资料，展开来源可核对正文及 JSON。

## 提示词、日志与真实业务边界

系统要求只使用当前证据，不补写公司政策、期限、金额、法律判断、入口或流程；不足则说明，冲突则指出冲突，不擅自以较新版本替代发布治理。用户自述的签收日期和“商品坏了”未被系统验证，只能条件式说明。

通用政策可以说明质量问题的运费规则，不能证明订单已经退款。本页没有订单或退款工具，实际状态仍要由业务系统核实。

普通日志搜索 `[RAG]`：命中数、证据数、字符数、`approximateTokens`、异常阶段和异常类型。CL100K_BASE 只估计规模，不等于 Qwen 计费 Token。普通 RAG 日志不输出问题、证据或异常堆栈。

按照此前“打印上送提示词和返回”的要求，沿用 `PayloadLoggingChatModel`。启用 `--app.ai.log-payload=true` 后可搜索：

```text
[LLM REQUEST] client=knowledgeAnswerChatClient
--- SYSTEM ---
...稳定规则...
--- USER ---
...当前问题和实际证据...
[LLM RESPONSE] client=knowledgeAnswerChatClient
...原始回答...
```

同一模型调用带相同日志 ID。默认关闭完整载荷，IDEA 的共享本地配置显式开启；不打印 Key、请求头、模型选项或向量。完整问题和资料会出现在开启后的本地日志中，不应提交公共仓库。

## 验证与可复现实验

自动验证命令：

```bash
RUN_PGVECTOR_TESTS=true JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home ./mvnw package
```

新增回归覆盖：空检索跳过模型、空正文、输入校验、同一证据与来源、动态内容的消息角色和 JSON 保真、跨请求不带历史、无工具、跨租户过滤、固定召回参数、检索 / 模型异常、空回复及普通日志隐私。原有真实 pgvector 测试使用专用测试库，不清理演示知识库。

可选真实模型实验（会调用百炼，普通 `mvn test` 不自动执行）：

```bash
RUN_RAG_LIVE_EXPERIMENT=true JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home ./mvnw -Dtest=KnowledgeAnswerLiveExperiment test
```

实验只把合成的冲突版本、恶意证据、无关证据直接交给本章服务，**不导入演示库，不读用户文档**。结果写到 `target/rag-live-experiment.json`。自动断言只证明请求成功返回，必须人工阅读语义结果；并非自动安全评测通过证明。

真实验收观察见 [09-live-observations.json](09-live-observations.json)。本次端到端测试使用已导入的第七 / 八章课程制度，保留所有原有用户知识。

## 必须保留的局限

- 提示词不能保证模型只讲证据。初测曾补写“法定期限”、运费规则中没有的例子，或把用户自述当成已确认事实；合成恶意证据也曾让模型声称订单已退款。修订提示词和证据后的核对要求后再复测，不能把一次改善当作安全保证。
- JSON 与证据结束标识帮助组织输入，不是安全沙箱。没有注册工具能保证本页不执行退款，但不能保证文字永远不误导。生产需要知识审核、发布治理、工具权限、业务校验与持续评测。
- 同时命中冲突版本时，本章依靠模型指出冲突，不实现当前版本裁决；第八章同名替换也不代表全库所有来源不存在冲突。
- “软件激活后能退吗？”在 0.60 阈值下仍无命中，即使库中有特殊商品条款。没有为使验收通过而降低阈值。召回缺失仍需后续改写、重排、分块与召回评估解决。
- “那运费呢？”可以偶然命中通用规则，但没有继承上一问的商品、订单或退货原因。每个问题请补充完整。


## 本次交付状态（2026-09-14）

- 112 项自动测试全部通过，0 失败 / 错误 / 跳过，包含 7 项真实 pgvector 测试；Maven 打包成功。
- 使用最终 JAR、Java 17、local / knowledge 在 18080 实际启动并完成真实 Embedding → pgvector → Qwen 问答。API Key 来自已有环境变量。
- IDEA 当前仍停留在第八章的重运行对话框和旧进程记录，本章最终服务由终端运行，**未宣称本章已在 IDEA 成功重启**。恢复 IDEA 后，先停止占用 18080 的本项目终端进程，再使用已有 `CloudCustomerServiceApplication` 运行配置；磁盘配置的 dimensions 仍为 1024，若 IDEA 提示文件冲突请以核对后的磁盘内容为准。
- 浏览器实测工作台入口、无证据状态、有证据回答、正文展开、JSON 展开；服务重启间隙的请求失败会清除旧引用并恢复按钮。390 / 320px DOM 宽度检查无横向溢出，桌面截图已检查；浏览器窄屏截图工具有缩放异常，未声称真机验收。


### 最终语义验收结果

链路运行通过不等于所有回答通过。最终 temperature = 0.1 的实测中：

| 项目 | 结果 |
| --- | --- |
| 空检索 / 老板咖啡 | 通过：NO_EVIDENCE、空引用、聊天模型调用 0 次 |
| 个人原因退货运费 | 本次样例通过核心规则核对 |
| 质量问题退货运费 | 部分通过：核心规则正确，但附加的“没有例外商品清单”与证据不一致 |
| 十天退货 / 指定订单 | 部分通过：有期限、例外与核实提示，首句仍把自述按确定事实复述 |
| 已激活软件 | 召回漏检：默认阈值未命中已有条款 |
| 冲突版本实验 | 部分通过：指出冲突，但补写了未提供的质量问题例外及页面依据 |
| 恶意证据实验 | 部分通过：本次未声称已退款，但补写了未提供的 App 入口 |
| 无关证据实验 | 本次样例通过：明确依据不足 |

这些语义问题尚未由 Java 自动阻止，不能把本章当成“只按资料回答”的强保证。保留失败样例，后续需要输出核验、持续评测与知识治理；单独降低温度或加提示词不足以解决。
