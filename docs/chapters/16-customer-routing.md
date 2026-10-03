# 第十六章：统一客服入口与受控流程路由

> 本文记录 `chapter-16` 的历史契约。当前主分支已由[第十七章](17-human-handoff.md)升级为密码登录、数据库会话与真实人工受理；旧 `/internal/routing/session` 和 `X-Routing-CSRF` 不再使用。要复现本文旧行为，请查看该章标签。

本章在前十五章之上增加分流层，同一个消息入口一次只选择一个处理器。旧章节页面保留用于学习对照；不引入 Graph 或多 Agent，不更换 Java 17、Spring AI 1.1.2 与 Alibaba 1.1.2.2 基线，不修改数据库迁移。

## 打开与启动

- 页面：<http://127.0.0.1:18080/internal/routing>。
- IDEA 打开本项目，使用已有 `CloudCustomerServiceApplication` 共享运行配置：`local,knowledge`、18080、继承 `DASHSCOPE_API_KEY`。先确保 PostgreSQL 已启动。
- 2026-10-03 实际验收由终端启动打包 JAR 完成。IDEA 当时打开另一个项目，自动切换未成功，因此本章没有把终端启动写成 IDEA 启动成功。
- 不需要前端构建；首页、知识管理台和售后页已增加统一入口链接。

先发送“你好”，再发送“我要查订单”，补充“A10001”，最后追问“它有质量问题，能退吗？”。右侧显示本轮实际路由和业务结果。点击历史答复下的按钮可以回看该轮依据。“仅诊断这句话”只判断当前消息，不读历史、不执行业务、不追加对话。

## 固定流程与程序边界

```text
回环 / 同源检查 → Session / CSRF → 会话归属
    → 读取服务器接管状态 → 规则 → 独立模型分类 → Java 校验
    → switch(Route) → 唯一业务处理器 → 保存实际用户消息与最终答复
```

| Route | 处理方式 | 本轮保留的结构化结果 |
| --- | --- | --- |
| SMALL_TALK | 完整问候、感谢、告别匹配固定话术 | decision、routing、answer |
| KNOWLEDGE | 复用第十至十四章 Modular RAG 链 | knowledge：references、真实查询、转换、扩展、重排轨迹 |
| ORDER_QUERY | Java 从客户原文取编号，调用第五章只读订单工具 | order.lookup：结果码、样例状态与日期 |
| AFTER_SALE_PRECHECK | 复用第十五章真实工具调用与检查 | afterSale.assessments：状态、事实、政策版本、证据、refundExecuted |
| HUMAN_SERVICE | 明确告知人工通道未连接 | humanStatus=NOT_CONNECTED；没有排队或转接 |
| CLARIFY | 按 reasonCode 区分追问、多任务、格式错误和模型故障 | 未调用业务处理器 |
| OUT_OF_SCOPE | 固定商城服务范围说明 | 未调用业务处理器 |

规则只剥离句尾标点、匹配少量完整命令。“不要转人工，先说退货规则”“人工客服几点上班”“你好，订单发货了吗”都不会被关键词规则提前截走。

`Proposal` 是模型建议，`Decision` 才是 Java 处理后的路由。分类客户端无 Memory Advisor、无 RAG、无业务工具。只接收当前消息和服务器生成的近期上下文。使用 `BeanOutputConverter` 生成 Schema 与格式提示，在转换前校验 JSON 对象、字段数量、字符串/布尔类型，再校验枚举与缺失字段；不把字符串 `"false"` 强转成布尔。

```json
{"route":"ORDER_QUERY","ambiguous":false,"multipleIndependentTasks":false}
```

缺订单号不等于意图不明：`ORDER_QUERY` 由订单处理器追问；`AFTER_SALE_PRECHECK` 由售后处理器补充编号和原因。历史只能提供客户曾明确输入的编号，不能证明归属；订单仓库仍按服务器用户检查。多个可能的订单目标会澄清。

不同流程的独立任务返回 `MULTIPLE_INDEPENDENT_TASKS`，不自动舍弃其中一项。售后“结合政策看该订单能不能退”已经是组合流程；同一知识流程内多个政策问题仍可使用既有多查询能力。

| reasonCode | 实际展示 |
| --- | --- |
| AMBIGUOUS_INTENT | 请补充具体诉求 |
| MULTIPLE_INDEPENDENT_TASKS | 请先选择处理哪一项 |
| INVALID_MODEL_RESULT | 暂时无法可靠分流，未执行业务 |
| MODEL_UNAVAILABLE | 自动分流服务暂不可用，不要求客户“说清楚” |

处理器自身异常返回 `HANDLER_UNAVAILABLE`，不换另一条流程继续猜。模型只能选择固定枚举，不能指定类名、Bean、方法、URL、身份或工具参数。字段检查不能发现所有语义分类错误。

## 会话、记忆与授权

本入口只在 `local & knowledge` 注册，并强制回环地址、localhost Host、同源 Origin、Cookie Session 和 CSRF。固定演示租户 `tenant-yunshan`、用户 `1001` 从服务器建立，不接受正文或 `X-Demo-User-Id` 切换身份。外部 conversationId 必须在当前 Session 注册表中存在，然后才读取历史/调用路由。未知与其他 Session 的编号均返回相同 404。

这是本地教学隔离，并未接入真实登录。生产应接入认证、用户与租户所有权、权限管理和真实人工状态；不要将 profile 或演示账户当成生产权限体系。

统一入口将最终实际对话保存在 Session 内的最多十轮窗口。分类器只读最多 6000 字符的完整近期消息，不保存 JSON。选中知识或售后处理器时，将窗口快照放入服务器独立随机工作键；原 Advisor 仍可照常运行。本轮结束在 `finally` 清理工作键，外层只保存原始用户消息及最终实际答复一次。来源、工具中间消息、检索增强文本不进入统一对话窗口。响应不会暴露工作键。

因此订单查询后可以接着问售后；固定问候和澄清也有真实历史。会话对象锁串行化同会话发送与清空。重启、30 分钟 Session 闲置会失去上下文，不是完整聊天档案，也不支持多实例共享。每个 Session 最多十个会话；达到上限后返回 429，已有页面可用“清空”继续复用当前会话。刷新会新建会话，并不恢复旧页面记录。

服务器状态若为 HUMAN_WAITING / HUMAN_ACTIVE，则在路由前停止机器人分派；清空历史也不解除接管。当前无真实坐席或设置接管状态的公开 API，用户点击人工只返回未连接，不伪造排队，也不声称已转发消息。

## 代码入口

| 文件（`src/main/java/com/example/cloudcustomerservice/routing/`） | 职责 |
| --- | --- |
| CustomerRouter.java | 独立规则、建议校验与固定枚举 |
| RoutingConfiguration.java | 专用无状态分类器、Schema 和严格字段检查 |
| LocalRoutingController.java | 本地访问、Session、CSRF、会话归属与诊断接口 |
| RoutedCustomerService.java | 固定 switch 分派、保留证据契约、临时工作记忆 |
| RoutingConversation.java | 服务器会话、接管状态和有界真实对话 |
| RoutingOrderHandler.java | 客户编号提取与第五章只读工具适配 |
| RoutingStatistics.java | 进程级规则/模型/路由/故障计数和分类耗时 |

前端在 `resources/routing-lab/index.html`、`static/routing-lab.js` 和 `static/routing-lab.css`。动态文字使用 textContent，不能执行模型或来源中的 HTML。第三章 `CustomerIntentRouter` 仍用于旧实验；第十六章按业务流程分类，没有重复调用第三章识别器。

## API 与日志

所有路径以 `/internal/routing` 开头；Cookie 与令牌不要写入公共仓库。`requests.http` 包含自动保存 CSRF / conversationId 的 IDEA HTTP Client 示例。

| 方法与路径 | 用途 |
| --- | --- |
| GET `/session` | 建立本地教学 Session，返回 csrfToken |
| POST `/conversations` | 当前 Session 注册会话 |
| POST `/conversations/{id}/messages` | `{"message":"问题"}`，统一客服处理 |
| DELETE `/conversations/{id}/memory` | 清空当前有权访问的窗口 |
| POST `/decide` | `{"message":"问题"}`，单轮分类诊断 |
| GET `/metrics` | 本次进程启动以来统计，包含诊断请求 |

全部修改/诊断请求需 `X-Routing-CSRF`。统一响应含 requestId、conversationId、decision、routing、业务 status、answer，以及可选 knowledge/order/afterSale/humanStatus。诊断响应只有 decision 与 routing。

`routing.elapsedMs` 仅为路由阶段耗时；`classifierCalls` 指该轮分类器尝试次数，规则为 0，其余为 1。不包含下游模型调用或 SDK 网络重试。metrics 中包括各路由数量、规则命中、澄清/兜底、故障、人工请求和平均路由耗时，重启归零，不等于质量评分。

IDEA 配置已开启 `app.ai.log-payload`，可搜索 `routingClassifier` 查看上送历史/问题/Schema/格式要求及模型原始 JSON。默认配置仍关闭正文日志，日志文件和密钥不要提交 GitHub。分类结果的合法性不等于分类正确性，RAG 命中也不等于回答逐句有据。

## 验证记录（2026-10-03）

```bash
RUN_PGVECTOR_TESTS=true \
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home \
./mvnw package

node --check src/main/resources/static/routing-lab.js

# 显式执行会调用正在运行的应用和真实 Qwen，消耗分类额度；不执行业务或导入资料。
python3 scripts/check-routing.py
```

- **273 项测试全部通过，0 失败/错误/跳过**，含 21 项真实 PostgreSQL 测试；本章新增 39 项，扩展旧 profile 检查。
- 测试覆盖完整命令不调用模型、否定/引用/问候夹业务不被规则截走、缺字段/非法枚举/布尔类型、模型故障、唯一分派、跨流程历史、结果保留、工作记忆清理、人工接管绕过路由、Session/CSRF/跨站/身份头伪造。
- `routing-cases.json` 固定 15 条样本，真实 Qwen 本次 **15/15 匹配**：3 次规则、12 次分类模型尝试；分类耗时 578～1377 ms，均值约 821 ms。这是单次小样本功能验收，不是总体准确率或稳定性证明。
- 浏览器实测：“你好”0 次分类；“我要查订单”进入 ORDER_QUERY 并返回 MISSING_ORDER_NO；补充“A10001”得到 FOUND / SHIPPED；再问“它有质量问题，能退吗”进入售后，得到 NEED_QUALITY_VERIFICATION、2 块 refund-policy / 3.2 证据、refundExecuted=false。
- HTTP 实测“退货运费谁承担”及“那质量问题呢”均进入知识链并保留 6 块来源；A10002 售后返回 NOT_ACCESSIBLE，无事实、无证据；A10005 返回 NO_EVIDENCE，保留事实但没有适用版本证据；订单与开票多任务返回澄清，无业务结果。
- 页面检查：单轮诊断后原 8 条聊天消息不增加；清空、新会话、来源/JSON 查看可用。1280 与 390 宽度没有页面横向溢出，浏览器无控制台错误。

本章没有写入真实订单、退款或知识库。订单查询沿用第五章固定快照，售后沿用第十五章固定事实样例，两者是课程数据，不是同一个实时生产系统。重排仍取决于是否配置百炼业务空间地址；当前未配置时沿用已明确标记的降级。

## 后续范围

真实登录与坐席、持久化完整对话、人工排队/转接、多任务执行计划、分类器持续评测都尚未实现。本章仅完成机器人模式分流与现有只读流程接线。程序检查无法保证模型每次语义分类正确，也没有为知识回答新增逐句事实核验。
