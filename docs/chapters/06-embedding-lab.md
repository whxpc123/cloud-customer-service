# 第六章：两句话一个字都不像，为什么意思却一样？

本章新增本地语义实验：将文本交给 `EmbeddingModel` 生成向量，再由 Java 计算余弦相似度，并对少量候选文本排序。客服聊天、结构化意图识别、会话记忆和第五章只读订单查询保持原有行为。

## 新增结构与调用链

```text
/internal/embedding-lab（local 环境的可视化实验页）
  POST /compare → SemanticSimilarityService.compare
  POST /rank    → SemanticSimilarityService.rank
    → TextEmbeddingService：校验输入，最多每批 10 条
      → 自动配置的 DashScope EmbeddingModel
      → text-embedding-v4 返回 float[]
    → VectorMath.cosineSimilarity：纯 Java 计算
    → dimensions + score / matches（不返回向量）
```

Java 包 `com.example.cloudcustomerservice.embedding` 新增：

| 文件 | 用途 |
| --- | --- |
| `TextEmbeddingService` | 单文本入口 `embed(String)`、有界批量生成、返回校验、元数据日志 |
| `VectorMath` | 余弦计算，使用 double 累加并将舍入误差限制到 −1…1 |
| `SemanticSimilarityService` | 比较两段文本；查询与最多 20 条候选一起向量化并排序 |
| `SimilarityRequest` / `SimilarityResult` | 两句比较的输入、实际维度和分数 |
| `SemanticSearchRequest` / `SemanticMatch` / `SemanticSearchResult` | 排序的查询、候选文本和分数 |
| `EmbeddingLabController` / `EmbeddingLabErrors` | local 实验页和 API、限定作用域的错误处理 |
| `EmbeddingUnavailableException` | 不携带上游正文的服务异常 |

页面模板放在 `resources/embedding-lab/index.html`，由带 `@Profile("local")` 的 Controller 提供；脚本和样式在 `static/embedding-lab.*`。工作台顶部提供入口。普通环境下页面/API 都是 404，静态脚本和样式本身不提供模型调用能力。

## 模型配置

沿用 Starter 和全部依赖版本，没有升级模型集成框架。`application.yml` 新增：

```yaml
spring:
  ai:
    model:
      embedding: dashscope
    dashscope:
      api-key: ${DASHSCOPE_API_KEY}
      embedding:
        options:
          model: text-embedding-v4
          dimensions: 1024
          text-type: document
```

Qwen-plus 继续负责聊天；text-embedding-v4 负责生成向量，两者共用既有环境变量。配置键和自动配置路径已核对当前 1.1.2.2 的源码。

维度来自本次 `vector.length`，不会在每个请求里额外调用 `embeddingModel.dimensions()`。本章比较和小型排序都使用同角色 `document`，用于复现课程实验；正式检索阶段再区分文档 document 与查询 query。

接口允许最多 20 条候选，加上查询是 21 条输入，因此不直接照抄文章的一次大批量请求，而按最多 10 条分批（10 + 10 + 1）。批内保持输入顺序、批间依次拼接；数量不符、跨批维度不同或任意批次失败时整个实验失败，不返回部分排名。真实 20 候选调用已验证。

## 数学与错误边界

余弦关注方向，不直接判断业务条件：

```text
score = dot(left, right) / (norm(left) × norm(right))
```

数学工具拒绝 null、空数组、维度不匹配、NaN/Infinity。按文章约定，零范数在纯数学工具中返回 0；但模型返回全零向量属于无效服务输出，向量服务会拒绝，避免向用户展示一个无意义的“正常分数”。

每段文本须为非空白且最多 2000 个 Java UTF-16 字符单位；候选 1–20 条，每条单独校验。校验在首个远程请求之前完成。同分排序稳定，重复候选保留，不自动去重。

| 情况 | HTTP / 结果 |
| --- | --- |
| 正常比较 | 200，`{"dimensions":1024,"score":...}` |
| 正常排序 | 200，`{"dimensions":1024,"matches":[{"content":"...","score":...}]}` |
| 空白、过长文本，候选为空/过多/null 元素 | 400，`INVALID_INPUT` |
| JSON 缺失或格式错误 | 400，Spring MVC 请求解析错误 |
| 模型异常，向量数量/维度/数值异常 | 502，`EMBEDDING_UNAVAILABLE`，不包含上游错误详情 |
| 未启用 local 环境 | 页面和两个 API 均为 404 |

不对分数设置退款决策阈值。文本相似并不证明结论相同，精确标识符仍由订单工具/数据库精确查询。

## IDEA 和页面使用

共享 `.run/CloudCustomerServiceApplication.run.xml` 的程序参数：

```text
--app.ai.log-payload=true --spring.profiles.active=local
```

启动后访问 <http://localhost:18080/internal/embedding-lab>。第一种模式支持四组对照预填、自定义两段文字、分数刻度与摘要 JSON；第二种模式支持每行一条候选、实际分数降序展示。运行时禁用输入和切换，避免重复提交；出现错误时清除旧结果并提供明确提示。

终端启动：

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
SPRING_PROFILES_ACTIVE=local ./mvnw spring-boot:run
```

不在命令中填写已配置的真实 Key。手动新建 IDEA 配置时也需加 local 参数；禁用 local 后客服仍可用，但点击实验入口会得到 404。

页面中的候选政策是课程样例，没有注入客服 Prompt，也不代表商城真实规则。刷新会回到初始样例，不保存输入、结果或向量。每次运行消耗真实向量 API 额度；服务重试、候选数量和文本长度会影响调用成本。

## 日志

IDEA 搜索：

```text
[EMBEDDING RESULT] texts=21 batches=3 dimensions=1024 elapsedMs=...
[EMBEDDING ERROR] texts=2 errorType=...
```

只记录数量、批数、实际维度、耗时和异常类型，不输出原文、float[] 或凭证。日志为 INFO/WARN 元数据，独立于聊天的 `app.ai.log-payload` 开关。

核对 SDK 源码发现当前 `DashScopeEmbeddingModel` 异常路径会输出原始输入，因此配置该类日志为 OFF，由本项目服务记录安全摘要。这不关闭原有聊天/工具日志，也不是开启 HTTP 报文日志。

## 实际验收（2026-09-13）

- 71 项自动测试、Maven package 通过，JavaScript 语法与 Git diff 检查通过。
- 本章增加 15 项测试：数学 2 项、语义服务 8 项、local API 4 项、非 local 环境隔离 1 项。测试替换 EmbeddingModel，无需真实凭证或网络；原有 56 项回归保留。
- IDEA 实际启动：Java 17.0.20.1，PID 1910，17:39:45 显示 Tomcat 18080 与 `Started CloudCustomerServiceApplication`，活动 profile 为 local。此前旧 Debug 进程占用端口，释放后启动成功。
- 真实模型执行 5 组比较、4 候选排序、20 候选分批排序；返回维度均为 1024。另验证空白输入 400，以及原会话聊天 HTTP 200。
- 浏览器实际点击比较和排序成功，正确显示返回分数；21 条候选在前端被拦截。桌面、390px/320px 布局已检查，窄屏无横向溢出；检查实验室与工作台双向入口。没有在真实手机设备验收。
- 详细输入和响应：[06-live-observations.json](06-live-observations.json)。只保存合成实验摘要，不提交向量或运行日志。

| 实验 | 本次实际分数 / 结果 |
| --- | --- |
| 我要申请退货 / 东西买错了，我不想要了 | 0.6129519164 |
| 我要申请退货 / 今天天气怎么样 | 0.2998217676 |
| 商品支持七日无理由退货 / 商品不支持七日无理由退货 | 0.8671851794 |
| 订单 A10001 / 订单 A10002 | 0.9710690464 |
| 测试文本 / 测试文本 | 1.0，实际维度 1024 |
| 衣服尺寸不对，想寄回去 / 四条政策候选 | 退货政策第一，0.6131051028 |
| 同查询 / 20 条候选（四条重复五次） | 20 条完整返回，退货政策位于前列 |

20 候选是批次/排序管道验证，不是 20 个独立业务问题的效果评测。不同批次的同文本分数可有微小变化，不将样例数值写成固定验收阈值。

实验六用相同“测试文本”的实际向量长度验证 1024；单文本 `TextEmbeddingService.embed(String)` 入口另有离线测试。没有向前端新增返回完整数组的接口。

实验七（维度变化）：1024 与 512 维不能直接做余弦比较；模型或维度改变后应对原文重新向量化并重建索引。同样维度也不保证不同模型的语义空间一致。当前没有向量库，因此本章不会迁移或重建任何数据库。

## 后续边界

本章每次请求都重新计算全部输入的向量，只适合少量本地实验；没有缓存、持久化、索引、Top K 截断、租户过滤、文档切分或 RAG。`local` 是环境开关而非认证机制，不应把实验接口直接用作公开服务。后续章节再处理知识库建设和基于资料回答。
