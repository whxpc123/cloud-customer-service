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
