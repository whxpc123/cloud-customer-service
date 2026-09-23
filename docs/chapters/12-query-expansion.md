# 第十二章：多查询扩展与知识合并

本章在第十一章的多轮补全后增加 `MultiQueryExpander`，使一个复杂问题可以从多个角度检索。保留原有 API 和前十一章页面，默认端口仍为 **18080**，不新增数据库迁移。

## 页面和链路

- 多查询实验室：<http://127.0.0.1:18080/internal/query-expansion>
- 最终多轮回答：<http://127.0.0.1:18080/internal/advisor-rag>
- 管理台知识问答：<http://127.0.0.1:18080/internal/knowledge-admin#chat>

```text
Audit → Memory → RetrievalAugmentationAdvisor
                 ├─ Compression 补全历史追问
                 ├─ 按策略执行 MultiQueryExpander
                 ├─ 每条 Query 分别向量检索
                 ├─ ConcatenationDocumentJoiner 按 ID 合并
                 └─ ContextualQueryAugmenter 注入证据
              → EvidenceRequiredAdvisor → 最终回答模型
```

默认 `AUTO` 使用有限主题词规则选择是否扩展；普通运费单问题走 Top 5，复杂问题默认请求 3 条变体并保留补全后的完整问题，成功扩展后每路 Top 3。阈值均为 0.60。正式问答可选 `OFF`（单路）或 `ON`（强制扩展）；AUTO 不是模型意图分类，也不保证识别所有复合问题。

本章多路在当前请求线程**依次执行**，最多 6 路。任何一路检索或证据校验失败就停止后续真实检索，不将部分结果交给回答模型。使用 `SyncTaskExecutor`，不额外引入线程池；如将来改为并行，必须同时改造请求轨迹的并发安全和取消策略。

## 核心代码入口

| 文件/类型 | 职责 |
| --- | --- |
| `rag/config/QueryExpansionConfiguration` | 无 Memory、RAG、Tools 的独立扩展客户端，temperature 0.2、maxTokens 1400 |
| `rag/expansion/GuardedQueryExpander` | 官方组件外层的数量、模式、异常回退与文本去重 |
| `rag/expansion/ExpansionQueryGuard` | 数字、时间、质量、激活否定与退款提交语义的有限保护 |
| `rag/expansion/MultiQueryRetrieval` | 各路相同服务器过滤、逐路证据校验、按计划顺序调用官方 Joiner |
| `rag/expansion/QueryExpansionTrace` | 每请求独立记录计划、实际执行、失败状态与合并统计 |
| `rag/expansion/LocalQueryExpansionService` | 只读会话实验，可选单路与多路真实检索对照 |
| `rag/config/CustomerModularRagConfiguration` | 将扩展、检索、合并接入原有正式 RAG |
| `config/KnowledgeChatClientConfiguration` | 提示模型逐项回答，缺少依据的子问题单独说明 |
| `static/expansion-lab.js` | 展示实际查询、候选来源、分数、重复块和 JSON |

新增 Java、测试及页面均提供中文说明。扩展模型复用 `DASHSCOPE_API_KEY`，不是本地模型。向量模型继续使用云端 `text-embedding-v4`，向量库存于本机 PostgreSQL。

## 实验 API

先 `POST /internal/advisor-rag/conversations` 获取会话 ID，身份沿用 `X-Demo-User-Id: 1001`（省略为访客）。实验读取同一隔离会话的近期历史，使用会话锁，但不把实验问题和扩展结果写入记忆。

```http
POST /internal/query-expansion/{conversationId}/expand
Content-Type: application/json
X-Demo-User-Id: 1001

{
  "question": "商品签收十天后发现质量问题，还能不能退、运费谁承担、退款多久提交？",
  "numberOfQueries": 3,
  "includeOriginal": true,
  "compare": true
}
```

- `numberOfQueries`：1～5，默认 3；页面提供 1、3、5 对照。
- `includeOriginal`：默认 true；指保留 **Compression 后的完整查询**，不是未补全的短追问。
- `compare`：默认 false；false 仅转换和扩展，不调用 Embedding、数据库检索或最终回答模型。true 另做一次完整问题 Top 5 基线，再执行各路检索。
- `question`：1～2000 字符。范围错误返回 400；所有入口只在 `local,knowledge` profile 下启用。

响应提供 `transformation`、`expansion`、`baseline`、`comparisonStatus`。`expansion` 包含原话、补全问题、实际查询、被丢弃的变体数、扩展耗时、各路实际命中及耗时、合并来源、原始/去重/合并数量和上下文字符数。`NOT_REQUESTED` 不是零命中，`FAILED` 也不是没有相关资料。

正式问答仍使用原路径：

```http
POST /internal/advisor-rag/conversations/{conversationId}/messages
Content-Type: application/json

{"question":"能不能退、运费谁承担、退款多久提交？","expansionMode":"ON"}
```

未提供 `expansionMode` 时为 AUTO。旧响应字段保留，新增 `expansion`；原 `retrievalQuery` 仅代表首路，完整实际查询请看 `expansion.retrievals`。历史评测记录缺少 expansion 时页面继续兼容。

## Spring AI 1.1.2 的实际语义

依据本项目锁定版本的 source JAR 核对，而不是照搬其他版本示例：

1. `MultiQueryExpander` 按换行拆输出，行数必须等于请求数量；不符时直接返回输入 Query。includeOriginal=true 时完整问题在第 0 项。
2. 本项目识别框架回退，避免把原问题误报为扩展成功。异常、全部非法或行数错误时只退回完整查询 Top 5，不自动再发一次扩展请求。即使用户关闭 includeOriginal，失败时也会启用这条兜底，响应中可见。
3. `ConcatenationDocumentJoiner` 按 **Document ID** 去重，同 ID 保留首次出现的文档及分数，再按分数降序排列；不是取最高分。
4. RAA 内部使用 HashMap 收集命中，因此先恢复查询计划顺序再调用 Joiner，保证“首次命中”确定。不同 ID 的相似内容仍保留，跨查询 score 没有全局校准。
5. 模型只能产生查询文字，不能改变 tenant、发布状态、知识库、语言过滤条件或会话历史。每路在去重前校验证据范围，防止错误租户的同 ID 文档被隐藏；合并后原证据门继续复核。

## 语义保护与可见限制

扩展客户端要求保留用户事实、时间和否定条件，不预设政策。有限规则拦截数字变化、丢失质量条件、未激活变已激活、退款提交变到账，以及把商家退款处理时效改成消费者申请期限。在线确实观察过最后一种偏差，已补入提示词和确定性回归用例。

规则并非通用语义证明：例如“十天”换成“10天”会被保守丢弃，未覆盖的主体、条件和同义词仍可能漂移。保留原问题有助于兜底，但不能保证找齐资料。页面显示 PARTIAL、rejectedVariants 和实际查询供人工检查。

最终提示要求逐项回答、每项独立核对，资料不足时说明缺口。Java 证据门只保证存在合规证据，不证明每个子问题都有证据；`ANSWERED` 不是准确性或完整性认证。本章没有结构化子问题清单、自动覆盖检查、重排或语义去重。

## 成本与日志

默认成功扩展增加一次聊天模型调用以及最多 4 次查询 Embedding / 数据库检索；有历史时还会增加 Compression。实验 compare=true 再增加 1 次基线检索，页面提示的是逻辑请求上限，不是供应商账单或底层网络重试次数。最终回答单独调用一次模型。

`contextCharacters` 是合并正文（以两个换行连接）的 Java 字符长度；`contextTokensEstimated` 按 ASCII/4、非 ASCII×1.5 启发式估算，**不是模型 tokenizer、完整 Prompt token 或账单用量**。每路数和 Top K 有上限，但尚未实现最终上下文 Token 硬预算。

普通 `[QUERY EXPANSION]` / `[MULTI QUERY RETRIEVAL]` 日志只输出 requestId、状态、计数、长度和耗时。原文日志沿用 `app.ai.log-payload` 开关，扩展调用标为 `queryExpansion`；开启后会记录查询和模型文本，日志留在本机且不提交 Git。前端 JSON 可直接核对模型通过校验后实际用于检索的文字。

## 自动验证

```bash
RUN_PGVECTOR_TESTS=true JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home ./mvnw package
```

175 项测试全部通过，0 失败、0 错误、0 跳过，包含 14 项真实 PostgreSQL 集成测试。普通测试为 161 项通过、14 项数据库测试跳过。本章新增 17 项自动测试，覆盖官方链路、1/3/5 变体、原问题开关、AUTO/OFF/ON、合并 ID/分数语义、空证据、失败停止、租户同 ID 隐藏攻击、只读历史、敏感事实保护、比较失败和 HTTP 输入校验。原有 Advisor 和转换测试接入升级后的同一条生产链。

打包版本由终端在 18080 实际启动；IDEA 运行配置仍可使用 JDK 17、local,knowledge 和同一端口。本次未把终端启动表述为 IDEA 启动。

## 在线验收记录

使用既有已发布知识，只读检索，没有重置或覆盖知识库。下述是小样例功能验收，不代表整体召回率或准确率。2026-09-24 使用 Qwen 和真实 pgvector 观测如下；每次生成可能不同。

主问题：“商品签收十天后发现质量问题，还能不能退、运费谁承担、退款多久提交？”

| 实验 | 实际查询路数 | 原始命中 → 合并 | 扩展耗时 | 观察 |
| --- | ---: | ---: | ---: | --- |
| 1 条变体 + 完整问题 | 2 | 6 → 4 | 963 ms | 没找到退款时效；单路基线 5 块，更多查询不必然更多结果 |
| 5 条变体，不保留完整问题 | 3 | 9 → 6 | 2269 ms | 2 条改变申请语义的变体被丢弃；找到退款时效，基线 5 块 |
| 正式问答 ON，3 条变体 + 完整问题 | 4 | 11 → 6 | 1383 ms | 找到退款时效，最终按三项回答 |
| 同主问题，浏览器独立实验 | 3 | 8 → 5 | 1334 ms | 本轮又出现“退款申请应在多久内提交”，被拦截；因此未找到退款时效，状态 PARTIAL |
| 未激活软件：能不能退、邮费谁承担 | 4 | 9 → 4 | 1064 ms | 各条保留未激活；基线 3 块，未据此断言未激活必然可退 |
| 质量问题运费，AUTO | 1 | 2 → 2 | 0 ms | SKIPPED_SIMPLE，不调用扩展模型 |

正式主问题一轮（requestId `bdbde52d-1b1b-4213-a0d9-5d0ee6b4da48`）找到既有《云杉商城售后服务规范》第 4 块退款时效，并回答了验收通过后三个工作日内提交退款。基线主问题检索未找到该块。该差异是本地这一份知识和这一轮查询的结果，不能推广为整体召回提升。

还有两类必须保留的局限：

- 同样输入在浏览器轮次触发语义拦截，少了一路有效退款查询，最后仍可能漏检。增加数量不能保证覆盖；这里没有为了展示成功自动重试。
- 最终模型仍可能以“可以”开头回答能否退货，或扩写资料没有明确列出的流程说明，即使后文补充核实条件。提示已要求条件式表达，但尚无逐句事实校验，因此不能把 ANSWERED 当作业务审批或完全忠实的证明。

另一个复合问题“质量问题退货运费谁承担，退款后能得到多少火星旅行积分”分两项回答：运费有依据，积分明确说明资料不足，没有编造积分数。该检查验证了局部拒答表现，不是机器判定的覆盖率。

浏览器验收覆盖实验提交、来源/原始 JSON 展示、选项与成本提示联动、正式问答策略、管理台章节入口。390px 宽度下页面无横向溢出（scrollWidth=390），两面板切为单列；检查的浏览器控制台没有错误。当前浏览器保留真实实验结果，PARTIAL 也属于可检查的实际结果。
