# 第十四章：精确编码与混合检索

在第十三章候选召回和后处理之间增加关键词与编码召回。正式多轮问答、管理台聊天和基础评测使用新链；第七、九章旧接口以及第十一至十三章独立实验保留原来的对照语义。

## 实际链路

```text
原始问题 → 实时业务问题检查 → 历史补全 → 受控扩展
→ 多路向量检索及合并
→ 完整独立问题的关键词检索 + 编码元数据精确检索
→ 按 ID 去重，RRF 融合，精确命中优先，最多 24 条
→ qwen3-rerank（或明确降级）→ 完整块预算 → 证据门 → 回答
```

查询补全、扩展额外检查编码全值，不能保留数字却改掉 SKU 前缀/字母，也不能凭空补充用户没有提过的编码。正式调用仍以原始问题作回答与记忆，词法检索使用补全后的完整问题，不使用某个拆分子问题冒充完整意图。

本地 `A10001` 等演示订单号以及“我的退款到账了吗”等有限规则返回 `BUSINESS_TOOL_REQUIRED`，引导到首页普通客服的已有业务工具；不会向量匹配相近订单号，也不声称已自动调用工具。该规则不是完整自然语言路由器，真实应用仍需业务认证、意图路由和实时数据接口。转工具的基础评测题不计算来源/拒答分数。

## 数据库 V4

新增 `V4__hybrid_search.sql`，不更改已发布 V1～V3：

- BEFORE INSERT / UPDATE 触发器从知识块正文及既有 `couponCode` / `productCode` / `policyCode` 字段提取编码，统一写入 `metadata.businessCodes` 数组。以块为单位，不把整份文档的所有编码复制到每个无关块。
- 编码支持 `CPN-`、`SKU-` 后 4～16 位 ASCII 字母数字，以及 `POLICY-4.2` 形式。统一大写，ASCII 边界允许紧邻中文，禁止将更长型号截断；Java 与 SQL 采用一致边界。
- 回填现有资料的编码；不改变正文、向量、发布状态或原件。所有写入路径（批量 upsert、样例、归档/恢复）都会维护这些元数据。
- `search_vector` 使用带明确 `simple` 配置的 STORED 生成列，全文 GIN 索引随正文/元数据更新；编码 JSON 数组另建 GIN 索引。无须重新调用 Embedding。
- 归档和旧版替换继续复用已有事务。多份同时 PUBLISHED 的冲突版本不会自动按版本字符串选“最新”，也没有实现尚不存在的生效日期字段。

生成列方式参考 [PostgreSQL 官方表与索引说明](https://www.postgresql.org/docs/17/textsearch-tables.html)。第一次升级需要回填和建索引，大库应安排维护窗口；本章没有声称完成八万条压力测试。

## 查询和融合

`PostgresKeywordSearchRepository` 使用 PreparedStatement 参数，每条查询包含 `tenantId + PUBLISHED + after-sales + zh-CN`；对象结果还会由原证据校验器再次核对。正文 JSON 完整解析，保留来源、版本、块号与权限元数据。

关键词采用 `search_vector @@ websearch_to_tsquery('simple', ?)` 和 `ts_rank_cd` 排名。这是 PostgreSQL 全文检索，**不是 BM25，也不提供中文分词**。带编码问题使用提取编码的 OR 查询，避免整段中文变成无法满足的 AND 条件；无编码问题按原完整文本查询，不暗中追加模糊 LIKE 扫描。参考 [PostgreSQL 查询与排名说明](https://www.postgresql.org/docs/17/textsearch-controls.html)。

精确查询使用 `metadata::jsonb->'businessCodes' @> ?::jsonb` 完整值包含关系。`SKU-E100` 不会精确命中 `SKU-E1000`；多个编码是 OR，最多八个。元数据仍只是资料线索，不能证明来源可信或所有内容适用。

RRF 对向量、关键词榜单采用从 1 开始的名次：

```text
rrfScore = （命中向量榜时 1/(60+vectorRank)，否则 0）
         + （命中关键词榜时 1/(60+keywordRank)，否则 0）
```

同一路重复 ID 只计一次。先按是否 EXACT 分层，再按 RRF 降序，平分按文档 ID 保持确定顺序。没有将不同量纲的原分数相加，精确匹配也没有虚构一份“模型高分”。多路向量阶段继续沿用第十二章稳定合并，RRF 的向量名次取该合并榜，而不是给每个扩展问题重复投票。

有精确候选时，重排请求返回所有入围者的排名（最多 24），Java 再把精确候选稳定置前并截取 Top N。`rerankScore/rerankRank` 保留供应商原结果，最终展示位置可以与模型名次不同。后续输入/上下文预算仍能排除过长块并记录原因；超过上限的多个编码不保证每个都覆盖。

`KnowledgeReference.hybrid` 保存 `retrievalSources`、`exactMatch`、`vectorRank/Score`、`keywordRank/Score`、`rrfScore`。正式引用与最终 Prompt 使用同一批文档。`score/retrievalScore` 在融合阶段是 RRF，不冒充向量相似度。旧 `expansion` 轨迹仍只统计向量多路；融合总候选见 `reranking.before`。

## 页面与 API

打开 <http://127.0.0.1:18080/internal/hybrid-search>：

```http
POST /internal/hybrid-search/compare
Content-Type: application/json

{"question":"CPN-88A7 退款后会返还吗？","topK":10,"rerankEnabled":false}
```

展示同次向量、关键词、编码精确、融合四份真实列表，以及预算后的完整上下文、每种分数和原始 JSON。`topK` 范围 1～24，页面默认 10；向量阈值 0.45。最终默认 Top 6、最多 6 个完整块、正文估算 5000 tokens。开关仅影响精排，不隐藏召回来源。实验不做历史补全，不写知识或记忆，不生成客服回答。

实验入口最多两个在途请求、每分钟二十次（本地单实例整体限额），超限 429；输入上限 2000 字符、八个编码，数据库单条语句超时 3 秒。任一路错误都返回失败，不把半套结果称为完整混合检索。不自动重试云请求。限流是本实验入口保护，不是全站生产网关。

页面复用同一 Spring Boot 静态资源，中文注释，动态内容用 textContent。正式问答和管理台的来源卡也展示 `VECTOR / KEYWORD / EXACT` 与精确优先。

## 本机验收（2026-09-24）

使用 JDK 17，执行 `RUN_PGVECTOR_TESTS=true ./mvnw package`，214 项全部通过，0 失败、0 错误、0 跳过，其中 19 项真实 PostgreSQL 集成测试。新增算法与真实数据库测试覆盖手算 RRF、重复 ID、编码边界、改写保留编码、精确优先、范围错误、超时不降成空命中、生成列更新、归档旧版过滤、SQL 输入与中文分词局限。普通测试不调用云模型。

本章在线验收用一份明确写着“技术验收样例，不是商城真实制度”的临时资料：

| 验收项 | 实际观察 |
| --- | --- |
| CPN-88A7 三路对照 | 向量 7、关键词 1、精确 1、融合 7；样例位于首位，来源同时包含 VECTOR / KEYWORD / EXACT |
| 样例融合分 | vectorRank=1，keywordRank=1，RRF=2/61≈0.032787；不是相关性百分比 |
| 正式知识问答 | ANSWERED；引用保留三路来源，回答明确限制在验收样例，并说明不代表真实商城政策 |
| 开启重排 | FALLBACK_NOT_CONFIGURED，未调用云端，沿用融合顺序；不把它当排序收益 |
| A10001 发货了吗 | BUSINESS_TOOL_REQUIRED，提示业务工具，不展示知识库订单状态 |
| 样例归档后 | 关键词、精确均为 0；样例退出融合候选，原用户资料保留 |
| 页面 | 390px 宽度无页面横向溢出，来源展开/重排开关/JSON 可用，控制台无错误 |

验收样例已归档，可在管理台回收站查看或恢复。再次查询 CPN-88A7 没有精确命中是预期行为，需导入真实的编码资料才能用于正式规则问答。此小样例没有证明整体准确率提升。

新版 JAR 在终端 18080 实际启动。IDEA 共享运行配置仍使用该端口与 `local,knowledge`；此前窗口自动控制故障未解决，不能把本次终端运行描述成 IDEA 自动运行。

qwen3-rerank 仍需 `DASHSCOPE_RERANK_BASE_URL` 百炼业务空间地址；沿用 `DASHSCOPE_API_KEY`，没有更换模型或要求贴出 Key。
