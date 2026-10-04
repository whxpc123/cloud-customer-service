# 第二十八章：同一条事件收到了三次，接收方为什么只能办一次？

新增独立 Maven 应用 [`after-sale-receiver`](../../after-sale-receiver)，JDK 17 / Spring Boot 3.5.8，不引入 Spring AI。客服应用仍运行于 **18080**，接收服务运行于 **18083**。两者使用不同数据库及应用登录角色，同机演示复用 PostgreSQL 实例。

```text
客服库 cloud_customer_service                  接收库 after_sale_receiver
申请 + Outbox 同事务提交                       服务 JWT → 固定来源映射
       ↓                                                ↓
短事务领取 → HTTP（固定 eventId） →            INSERT Inbox（唯一身份）
       ↑                                       ├─ 首次：建单 + 保存回执
       │                                       └─ 重复：核对正文 + 读取原回执
       └── 校验 PERSISTED，保存 DELIVERED ←     整体提交以后返回
```

## 实现与阅读顺序

| 文件 | 作用 |
| --- | --- |
| `inbox/InboxModels.java` | 固定 v1 协议、可信来源、用户陈述、四字段回执 |
| `inbox/ReceiverSecurityConfiguration.java` | 无状态 JWT，POST 限定 scope，其他路径拒绝，不使用浏览器 Cookie |
| `inbox/InboxController.java` | subject 映射为稳定来源；最多读取 64 KiB + 1 字节；严格 UTF-8 |
| `inbox/InboxProtocol.java` | 重复键、未知字段、尾随对象、浮点截断、标量自动转换、业务约束检查 |
| `inbox/InboxApplicationService.java` | 独立 READ COMMITTED 短事务，登记、建单、固定回执原子提交 |
| `inbox/InboxErrors.java` | 稳定错误码，不返回 SQL、正文、令牌或堆栈 |
| `inbox/LocalJwtConfiguration.java` | 显式 `local-jwt` 教学配置：真实 RSA 签名与时间/issuer/audience 验证 |
| `src/main/resources/db/migration/V1__receiver_inbox.sql` | 接收库自己的迁移；发送端 V1～V9 不变 |

上述 Java 文件位于 `after-sale-receiver/src/main/java/com/example/aftersalereceiver/`。

## 为什么不会重复建单

`rx_inbox` 的主键是 `(producer_id, tenant_id, event_id)`。producerId 来自认证后的 subject 与服务端配置，不取自 JSON、完整令牌或 jti。同一个服务主体换合法令牌，仍进入相同去重空间。

处理入口通过 Spring 事务代理开启 `REQUIRES_NEW + READ_COMMITTED`，5 秒事务超时；数据库 `lock_timeout=3s`、`statement_timeout=4s`。事务中只有数据库操作，不调用模型或其他网络服务。

1. `INSERT ... ON CONFLICT DO NOTHING` 争取处理资格，唯一索引协调并发。
2. 首次处理创建 `PENDING_REVIEW` 申请，再将 Inbox 改为 `PROCESSED` 并保存固定回执。任一步或最终提交失败，整笔事务回滚。
3. 已存在时，使用**下一条 SELECT** 的新语句快照读取原记录。JSONB 内容相等才回放原回执，不执行建单 UPDATE。
4. 同 eventId 改正文返回 409。改变对象字段顺序和格式空白允许回放；字符串内部内容仍精确比较。
5. 来源申请和来源 operationId 各有唯一约束。换 eventId 复用任一业务编号返回 409，本轮新 Inbox 一起回滚。

接收方进入后续审核状态后，重复创建事件仍返回原创建回执，业务状态不会被重置。`PERSISTED` 表示申请和回执已经提交，不表示审核通过或退款。

## HTTP 合同与认证

唯一业务入口：`POST /integration/after-sales/applications`，`Content-Type: application/json`，`Idempotency-Key` 等于正文 eventId。

正文与第 27 章保持一致：schemaVersion、eventId、eventType、applicationId、operationId、tenantId、orderNo、draftVersion、userStatement、occurredAt。完整合成示例见 [`after-sale-receiver/requests.http`](../../after-sale-receiver/requests.http)。

首次和重复均返回 HTTP 200：

```json
{
  "eventId": "原事件UUID",
  "applicationId": "来源申请UUID",
  "remoteApplicationId": "接收方申请UUID",
  "status": "PERSISTED"
}
```

| HTTP | 典型情况 | 发送方行为 |
| --- | --- | --- |
| 400 | 错误 JSON / 版本 / 字段 / 幂等头 | REVIEW |
| 401 | 无令牌，签名/issuer/audience/时效不符 | REVIEW，修复凭证后按授权流程核查 |
| 403 | scope、subject 或正文租户不允许 | REVIEW，不返回原回执 |
| 409 | 原事件正文冲突，或换号复用原业务 | REVIEW，不能换号绕过 |
| 413 | 请求体超过 65536 字节 | REVIEW |
| 500/503 | 回执损坏、事务失败、锁超时、结果未确认 | 保留原 eventId 重试，达到上限后 REVIEW |

未知路径不开放。没有公开的 Inbox 列表、删除、重置或人工补发接口。普通日志不输出请求正文或服务令牌。客服原有 Cookie / CSRF 安全配置不受影响。

## 本机启动

在仓库根目录执行：

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home
# 首次准备；以后再执行只刷新本机短期令牌，保留密钥、数据库和记录。
./scripts/prepare-receiver-local.sh

# 终端一：接收端，18083，无需 DASHSCOPE_API_KEY
./scripts/start-after-sale-receiver.sh
```

脚本生成的 `.local/receiver-database.properties`、`receiver-jwt.properties`、`inbox-sender.properties`、签发私钥/公钥均不提交 Git，文件权限 0600。数据库为 `after_sale_receiver`，登录角色为 `after_sale_receiver`。该角色没有客服业务表的读写权限。已有数据库/角色却丢失配置时，脚本停止，不能靠生成新口令“修复”。

第二个终端启动客服应用；先停止该项目已占用 18080 的旧进程：

```bash
./mvnw -DskipTests package
java '-DsocksNonProxyHosts=localhost|127.*|[::1]' \
  -jar target/cloud-customer-service-0.0.1-SNAPSHOT.jar \
  --spring.profiles.active=local,knowledge,outbox-delivery \
  --server.port=18080 \
  --spring.config.additional-location=file:.local/inbox-sender.properties
```

客服仍从环境变量 `DASHSCOPE_API_KEY` 读取模型凭证；它与服务间 JWT 是两种凭证。

本机令牌有效期 8 小时。续签命令为 `java scripts/LocalServiceToken.java "$PWD"`，然后重启发送方以加载新令牌。接收方无需更换密钥或 producerId。**不会自动重置已经进入 REVIEW 的事件**；此章没有实现自动令牌刷新或对账恢复。

### IntelliJ IDEA

- 原客服项目选择新增配置 **CloudCustomerServiceInbox**，使用 18080 和 `.local/inbox-sender.properties`。
- 将 `after-sale-receiver/pom.xml` 作为独立项目打开，选择 JDK 17，再运行附带的 **AfterSaleReceiverApplication**，使用 18083。接收项目工作目录为子项目目录，配置文件从 `../.local/` 加载。
- 首次先在仓库根目录执行准备脚本；两个进程分别启动。默认客服配置仍不启用后台投递。
- 本次自动操作 IDEA 项目菜单仍未能切换工程，实际运行验证采用终端；不能把附带运行配置称为已经在 IDEA 内启动。

### 正式服务身份

不启用 `local-jwt`，配置接收方独立 JDBC 地址、用户名、口令及 `SERVICE_TOKEN_ISSUER`。默认走 Spring Security 真实 issuer discovery，约束 `aud=after-sale-receiver`，并要求 `scope=after-sale.ingest`、授权 subject。对外接入需配置监听地址、HTTPS 和部署环境的秘密管理。

本地教学签发工具不是完整 OAuth 授权服务器，不提供自动续签、撤销或多租户授权目录。不要把本机私钥部署到真实接收服务。真实生产身份系统、本章之外的审核/退款平台均未接入。

## 与第 27 章数据的边界

18081 文件演示器及原运行配置保留。原 `DEMO-...` 回执不会被迁移成接收数据库记录；旧 DELIVERED 不重新发送，历史 LOCAL_ONLY 也不补发。

切换本机教学接收器前，已检查发送端只有一条 DELIVERED，没有 PENDING/SENDING/REVIEW。新联调只使用新建并明确批准同步的教学申请。生产环境不可在保留待发事件时随意更换 AFTER_SALE_V1 的业务目的地。

## 验证

两个 Maven 项目分别运行；根目录测试不会暗中包含子项目：

```bash
RUN_RECEIVER_TESTS=true ./mvnw -f after-sale-receiver/pom.xml package
RUN_PGVECTOR_TESTS=true RUN_ACCEPTANCE_TESTS=true ./mvnw package
```

- 接收端 **49 项**通过：22 项纯协议测试，27 项真实 PostgreSQL/HTTP/JWT/进程测试，无跳过。
- 同一事件三次、对象顺序变化、正文冲突、来源/租户隔离、新 eventId 复用 applicationId 或 operationId。
- 正确签名的新令牌回放同一申请；错误签名、issuer、audience（含缺失）、subject、scope、过期、未来生效、缺少 exp 拒绝。
- 固定长度与 chunked 超长正文、错误 UTF-8、重复字段、未知字段、版本和请求头不符。
- 临时触发器在申请 INSERT 后、以及事务 COMMIT 时制造异常，两表均无残留；移除后同事件恢复成功。
- 通过 `pg_stat_activity` 的实际锁等待协调两个事务；前者提交时回放，回滚时后者接管。锁超时 HTTP 503，原事件可恢复。
- 真实 HTTP 代理在接收事务提交后断开首次响应，后续两次均取得原回执，申请始终一份。
- 两个独立 JVM 先后启动并被强制结束，再次发送同事件得到数据库原回执。成功去重不依赖进程内集合。

主项目全量 **544 项**通过（156 项真实数据库），无失败或跳过。与接收端合计 **593 项**，其中 **183 项**使用真实数据库。最终补充了不支持的 Content-Type 返回 415、非法幂等头返回 400 的 HTTP 验证，避免把永久输入错误误报为 500。

### 本机两库联调记录（2026-10-04）

通过真实 Qwen 候选与整理、具体草稿 V2 内容确认、明确同步范围批准和浏览器执行，创建了新的教学申请。两边只登记待审核材料，没有审核通过或退款动作。

| 关联身份 | 本次实际编号 |
| --- | --- |
| taskId | `07550bf6-034e-4422-a0eb-5862220176d5` |
| operationId | `c204c65d-3ca4-44c9-8d0a-10ef45ab4b8c` |
| 本地 applicationId | `61ea797d-a088-4630-9560-d73c1188b97e` |
| Outbox / Inbox eventId | `8e95ded8-8d00-4444-b148-8e28f6a98c39` |
| 接收方 remoteApplicationId | `a4314f3e-9d97-4b1e-bd7d-efb6d4427bed` |

08:49:51 本地提交，08:49:52 保存 DELIVERED，投递器领取 1 次。随后用三次回放工具发送同一事件，三次 HTTP 200、回执完全一致。直接查询接收库：一条 PROCESSED Inbox、一份 PENDING_REVIEW 申请，来源操作和申请编号均与发送端一致。浏览器显示同一远端编号，刷新只读，控制台未见错误。最终接收 JAR 重启后，再运行三次回放工具也保持同一个编号与一份申请。

本机联调没有故意断开这次实际投递；回执断开和进程崩溃场景由上述隔离数据库自动测试覆盖。当前客服 18080、接收端 18083 均为终端启动；第 27 章原 18081 文件接收器保留，旧记录未迁移或补发。

可复现已成功事件的三次回放：

```bash
python3 scripts/replay-inbox-event.py 已成功处理的eventId
```

工具只允许固定回环端点，先确认接收库已有 PROCESSED 且发送端为 DELIVERED，再发送三次原正文、核对相同回执及一份业务记录。不会首次投递、补发旧演示结果、打印令牌或完整正文。

## 保证范围

保证的是当前保留期内，同一受信来源/租户/事件的数据库业务效果不会重复。网络、方法调用、响应本身仍可能多次或丢失。没有笼统承诺全链路 Exactly Once。

本章不清理成功 Inbox；其保留期需要覆盖重试、人工重投及灾难恢复窗口。归档后如何回放、隐私保留期与备份保护仍需部署时设计。跨事件乱序、下游通知、自有 Outbox、多来源映射、对账恢复属于后续范围。

依据：[PostgreSQL READ COMMITTED 与 ON CONFLICT 的快照说明](https://www.postgresql.org/docs/17/transaction-iso.html)、[Spring Boot 3.5 JWT Resource Server](https://docs.spring.io/spring-boot/3.5/reference/web/spring-security.html)、[Spring Security 6.5 JWT 验证](https://docs.spring.io/spring-security/reference/6.5/servlet/oauth2/resource-server/jwt.html)。
