# 数据库迁移代码说明

`migration/V1__create_knowledge_vector_store.sql` 已在现有数据库执行。Flyway 会校验历史文件内容，直接增加 SQL 注释也会改变校验和。因此此次注释工作保留迁移原文件，将逐项说明放在这里；未来变更表结构请新增迁移版本。

| SQL 对象 | 中文说明 |
| --- | --- |
| `CREATE EXTENSION ... vector` | 安装 pgvector 扩展，提供向量类型、距离运算和索引。 |
| `hstore` | 建立兼容 Spring AI 存储环境的扩展；本表元数据实际使用 JSON。 |
| `uuid-ossp` | 提供 `uuid_generate_v4()`，在调用方未指定 ID 时生成 UUID。上传流程通常由 Java 提供稳定 ID。 |
| `CREATE SCHEMA ... ai` | 将知识表放入独立命名空间，配置中的 schema-name 与此一致。 |
| `id uuid ... PRIMARY KEY` | 每个知识块的唯一键；upsert 用该键定位重复块。 |
| `content text NOT NULL` | 保存真正交给向量模型和 RAG 的知识块正文。 |
| `metadata json ...` | 存储租户、知识库、语言、发布状态、来源、版本、页码、块号与哈希等信息。默认空对象不意味着满足检索过滤。 |
| `embedding vector(1024)` | 固定 1024 维且不允许为空，与 text-embedding-v4 适配器一致。更换维度需要迁移并重建向量，不能只改配置。 |
| `USING hnsw (embedding vector_cosine_ops)` | 构建余弦距离 HNSW 近似近邻索引，以索引空间和构建成本换取检索速度；不保证查询能返回全文或业务上完整的规则。 |

检索由 `KnowledgeSearchService` 构造范围过滤并复核元数据；整来源发布由 `KnowledgeBatchWriter.replace` 在短事务内执行 upsert 和旧块清理。数据库列和索引本身不代替应用侧的租户校验。


## V2：原件与来源目录索引

`V2__knowledge_originals.sql` 新建 `ai.knowledge_originals`，以租户和来源 ID 为联合主键，保存文件名、格式、SHA-256、原始字节、Reader 提取正文及首次/最近上传时间。`KnowledgeBatchWriter` 在向量发布事务中同步 upsert 原件；任一写入失败整体回滚。更新保留首次上传时间。没有原件的程序化导入清理该来源旧原件，避免新知识对应旧文件。

来源目录索引用于租户、知识库、语言、来源筛选；原件下载仍须验证同一向量来源属于当前库。回收站不删除表数据，仅把当前来源知识块 metadata 的 `PUBLISHED` 改为 `ARCHIVED`；恢复执行反向变更。与同名导入共用事务级来源锁。历史资料无原件，不回填伪造字节或时间。

## V3：基础评测持久化

`V3__knowledge_evaluations.sql` 新建 `ai.knowledge_evaluation_runs`，保存租户、题集标题、输入 JSON、每题结果 JSON、运行状态和时间。结果是本次回答/来源快照，之后文档归档或重新导入不会改写它。

单实例后台队列最多一个执行、两个等待，每题结束更新结果；异常/不可用不会计作正确拒答。进程重启把遗留 `QUEUED/RUNNING` 标为 `INTERRUPTED` 并保留已完成题目，用户可复制题集重新运行。不是多实例任务调度器。
