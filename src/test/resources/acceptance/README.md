# 第一阶段固定验收夹具 acceptance-v1

这些资料仅用于教学验收，不是现实订单或法律结论。`StageOneLiveExperiment` 每次创建临时 pgvector 数据库；不会修改用户的本地知识库。

- Clock：2026-08-30 10:00:00 +08:00，固定给售后服务；接待时间仍用数据库实际提交时间。
- 身份：tenant-yunshan / 1001、2002 / 客服 9001、9002；切片额外创建其他租户。真实 HTTP 使用独立测试密码；生产 IAM 未接入。
- 订单：沿用当前 InMemoryOrderService 和本地售后事实适配器。A10001 属于 1001，A10002 属于 2002，避免为文章中的 A20002 改写项目既有样例。
- 政策：四条 LocalKnowledgeDocuments（refund-policy 3.2、logistics-policy 2.1、invoice-policy 1.4），新增 refund-timing 1.0。另有一条归档旧政策及其他租户的相似政策，故意包含不适用内容。
- 上传夹具：名称“阶段验收编码资料”，1.0 包含 CPN-19A1，2.0 改为 CPN-19B2。走正式 ETL/Embedding/入库，查询向量及关键词，检查旧版退出和元数据，再归档，避免影响政策题集。
- 知识评测：`knowledge-cases.json` 的证据用 `sourceId@version#chunkIndex` 标注；每组可含等价 ID，整题需全部组命中。原始报告同时保存实际 UUID、正文、各阶段列表，不能用一个来源命中冒充三项覆盖。
- 路由基准：继续复用 `../routing-cases.json` 和既有离线分类测试。在线本章运行五个正式 HTTP 场景，不能冒充全部 15 题分类基准复测。

`requiredMeaning` / `forbiddenMeaning` 是语义复核要求，评分器不以字符串包含测试代替人工评审。报告默认将语义完整性、错误拒答率标为未执行；自然语言拒答与 NO_EVIDENCE 程序状态并不等价。

## 故障与竞争证据索引

| 场景 | 可执行证据 | 断言边界 |
| --- | --- | --- |
| 两个申请 / 两个客服竞争 | HandoffAcceptanceTest | 行锁持有，pg_blocking_pids 证明真实等待，再释放；一张受理单 / 一个领取成功 |
| 生成途中转人工 | HumanHandoffPersistenceTest.handoffDuringGenerationCommitsAndLateCandidateNeverPublishes | 门闩控制生成；交接提交后释放旧答案；正式消息不发布 |
| 受理响应未知后重复调用 | requestAndRetryReturnOneDurableReceipt / repeatedRequestCommitsOneReceipt | 重复提交返回同一数据库回执；未注入真实 TCP 丢失 POST 响应 |
| 分类模型故障 | RoutingClassifierTest | 返回模型不可用兜底，不进行业务执行 |
| 订单不可用 | CustomerOrderToolsTest / OrderToolConversationTest | 故障结果不携带旧订单状态，模型不能冒充本次查到 |
| 无证据 / 错误范围 | EvidenceRequiredAdvisorTest / HybridSearchPersistenceTest | 最终模型之前拒绝，按租户、发布状态等过滤 |
| 精排异常 | RerankingTest | 明确降级和预算，不把降级记成云端成功 |
| 事务回滚 | HumanHandoffPersistenceTest.failedSystemMessageRollsBackHandoffTogether | 系统消息写入失败同时回滚受理状态 |
| 流失败/取消/会话退出 | SseHttpIntegrationTest | 真实 Tomcat；第二轮 RUN_NGINX_TESTS=true 加真实 Nginx，验证服务器连接计数归零 |
| 断帧/缺号/EOF | scripts/tests/sse-client.test.mjs | 明确终结事件才完成；不自动重发 POST |

外部服务故障替身、真实数据库和真实 HTTP 各有职责，不把受控模型的结果算作真实模型效果。没有正式业务文字流，因此不存在“正式退款片段实时发布”的验收结论。
