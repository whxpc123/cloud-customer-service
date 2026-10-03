# 第二十六章：同一笔提交，返回同一份申请回执

入口：<http://127.0.0.1:18080/internal/draft-tasks/submission>。使用现有客户账号登录；从第 23 章某份**当前且已确认**的草稿进入，或填写具体 taskId / draftVersion 准备操作。

## 本章真正写入什么

本章通过新的 `V8__idempotent_submission.sql` 在应用 PostgreSQL 的 `ai` schema 中增加：

- `cs_submit_operation`：稳定 operationId、固定任务/草稿版本/动作、接待版本、持久化决定、决定人/时间与首次执行期限。
- `cs_after_sale_application`：本系统的待审核申请、固定正文快照、创建时间与原 operationId。

申请状态只有 `PENDING_REVIEW`。这是**本地申请受理**，不是售后审核通过、退款完成或外部售后平台已接收。没有 HTTP 远程提交、消息投递、Outbox、自动审核或退款。草稿中的订单核验仍是生成时的历史快照。

第 24 章 HITL 和第 25 章夹具实验保留，不把它们的内存 APPROVED 或计数当成本章提交授权。新的正式本地操作以数据库记录为准，决策方法没有注册为模型工具。操作重试沿用原编号，不使用 runId、toolCallId 或每次随机生成的新 UUID 代替。

## 服务与事务

`submission/IdempotentSubmissionService` 提供 prepare、decide、submit、result，以及只读目录和固定正文详情。`@Transactional(REQUIRES_NEW, READ_COMMITTED, timeout=5, rollbackFor=Exception.class)` 由 Spring 代理执行；`JdbcTemplate` 使用应用的同一 DataSource。锁等待限制 3 秒，不在事务中等待用户或调用模型。

写入顺序：

```text
prepare：会话 → 任务锁 → 查询同版原操作 → 校验当前确认 → 保存 PENDING_APPROVAL

decide：归属校验 → 会话 → 任务 → 操作锁 → 校验明确批准 / 拒绝 → 保存决定

submit：归属校验 → 会话 → 任务 → 操作锁 → 先查询原回执
  已成功：返回同一 applicationId / createdAt
  未成功：检查批准、期限、BOT 接待版本、当前草稿和确认
          → 插入申请 → 操作 SUCCEEDED → 准备任务 CLOSED → 一起提交
```

会话锁顺序沿用旧章节；操作捕获 `reception_version`，交接后即使回到 BOT，旧首次执行授权仍失效。回放已成功结果只核对当前所有权，不要求旧批准未过期、任务未关闭或会话仍是 BOT。关闭的是准备任务，不是客服会话。

数据库唯一约束分别保证同任务同版本最多一笔操作、同操作最多一份申请、同任务最多一份申请。外键绑定内容确认和操作目标；触发器阻止目标换绑、反向状态变化、批准人改写和申请快照原地 UPDATE。成功历史无删除 API；数据库管理账户仍可能绕过保护，这不是生产不可篡改审计。

十五分钟只是首次执行窗口，重复 prepare 不延时或换号，重复相同决定返回原决定；已拒绝不能改成批准。过期和失效不自动续期，也没有增加恢复/撤销接口。

## 真实执行图

`submission/SubmissionGraph` 是新的真实 StateGraph，每次使用独立内存检查点：

```text
START → lookup_receipt
  FOUND   → receipt → END
  MISSING → submit_once
              成功 → receipt → END
              409  → blocked → END
              数据库/事务异常 → reconcile → END
  查询异常 → reconcile → END
```

先查回执，避免已关闭任务阻止成功重试。`submit()` 内仍再次查回执，解决两次节点调用之间另一个请求先完成的情况。这里没有自动重试边，也不宣称图与业务数据库共同提交；即使图后续失败，下一次用相同 operationId 仍能恢复业务回执。

Graph 返回真实 `trace`、明确业务状态和数据库 Receipt。`replayed=true` 只表示本次在 lookup_receipt 节点直接找到回执；`false` 不能证明一定是首次创建，因为 submit 服务内部也可能回放并发请求的结果。

数据库或提交确认异常转 `RECONCILIATION_REQUIRED`，不伪造成功也不宣称一定失败。明确条件冲突为 `BLOCKED`。身份与归属错误保持 401/403/404。`GET result` 返回 `NOT_OBSERVED` 仅表示当前尚未观察到已提交回执，不能证明进行中的事务不会成功。

## 页面与 API

沿用本机限制、Session 登录、customer:chat 和 CSRF；匿名只能看页面壳。客户端不传 actor、approvedBy、正文或 Graph State；多余字段直接 400。

| 方法 | 路径（共同前缀 `/internal/draft-tasks/submission`） | 请求 / 含义 |
| --- | --- | --- |
| GET | `/operations` | 当前客户最近 100 笔，包含已完成操作 |
| POST | `/operations` | `{"taskId":"UUID","draftVersion":1}`，重复准备返回原编号 |
| GET | `/operations/{id}` | 固定正文、操作决定、是否仍可首次执行、历史回执 |
| POST | `/operations/{id}/decision` | `{"decision":"APPROVE","accepted":true}` 或 `{"decision":"REJECT"}` |
| POST | `/operations/{id}/execute` | `{}`，稳定编号仅在路径中；执行实际 Graph |
| GET | `/operations/{id}/result` | 只查询写库，不执行图或提交 |

页面将批准与执行分开。明确勾选后才能批准；批准不自动创建申请。操作编号保留在 URL hash，重新登录、刷新或重启后可从数据库目录找回；不把浏览器状态当授权。网络错误不自动重试，不重新生成操作。固定正文与创建回执分别展示，成功后可显式再次执行原操作对比原回执。

## 验证与运行

```bash
RUN_PGVECTOR_TESTS=true RUN_ACCEPTANCE_TESTS=true \
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home ./mvnw package
node --check src/main/resources/static/submission.js
```

新增真实数据库测试从前章服务生成并确认草稿，覆盖重复准备/决定、顺序与并发回放、旧草稿/新 runId/接待变化、过期首次执行与过期成功回放、唯一约束、跨用户/租户、HTTP/CSRF/严格 DTO、事务中途失败、锁超时核查、未提交结果不可见和跨 JVM 重启。业务锁竞争通过 PostgreSQL wait_event 观察，不用固定 sleep 假装并发。

2026-10-04 验收结果：

- 新增 20 项真实数据库专项测试通过，其中 19 项服务/HTTP/并发验证与 1 项两个独立 JVM 的重启验证。
- 全量 `package`：**502 项测试，0 失败、0 错误、0 跳过**，其中 143 项依赖真实 PostgreSQL。最终页面文案与查询展示调整后重新打包，两个前端脚本语法检查通过。
- 中途错误测试在申请 INSERT 之后、操作更新时由真实数据库触发异常，申请回滚、操作仍 APPROVED、任务版本未变化；去掉测试故障后使用原编号可正常创建。
- 并发准备返回相同 operationId；两个独立事务并发执行返回同一回执，数据库一条申请。另一项测试让 INSERT 后事务等待，外部 result 只观察到 NOT_OBSERVED，提交后才出现成功回执。
- 两个独立 JVM 验证强制结束前一进程后，重新登录、GET 查询和再次 execute 均返回相同完整回执，原 operationId 保留，数据库申请数仍为 1，新增模型调用为 0。
- 实际应用在 18080 成功迁移 V8 并启动。浏览器使用新的验收会话和演示订单 A10001，经前章真实模型流程生成并确认 V1，再完整走本章准备/批准/执行。重复准备的编号与截止时间相同；批准只保存决定、尚无申请；首次执行轨迹为 `lookup_receipt → submit_once → receipt`，重复执行变为 `lookup_receipt → receipt`。
- 浏览器得到的申请编号与创建时间逐字一致；数据库核对操作 SUCCEEDED、任务 CLOSED、申请 PENDING_REVIEW，只有一条申请。第 26 章新进程的提交/查询期间日志中模型调用为 0（草稿生成发生在准备阶段）。
- 浏览器刷新仅有 GET，回执保持一致；390 px 下 body / document scrollWidth 都为 390，只读查询正常。桌面布局与控制台检查通过，无浏览器错误；临时尺寸覆盖已清除。
- 18080 当前通过**终端**运行。尝试 IDEA 项目切换未生效，本章没有取得 IDEA 内启动证据，不将终端验证描述为 IDEA 验证。

本次真实页面验收留下的是本地演示订单的待审核申请；没有联系外部售后平台，没有资金操作。自动化并发/故障/重启测试均在独立临时数据库执行。

事务代理语义参考 [Spring 官方事务文档](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/annotations.html)，行锁与锁顺序参考 [PostgreSQL 官方锁文档](https://www.postgresql.org/docs/current/explicit-locking.html)。实现与测试继续使用本项目固定的 JDK 17、Spring Boot 3.5.8、Spring AI 1.1.2、Alibaba 1.1.2.2，不因在线文档版本变化升级依赖。
