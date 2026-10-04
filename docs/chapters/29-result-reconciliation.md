# 第二十九章：系统显示“结果待核查”，为什么不能直接再提交一次？

本章围绕**原事件**查询接收方已经保存的结果。客服端口仍为 **18080**，独立接收服务仍为 **18083**。页面入口：[/internal/outbox-reconciliation](http://127.0.0.1:18080/internal/outbox-reconciliation)。

只有原事件、原正文、原申请与接收方固定回执都匹配，才把发送方 `REVIEW` 补记为 `DELIVERED`。查询没有结果或发生故障时，保留 `REVIEW`；不换编号、不重置投递次数、不自动重发、不新增申请。

## 从哪里读代码

| 位置 | 作用 |
| --- | --- |
| 接收端 `inbox/InboxLookupService.java` | 对权威库执行单条只读查询，检查原正文、Inbox、申请与回执 |
| 接收端 `inbox/InboxController.java` | 查询与创建分别路由，共用有界读取和严格 UTF-8 |
| 接收端 `inbox/ReceiverSecurityConfiguration.java` | 查询专用 scope，原 JWT 验证和来源映射继续生效 |
| 客服端 `outbox/RemoteAfterSaleClient.java` | 固定 `/lookup` 子路径、原 eventId、原正文、严格响应，不回退调用创建 |
| 客服端 `reconcile/ReconcileModel.java` | 证据分类、回执标识逐一核对、安全审计码 |
| 客服端 `reconcile/OutboxReconciliationService.java` | 当前权限及身份检查，两个短事务之间执行 HTTP |
| 客服端 `reconcile/OutboxReconciliationStore.java` | STARTED 留痕、版本校验、审计和修复原子提交 |
| 客服端 `reconcile/ReconciliationQueryService.java` | 当前租户分页列表、详情、最近 50 次审计与历史指标 |
| `src/main/resources/db/migration/V10__outbox_reconciliation.sql` | 核查版本、持久化审计、约束与完成记录保护 |
| `src/main/resources/reconciliation/index.html` / `static/reconciliation.*` | 核查台：身份链、观察发现、应用结果、审计记录 |

接收端 Java 根路径为 `after-sale-receiver/src/main/java/com/example/aftersalereceiver/`；客服端为 `src/main/java/com/example/cloudcustomerservice/`。

## 一次核查的事务边界

```text
核查员登录与 CSRF、support:reconcile、服务器租户身份
    ↓
事务 A：锁原事件 → 只允许 REVIEW → 写 STARTED → 提交
    ↓
无数据库事务：POST 固定远端 /lookup，携带原正文和原事件号
    ↓
逐项检查查询信封与创建回执
    ↓
事务 B：再次锁原事件 + 比较 reconcile_version
    ├─ 新鲜 PERSISTED：补记 DELIVERED、远端编号、确认时间
    ├─ 新鲜其他发现：仍为 REVIEW
    └─ 过期观察：只记录 STALE，不覆盖当前状态
    ↓
审计与可选修复整体提交
```

每条新鲜观察都会将 `reconcile_version` 加一，**包括 NOT_OBSERVED**。因此两个核查都从 v0 开始时，即便较旧的肯定结果后来到达，也不能覆盖已经记录的较新未知观察；需要再发起一次当前版本的查询。

两个本地事务都是 `REQUIRES_NEW`、3 秒超时，锁等待限制 2 秒。外层使用 `NEVER`，防止误把网络调用包进事务。第二事务写审计失败时，送达修复也回滚。已完成审计禁止 UPDATE；未完成 STARTED 留存，不在重启时自动查询或创建。

`delivered_at` 是**本地补记确认的时间**，不是推测出来的远端创建时间。原申请、操作、事件、正文及投递次数都保留。

## 接收端查询合同

```http
POST /integration/after-sales/applications/lookup
Authorization: Bearer <具备 after-sale.reconcile 的服务令牌>
Idempotency-Key: <原 eventId>
Content-Type: application/json

<原始 Outbox 正文>
```

POST 用来携带原正文核对；实现是只读 `REQUIRES_NEW + READ_COMMITTED`，3 秒超时，使用与接收处理相同的主库数据源。单条 SELECT 同时读取 Inbox 与关联申请；不调用 `receive()`，不锁住未提交 INSERT 等待结果，也不连接只读副本。

返回 HTTP 200，`Cache-Control: no-store`：

```json
{
  "eventId": "原事件UUID",
  "state": "PERSISTED",
  "receipt": {
    "eventId": "原事件UUID",
    "applicationId": "本地申请UUID",
    "remoteApplicationId": "接收方申请UUID",
    "status": "PERSISTED"
  }
}
```

| 接收端 state | 意义 | 客服端 finding |
| --- | --- | --- |
| PERSISTED | 正文一致，Inbox 为 PROCESSED，申请和回执全部匹配 | 再核对事件、申请、状态及远端编号后 PERSISTED |
| NOT_OBSERVED | 本次已提交快照没有发现原记录 | NOT_OBSERVED，不证明远端从未发生 |
| PAYLOAD_CONFLICT | 相同来源/租户/事件号的 JSONB 正文不同 | PAYLOAD_CONFLICT |
| INCONSISTENT | 记录不完整、关联申请不匹配或回执损坏 | RECEIVER_INCONSISTENT |

非 PERSISTED 状态的 `receipt` 必须为空。HTTP 401/403/404/5xx、网络中断、超大或无法解析的响应记为 QUERY_UNAVAILABLE；合法 JSON 但错号、缺字段、未知 state、矛盾回执记为 INVALID_RESPONSE。两者均不是 NOT_OBSERVED。

创建只需要 `after-sale.ingest`，查询只需要 `after-sale.reconcile`；只有创建 scope 不能查回执，只有查询 scope 不能创建。签名、issuer、audience、时效、subject、固定来源/租户映射不变。请求/响应上限均为 64 KiB，响应不跟随重定向；拒绝重复键、未知字段与尾随 JSON。日志和审计不保存 JWT 或远端错误正文。

## 客服核查台与权限

页面公开静态布局，事件数据必须登录并具有 `support:reconcile`。教学账户 `support9001` 默认具有该权限；`support9002` 仍只具有接待权限，客户也不能访问。可以在已有账户配置中设置 `handoff.accounts.support9001.reconcile-enabled=false` 撤销权限；变更后重启并重新登录。没有默认密码，密码继续来自 `.local/handoff-accounts.properties`。

| API | 作用 |
| --- | --- |
| GET `/api/support/outbox?status=REVIEW&page=1` | 当前租户分页，每页 25 项 |
| GET `/api/support/outbox/summary` | 待核查数、事件年龄、修复数、STARTED 与分类统计 |
| GET `/api/support/outbox/{eventId}` | 原身份链与最近 50 次核查记录 |
| POST `/api/support/outbox/{eventId}/reconcile`，正文 `{}` | 发起一次核查并保存观察；要求 CSRF |

服务端从 Session 取操作人、租户，不接受客户端传入正文、身份、远端编号或“强制成功”。跨租户和不存在的事件统一 404，非 REVIEW 核查返回 409。API 不返回原用户陈述、服务令牌或租约令牌。

页面将“核查发现”与“本次是否应用”分开展示，标出 STALE 和 STARTED。刷新、重新打开页面和查看详情仅执行本地 GET；没有收到 POST 确认时不自动重试。目录无物理删除、清空、强制送达或重新创建按钮。

历史成功的补记不重新检查旧草稿审批期限，也不要求已关闭任务重新开放；它确认的是原来获准执行的事实。当前核查身份和原事件范围仍逐次校验。

## 运行与验证

继续使用第 28 章双服务配置。已有配置只需刷新本机服务令牌，再重启双方加载本章代码：

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home
java scripts/LocalServiceToken.java .
# 首次搭建环境用 ./scripts/prepare-receiver-local.sh；已有配置不会清库。
./scripts/start-after-sale-receiver.sh
```

客服 IDEA 使用既有 `CloudCustomerServiceInbox` 配置，端口 18080，profiles 为 `local,knowledge,outbox-delivery`，加载 `.local/inbox-sender.properties`。命令行等效启动：

```bash
java '-DsocksNonProxyHosts=localhost|127.*|[::1]' \
  -jar target/cloud-customer-service-0.0.1-SNAPSHOT.jar \
  --spring.profiles.active=local,knowledge,outbox-delivery \
  --server.port=18080 \
  --spring.config.additional-location=file:.local/inbox-sender.properties
```

模型 Key 仍只读 `DASHSCOPE_API_KEY`。本章核查本身不调用大模型。本机 JWT 8 小时过期，签发脚本现在同时授予 ingest 与 reconcile；生成后须重启客服加载新令牌。

```bash
RUN_PGVECTOR_TESTS=true RUN_ACCEPTANCE_TESTS=true ./mvnw package
RUN_RECEIVER_TESTS=true ./mvnw -f after-sale-receiver/pom.xml package
node --check src/main/resources/static/reconciliation.js
```

新增验证覆盖：

- 原结果补记、权限/CSRF/越权参数、租户隔离，查询期间没有本地事务。
- 非肯定观察保留 REVIEW，投递器不领取，旧错误和次数保留。
- 正反两种观察先后顺序、真实双线程竞争、重复/伪造审计版本拒绝。
- 审计写失败时补记一起回滚，原 STARTED 留存。
- 独立 JVM 已获取远端查询响应后被强杀；重启看见 STARTED，再次核查恢复，创建仍只有一次，模型调用不增加。测试报告：`target/chapter-29-process-restart.json`。该故障夹具只在测试 classpath，不进入应用 JAR。
- 接收方真实 JWT 专用 scope、未提交事务先 NOT_OBSERVED 再 PERSISTED、正文冲突、错号/缺失关联回执、只读查询不改变审核状态。

## 本次实际验收结果

2026-10-04，主项目 `RUN_PGVECTOR_TESTS=true RUN_ACCEPTANCE_TESTS=true ./mvnw package` **583 项全部通过**（169 项真实数据库），接收项目 **65 项全部通过**（43 项真实数据库），合计 **648 项，失败/错误/跳过均为 0**。初次全量回归的旧权限断言已按新合同更新；测试容器的一次连接中断在完整重跑中恢复，最终全量构建通过。

在本机 18080 与 18083 做了真实双服务联调，使用新建的合成验收申请，未修改历史事件状态：

| 标识 | 本次值 |
| --- | --- |
| taskId / draftVersion | `247573a5-1363-4470-b66c-f5d8f27de253` / `1` |
| operationId | `20c339a5-ff3d-4874-8e80-6b75c3909ec7` |
| applicationId | `99cb264b-2ba0-4b75-b21d-0be53ecf964d` |
| eventId | `a52ea0ac-2e2e-4dd4-bfe5-70284fc1e1f0` |
| remoteApplicationId | `36a337ae-cd8f-4772-9c0d-ec104fc09156` |
| checkId | `5f18a20a-f1c6-405f-aa84-7e99d6526f13` |

本次临时本机代理只对该 operationId 损坏创建响应：真实接收服务提交后，将回执换为 `{}`，原投递器因此记录 ACK_MISMATCH / REVIEW。浏览器点击“核查原事件”后，代理原样转发只读查询，发送方补记 DELIVERED。代理计数为**创建 1 次、查询 1 次**；客服申请、Outbox、接收方 Inbox 和接收方申请各只有 **1 条**，投递次数仍为 **1**，核查版本从 v0 变为 v1，业务状态仍为 PENDING_REVIEW。

故障代理已停止，客服重新启动后恢复直连 18083。数据库迁移 V10 和核查记录保留。浏览器验证客户账户只见无权限提示，核查员在重启后仍可查看原审计；390 px 手机视口无横向溢出，审计证据可展开，浏览器无错误日志。此前第28章原回执查询也保持原远端编号。

当前服务通过终端运行，18080 / 18083 均启动成功。IDEA 仍停留在另一个项目，尝试项目菜单切换未生效，因此本次**没有宣称已在 IDEA 内启动**；已有 `CloudCustomerServiceInbox` 运行配置保持可用。

## 边界

核查台是本机教学的租户内运营功能，不是生产多租户管理系统。没有对账批处理调度、审计归档或外部审核进度同步。超过 5 分钟的 STARTED 仅表示未记录完成，不能自动认定失败。PERSISTED / DELIVERED 只确认远端持久化，不代表最终售后成功。
