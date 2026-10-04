# after-sale-receiver

第二十八、二十九章独立售后接收服务，Java 17 / Spring Boot 3.5.8。无 AI 依赖，默认只绑定 127.0.0.1:18083，服务 POST 经 JWT 认证。Inbox 登记、待审核申请与固定成功回执同事务提交。

完整设计、启动、IDEA 双项目配置、验收及边界见[第二十八章说明](../docs/chapters/28-inbox-receiver.md)。

```bash
# 在仓库根目录运行：
./scripts/prepare-receiver-local.sh
./scripts/start-after-sale-receiver.sh

# 单独构建本应用；不会执行客服项目测试。
RUN_RECEIVER_TESTS=true ./mvnw -f after-sale-receiver/pom.xml package
```

不带 `RUN_RECEIVER_TESTS=true` 时只运行 22 项离线协议测试，43 项集成测试显示跳过，不等于完成数据库验收。集成测试会启动临时 Docker 容器，绝不连接业务库。

`V1__receiver_inbox.sql` 属于这个独立数据库，与根项目 Flyway 版本无关：

- `rx_inbox` 的组合主键隔离来源、租户和事件；状态约束要求 PROCESSED 必须带回执和处理时间。
- `rx_after_sale_application` 的两个业务唯一约束阻止更换 eventId 后重复创建；组合外键关联同来源 Inbox。
- 事务提交前的 PROCESSING 不用于长期异步任务。成功回执保持原样，不代表当前审核状态。
- 不提供删除或重置接口。已执行的迁移文件不得改写；后续变更新增迁移版本。


第29章新增只读 `POST /integration/after-sales/applications/lookup`，携带原 eventId 与原正文，需要独立 `after-sale.reconcile` scope。查询使用主库的已提交快照，只有 Inbox、申请和回执全部匹配才返回 PERSISTED；未观察到不证明未发生，查询不创建任何业务。完整合同与核查端说明见[第二十九章](../docs/chapters/29-result-reconciliation.md)。
