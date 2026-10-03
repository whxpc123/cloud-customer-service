# 第二十七章：本地提交成功了，远端系统为什么还没收到？

本章在第 26 章的本地受理事务中保存固定投递意图，再由独立后台流程领取、发送、核对回执。页面继续使用 <http://127.0.0.1:18080/internal/draft-tasks/submission>，保留本地创建回执，增加独立的外部同步卡片及当前客户的投递概况。

## 授权及历史兼容

准备操作增加 `deliveryProfile`：

- `LOCAL_ONLY`：仅在本系统创建；旧客户端省略该字段等价于此值。
- `AFTER_SALE_V1`：创建并按固定合同同步至售后平台 V1。它不是用户提供的 URL。

页面在批准前展示接收方及字段范围。范围和 taskId、draftVersion、接待版本一起不可改写；重复准备同一版必须使用相同范围，否则 409。V9 迁移让所有旧操作保持 LOCAL_ONLY，**不补发历史申请、不扩大历史批准**。同一任务已创建申请后，也不能换号再创建一份。

批准后允许发送：schemaVersion、eventId、eventType、applicationId、operationId、tenantId、orderNo、draftVersion、userStatement（问题描述和申请诉求）、occurredAt。没有聊天历史、完整订单、内部风控或 Agent State。tenantId 是业务关联字段，接收方仍须验证服务凭证代表的租户。

## 代码与事务

| 入口 | 职责 |
| --- | --- |
| `submission/IdempotentSubmissionService` | 固定范围；INSERT 申请后调用 OutboxWriter，再保存 SUCCEEDED 和 CLOSED |
| `outbox/OutboxWriter` | Spring 代理 `MANDATORY`，从不可变申请快照生成并插入事件；异常向外传播 |
| `outbox/OutboxStore` | `REQUIRES_NEW` 短事务，领取、确认、退避、最后租约回收 |
| `outbox/RemoteAfterSaleClient` | 固定 HTTP 接口、稳定幂等 key、严格回执验证 |
| `outbox/OutboxRelay` | 领取事务提交后发送，另一个事务保存结果；不使用模型 |
| `outbox/OutboxQueryService` | 当前客户的只读状态与统计，不返回事件正文、租约令牌和服务凭证 |

```text
明确批准固定正文与范围
  → 同一本地事务：申请 + Outbox + 操作成功 + 任务关闭
  → 返回本地创建回执

后台：短事务领取 → 无事务 HTTP → 短事务保存远端回执
```

新 `V9__transactional_outbox.sql` 保留 V1～V8 原文件和 V8 的全部审批/状态约束。Outbox 有稳定 eventId、目的地唯一约束、与申请身份的组合外键、不可改写正文触发器。只允许同一申请的一个创建事件；不实现修改/取消事件的有序投递。

## 租约和失败语义

一次只领一条，`FOR UPDATE SKIP LOCKED` 避免工作者争用。领取生成 leaseToken，租约 30 秒；过期可重新领取，eventId 与正文不变。回写要求当前 SENDING + 同一 leaseToken，旧工作者不能覆盖新状态。租约不能阻止旧 HTTP 到达远端，因此接收方必须幂等。

| 状态 | 表示 |
| --- | --- |
| PENDING | 等待首次投递或退避后重试 |
| SENDING | 当前工作者处理，外部结果尚未确认 |
| DELIVERED | 匹配的持久化回执已在本地保存 |
| REVIEW | 自动处理已停止，需要核查；不能断言远端未创建 |

每次领取增加 attemptCount，未真正发 HTTP 也可能计一次。暂时故障指数退避并抖动，首次约 2～5 秒，最高 300 秒，最多 8 次；第 8 次领取后进程崩溃，租约过期也转 REVIEW。错误码只保存受控大写代码，不保存错误响应正文或凭证。

HTTP 408/429/5xx、网络未确认可重试；其他状态（包括 202/重定向/401）、格式错误、超大响应或编号不匹配进入 REVIEW。连接超时 2 秒，读取超时 8 秒，禁止重定向，回执上限 64 KiB。200/201 还必须匹配 eventId、applicationId、非空 remoteApplicationId、`status=PERSISTED`。收到有效回执但本地保存失败时，不将其重新解释为远端失败；保留租约以原事件恢复。

本章没有公开“重置全部失败”“删除换号”接口。核查应使用原 eventId；生产人工恢复还需要对方查询能力、权限、审计与状态检查。

## 启动方式

普通 IDEA 配置及 `local,knowledge` 保持不变：可准备/批准同步操作并落库，但投递器关闭，页面显示等待投递。重启不会丢失任务。

连接真实平台前，须先确认该平台提供本章幂等及持久化回执合同，再通过服务端环境配置：

```text
REMOTE_AFTER_SALE_ENDPOINT=https://固定售后平台/固定接口
REMOTE_AFTER_SALE_TOKEN=服务端凭证（不要提交 Git）
```

然后增加 `outbox-delivery` profile。支持 HTTPS；明文 HTTP 仅允许 127.0.0.1 用于本机验收。不允许 userInfo/query/fragment；端点不能来自用户输入或模型。一个 AFTER_SALE_V1 必须一直对应同一业务接收系统，不能通过改配置把旧授权发给另一个接收人。

### 本机可复现演示

本章没有接入真实售后平台。附带 Java 17 单文件教学接收器，绑定 18081，只认演示租户，记录落在被忽略的 `.local/outbox-demo/`。JSON 内容相同返回同一 DEMO 编号，内容不同 409。它用单进程文件锁、原子重命名和同步落盘验证重发；不是生产 Inbox、集群接收端或退款系统。

在项目根目录两个终端运行（先配置原项目的 DASHSCOPE_API_KEY）：

```bash
# 终端一：每个新事件先落盘，然后故意断开首次回执；重发返回原回执。
export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home
./scripts/start-outbox-demo.sh --drop-first-ack

# 终端二：沿用 18080；先停止该端口原项目进程，避免同时争用。
export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home
java '-DsocksNonProxyHosts=localhost|127.*|[::1]' \
  -jar target/cloud-customer-service-0.0.1-SNAPSHOT.jar \
  --spring.profiles.active=local,knowledge,outbox-delivery \
  --server.port=18080 \
  --spring.config.additional-location=file:.local/outbox-demo.properties
```

接收器首次启动生成只限本机演示的随机令牌配置（权限 600），不打印令牌。它只用于服务间调用，和模型的 DASHSCOPE_API_KEY 无关。IDEA 可选择新增共享运行配置 `CloudCustomerServiceOutboxDemo`（先启动教学接收器）；恢复 `local,knowledge` 即关闭后台发送，不删除待发记录。

## 接口

沿用当前客户 Session 和 CSRF：

```http
POST /internal/draft-tasks/submission/operations
Content-Type: application/json
X-CSRF-TOKEN: 当前会话令牌

{"taskId":"已确认任务UUID","draftVersion":1,"deliveryProfile":"AFTER_SALE_V1"}
```

随后仍是独立的 `/operations/{id}/decision` 与 `/execute`，确认内容不自动等于批准执行。

```text
GET /internal/draft-tasks/submission/operations/{id}/delivery
GET /internal/draft-tasks/submission/delivery-summary
```

两者都只读。按原操作关联到任务及申请的 tenantId/userId 校验；其他客户 404，客服角色 403，未登录 401。状态读取不领取事件、不触发图、不调用模型。状态还有 `NOT_CREATED`、`LOCAL_ONLY` 和异常 `MISSING_EVENT`，不会把缺记录伪装成送达。

统计限定当前客户全部历史事件：等待/处理中/已确认/待核查数量、最老 PENDING 等待秒数、领取次数分布、已确认的平均创建到送达耗时和样本数。这是基础可见性，没有全局告警、值班或人工恢复系统。

## 验证

2026-10-04 执行：

```bash
RUN_PGVECTOR_TESTS=true RUN_ACCEPTANCE_TESTS=true ./mvnw package
node --check src/main/resources/static/submission.js
```

全量 **544 项测试通过，0 失败、0 错误、0 跳过**，其中 **156 项真实 PostgreSQL 验证**。本章新增 42 项：29 项 HTTP 合同/客户端与回执保存失败分类、12 项真实数据库及 HTTP 集成、1 项三个独立 JVM 的恢复测试。最终前端调整后重新打包并实际启动。测试不调用真实百炼，HTTP 只访问本机。

关键证据：

- Outbox INSERT 失败：申请为 0，批准仍 APPROVED，任务版本未改变。事件写入之后关闭任务失败：申请和事件均为 0。
- 相同操作重放：申请和同目的地事件各只有一条；未批准不能执行，旧操作不能换同步范围。
- 并发领取与锁跳过：同一未过期事件只被一人领取；租约过期重领保持正文/编号，旧 token 的成功与失败写回均返回 false。
- 8 次暂时失败转 REVIEW；最后一次租约过期也被回收；错误/超大/错号回执不标记成功。
- 接收方数据库真实提交后丢第一次回执：两次 HTTP 得到同一远端结果，无重复业务行；实际发送期间没有覆盖 HTTP 的 Spring 数据库事务。
- 独立 JVM：第一进程关闭投递器创建 PENDING 后退出；第二进程启用投递器、远端落库而未返回回执时强制退出；第三进程按过期租约恢复并 DELIVERED。申请、事件、接收方结果各一条，投递没有新增模型调用。报告：`target/chapter-27-process-restart.json`（不提交）。测试以数据库时钟条件推进租约到期，不依赖固定睡眠。
- HTTP 读取验证当前客户归属，跨客户/租户不可读；统计不会领取或发送。未启用 local/knowledge 时页面与接口仍不可访问。

浏览器使用新建本机演示任务，不改旧申请：

| 事实 | 实测 |
| --- | --- |
| 新任务 | `f2997054-7caa-4121-8c80-4dd2b1b1ddf9`，草稿 V1 |
| 操作 | `da4df083-ec19-4927-b288-72e2a0026726`，明确批准 AFTER_SALE_V1 |
| 本地申请 | `d311c467-b950-4554-8ce2-967256355bde`，待审核 |
| 稳定事件 | `dc1b62ef-a964-4121-b11c-5bdc5376ec50` |
| 远端教学回执 | `DEMO-fba60263-a75b-4eea-8cd6-31d644d510c0` |
| 停用投递器 | PENDING，领取 0 次，刷新不发送 |
| 重启启用 | 首次落盘后故意断开，第二次返回原结果；最终 DELIVERED，领取 2 次 |
| 重复执行原操作 | 仅 lookup_receipt → receipt，本地编号/时间不变，事件仍一条 |
| 旧第26章操作 | LOCAL_ONLY，eventId 为 null，迁移及重启均未补发 |

界面通过桌面截图检查；390 px DOM 无横向溢出，动态数据以 textContent 展示，控制台无错误。截图在 `.local/chapter-27-final.png` 和 `.local/chapter-27-receipts.png`（不提交）。

当前主程序在 **18080** 由终端运行，教学接收器在 **18081**。IDEA 当前仍停留在另一个项目，自动切换项目未生效；已提供共享运行配置和步骤，**本次未验证 IDEA 内启动**。这是本机模拟平台验收，不是外部公司的真实售后联调。

## 边界与参考

至少一次投递会出现重复 HTTP；只有接收方真正去重，才能避免重复业务结果。无限可用、严格顺序、生产 Inbox、多目的地、熔断、Retry-After 合同、远端查询及人工恢复审批均未在本章承诺。本地受理、外部持久化、业务审核、退款是四件不同的事。

核对依据：[Spring 事务传播](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/tx-propagation.html)、[PostgreSQL SELECT / SKIP LOCKED](https://www.postgresql.org/docs/17/sql-select.html)、[Spring RestClient](https://docs.spring.io/spring-framework/reference/integration/rest-clients.html)。项目实际使用 Boot 3.5.8 / Spring AI 1.1.2 / Spring AI Alibaba 1.1.2.2，编译和测试依据当前锁定依赖，没有为本章升级。
