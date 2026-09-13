# 第八章：文档 ETL、Token 切分与预览导入

本章将第七章的固定字符切块升级为 `DocumentReader → 清理 → TokenTextSplitter → 预览 → 后台 Embedding → 短事务写入`。继续使用同一个 Spring Boot 项目、知识库页面和 `DASHSCOPE_API_KEY`。

## 在 IDEA 体验

启动 Docker 和 `./scripts/start-knowledge-db.sh`，同步 Maven 后运行共享的 `CloudCustomerServiceApplication` 配置（Java 17、`local,knowledge`）。打开 <http://localhost:18080/internal/knowledge>。

1. 点击“预览第八章售后制度样例”，或上传文件 / 粘贴正文。
2. 填写资料名称和业务版本，选择 200、500 或 1000 Token；默认 500。
3. 点击“清理并预览切分”，查看块数、字符数、质量提示、正文、标题、页码、Hash 和完整 Metadata。
4. 核对条件与例外没有丢失后，点击“确认这些知识块并导入”。页面展示后台任务进度，成功后可搜索。
5. 修改正文、名称、版本或切分参数会让当前预览失效；需要重新预览。预览不调用模型、不写数据库，确认导入才调用百炼。

课程制度在 [`refund-policy-v3.2.md`](../../src/main/resources/knowledge/refund-policy-v3.2.md)。它包含七日无理由退货、退货运费、特殊商品与退款时效四节，属于演示资料，不代表真实商城政策。

## Java 代码入口

代码位于 `src/main/java/com/example/cloudcustomerservice/knowledge/ingestion/`：

| 类 | 职责 |
| --- | --- |
| `KnowledgeSource` | 资料名称、业务版本、文件信息；服务端固定租户与来源 ID |
| `KnowledgeDocumentReaderFactory` | 选择 Reader、校验文件、限制提取规模 |
| `KnowledgePreparationService` | 保守清理、Token 切分、质量检查、Metadata 和稳定 ID |
| `ChunkingOptions` | 200 / 500 / 1000 Token 与 PDF 页首 / 页尾清理选项 |
| `PreparedKnowledge` | 供预览与正式导入共同使用的已处理内容 |
| `KnowledgePreviewService` | 有界预览缓存、确认令牌和异步导入任务 |
| `KnowledgeBatchWriter` | 已生成向量的 SQL 批量写入、同名旧块清理与回滚 |
| `KnowledgeEtlController` | 预览、确认、任务查询 HTTP 入口 |

`CustomKnowledgeImportService` 在事务外调用 `KnowledgeEmbeddingModel`，每批最多 10 条，验证数量、维度和数值后才交给 Writer。查询继续使用 `PgVectorStore` 的相似度检索及服务端过滤。

## 读取与清理

| 输入 | Reader | 当前行为 |
| --- | --- | --- |
| 粘贴正文 / TXT | TextReader（粘贴直接构造 Document） | 严格 UTF-8、保留段落，清理 BOM / 不间断空格 / 冗余空白 |
| Markdown | MarkdownDocumentReader | 保留标题，标题同时加入每块正文；横线可分段，代码块排除 |
| PDF | PagePdfDocumentReader | 每页一个原始 Document，保留页码；可选删除每页开头 / 末尾 0–5 行 |
| DOCX / PPTX | TikaDocumentReader | 提取正文；不承诺 Word 页码、幻灯片结构或复杂表格布局 |

只接受上传的内存资源，不接受客户端 URL 或服务器文件路径。单文件最多 5 MB，PDF 最多 100 页，提取正文最多 50000 个 Java 字符单位，最终最多 2000 块。Office 另限制内部条目不超过 2000、展开总量不超过 20 MB。加密 PDF、空正文、NUL、累计超过 20 个 Unicode 替换字符会被拒绝。扫描 PDF 提示先 OCR。

为保留前一版本的简短 FAQ，少于 40 字符的块、总正文少于 100 字符的资料给出提示，允许人工核对后导入；没有照抄文章中直接丢弃或拒绝短内容的示例。PDF 页眉默认不删，避免误删业务条件。

## Token、标题与稳定 ID

500 Token 不等于 500 汉字。使用 Spring AI 1.1.2 的 `TokenTextSplitter`，`minChunkSizeChars=180`、`keepSeparator=true`、`minChunkLengthToEmbed=0`；分块后自行检查质量和全局块数。当前无 overlap。

该版本默认切分在中文 / emoji 的 Token 边界可能引入替换字符，也可能提前截断或合并尾部；实现会核对非空白正文完整性与 Token 大小，必要时按完整 Unicode 字符重新寻找 Token 边界并显示提示。Token 计数使用 CL100K_BASE，属于本地切分尺度，不等同于百炼账单 Token 数；标题在切分后补入正文，所以最终块可能略超过目标大小。

同一租户内，资料名称生成稳定 `sourceId`，沿用第七章真实导入的命名规则。`sourceVersion` 表示业务版本；`chunkingVersion` 例如 `token-500-v1-top0-bottom0` 表示切分参数。

```text
chunkHash = SHA256(最终嵌入正文，含标题)
documentId = UUID(tenantId | sourceId | sourceVersion | chunkingVersion | chunkIndex | chunkHash)
```

`chunkIndex` 从 1 开始。Metadata 保留来源、标题、页码（存在时）、原始段落索引、业务版本、切分版本、Hash、Embedding 模型 / 维度 / profile、语言与发布状态。只继承清理后的来源 Metadata，排除 Spring Splitter 自动生成的随机 `parent_document_id` 及重复的零起始索引。

## 预览、后台导入与版本替换

预览令牌由服务器生成。确认接口只接收令牌，使用服务器缓存的相同知识块，客户端不能提交篡改后的正文或 Metadata。

- 最多 32 个预览，15 分钟有效；不保留原文件字节。
- 单后台线程，等待队列 4 个，满载返回 429。
- 状态为 `QUEUED → EMBEDDING → PUBLISHED`，异常为 `FAILED`；失败可重试，同一预览重复提交返回相同的非失败任务。
- 完成后任务状态再保留 15 分钟；运行中的任务不会被 TTL 清除。
- 浏览器失去连接时可重新查询任务，状态未确认前锁定新的导入操作。
- 缓存与队列仅在内存中。应用重启后丢失预览 / 任务状态，但已提交的数据库知识保留；未完成的任务不能恢复。

Embedding 在数据库事务外执行。向量全部生成后，短事务取得来源锁、批量 upsert、删除同名资料的旧块；写入或清理失败整体回滚。未预先删除旧知识，不会在远程调用期间占住数据库事务。

同名导入替换所有旧业务版本与切分版本；当前不是多版本档案库。提交顺序决定最终版本，尚无版本号大小比较 / 防过期发布功能。不同名称被视为不同来源，不自动去重。第七章既有资料只有重新导入才会切换到本章切分方式。

这是一套本地演示的预览确认流程，不是持久化任务系统或生产 `STAGED / PUBLISHED / ARCHIVED` 审核发布平台。预览中的 `status=PUBLISHED` 是确认后将写入的检索元数据，预览本身尚未发布。

## 接口

全部位于 `/internal/knowledge`，仅在 `local & knowledge` 生效；Profile 是环境开关，不是生产鉴权。

| 方法和路径 | 输入 / 返回 |
| --- | --- |
| POST `/preview` | JSON：`sourceName, sourceVersion, text, chunkSize`，返回完整预览 |
| POST `/preview/file` | multipart：`file`、可选名称 / 版本 / chunkSize / pdfTopLines / pdfBottomLines |
| GET `/files/refund-policy/preview?chunkSize=500` | 预览内置课程 Markdown |
| POST `/previews/{previewId}/import` | 无须正文；202 返回 jobId 与状态 |
| GET `/jobs/{jobId}` | 查询进度与成功结果 |
| POST `/search` | 沿用 query / topK / threshold，结果增加来源 Metadata |

旧 `/import` 和 `/import/file` 同步接口保留兼容，内部也使用新 ETL，但跳过人工预览且调用线程会等待模型。新页面使用异步确认接口。输入问题返回 400，超大请求 413，过期 / 不存在预览 404，缓存或队列满 429。异步失败通过任务状态返回。

IDEA 日志可搜索 `[KNOWLEDGE EMBEDDING] role=document`、`[KNOWLEDGE IMPORT] mode=etl` 和 `[KNOWLEDGE SEARCH]`；不会打印 API Key 或完整向量。预览中可以直接看处理后正文。

## 验证与边界

执行 `RUN_PGVECTOR_TESTS=true JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home ./mvnw package`：100 项测试全部通过，0 失败 / 0 错误 / 0 跳过。普通无数据库测试运行会跳过 7 项集成测试。

测试涵盖真实生成的 DOCX / PPTX / 两页 PDF、可选页眉删除、正文清理、短 FAQ、全局块数、乱码与空输入、中文 emoji 保真、稳定 ID / Metadata、预览不调用模型、重复提交与过期、数据库回滚及 Embedding 时没有活动事务。集成测试只使用专用 `cloud_customer_service_test`，不清理演示库。

真实运行观察见 [08-live-observations.json](08-live-observations.json)。

- IDEA 在 2026-09-13 23:00 使用 Java 17、local / knowledge 启动本章代码，PID 26853。后续元数据修订重新运行时，IDEA 残留的停止确认对话框无法通过 UI 工具操作，不能把这一轮重启说成成功。旧实例已停止；最终 JAR 已从终端启动并提供 18080 页面。共享 IDEA 配置及依赖同步已经验证，完成 IDE 对话框处理后可停止终端实例再用同一配置运行。
- 实际预览前后数据库均为 8 行；课程文档导入 4 块、1024 维，数据库正文与预览逐块相同；重复导入的 ID、正文和 Metadata 一致。
- 长中文 / emoji 文本用 200 / 500 / 1000 Token 得到 18 / 8 / 4 块，拼接后非空白字符完整。课程四节都很短，所以课程文件在这些参数下可能一直为 4 块，不能用它单独比较切分大小。
- 在独立测试来源将“七日”改为“十五日”并提升版本，Hash / ID 改变、旧 ID 消失，数据库只保留新块；验收后仅清理该临时来源。
- 五个真实查询中四个在 0.60 阈值有结果；“软件激活后能退吗”默认无结果，降到 0 后第一条为“特殊商品”，约 0.5786。原来的四条课程知识仍在检索范围内。未校准阈值，也未计算全量召回率。
- 浏览器完成 DOCX / PPTX 上传预览、无文字 PDF 错误、展开 Metadata、改参数失效、确认后台导入及退款时效检索。390 / 320px 检查的文档宽度均未超出视口；控制台无页面脚本错误。

本章验证技术完整性和检索片段，不宣称已经验证回答质量；RAG、OCR、Excel FAQ、复杂表格解析、生产鉴权、耐久任务与大规模性能留待后续工作。
