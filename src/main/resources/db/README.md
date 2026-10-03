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


## V4：编码与全文检索

`V4__hybrid_search.sql` 在写入前重建 metadata.businessCodes（CPN / SKU / POLICY 编码数组），回填现有行；STORED search_vector 生成列自动同步正文和元数据。全文 tsvector 与编码 JSON 分别用 GIN 索引。不改向量维度、不重新调用模型，归档/恢复和 upsert 继续走同一事务。

精确查询使用 JSON 数组包含关系，全文查询采用 simple / websearch_to_tsquery / ts_rank_cd。两者都执行租户、发布状态、知识库、语言限制。simple 不是中文分词器，ts_rank_cd 不等于 BM25；多版本同时发布时不按字符串猜测最新有效规则。首次回填建索引会占用数据库资源，大库需维护窗口。详见第十四章文档。


## V5：接待状态与正式消息

`V5__human_handoff.sql` 增加 `ai.cs_conversation` 与 `ai.cs_message`，不修改 V1～V4。前者以租户/会话为主键，保存客户、受理号、接待模式、版本、领取客服与阶段时间；CHECK 约束拒绝模式和字段互相矛盾的行。受理号唯一，等待队列通过租户与 WAITING_HUMAN 部分索引查询，不维护另一份队列状态。

`HumanHandoffService` 的短事务使用 SELECT FOR UPDATE 串行化申请、领取、结束、用户消息接收和机器人正式发布。变更与 SYSTEM 消息一起提交；消息写入失败会回滚状态变更。模型调用位于事务外，发布时复核 BOT、version 和 generation_id。交接清理租约，迟到的候选答案不保存、不返回。

消息保存原文、角色、作者、接收版本、时间，以及正式机器人消息的完整 JSON 业务证据；按游标每页最多 100 条。client_message_id 对每个会话唯一，重复请求不会重复追加或重新生成。generation_started_at 为异常中断提供五分钟租约过期恢复，不是排队等待时间。memory_after 只重置模型读取窗口，永不删除正式记录或恢复机器人接待。

两张表与知识向量、原件及评测完全独立；它们是 PostgreSQL 普通业务表，不将聊天记录自动导入向量知识库。登录 Session 仍在进程内，重启后重新登录才能按同一账户恢复数据库记录。

## V6：业务任务与 Graph 检查点

`V6__persistent_draft_tasks.sql` 新建 `ai.cs_draft_task`，保存客户/租户/正式会话、内部 threadId、固定订单和原因、契约版本、状态、CAS 版本、轮次、runId、最近正常 checkpointId 和结果快照 JSON。外键关联既有正式会话，结果和检查点关联必须同时为空或非空。任务不会自动写成正式聊天消息或知识向量。

`DraftTaskRepository` 的短事务通过 expectedVersion + 正常状态 + 契约 + 会话版本领取一轮；正常完成再次条件更新结果和版本。Graph 的写入在另一个连接里提交，两者不是原子事务。无法确认完成时保存 RECOVERY_REQUIRED，标记失败或进程退出则保留 RUNNING；本章没有启动时重置/接管。查询只读业务结果快照，结束保留数据库历史。

同时创建与 Graph Core 1.1.2.2 PostgresSaver 实际 SQL 一致的 `public.graphthread` / `public.graphcheckpoint`。配置 threadId 对应 thread_name，表内 thread_id 为内部 UUID。state_data 是包裹 Base64 binaryPayload 的 JSONB，搭配 state_content_type 由默认 Jackson 状态序列化器还原类型；不等于明文消息数组，更不是加密。非 released 的 thread_name 有唯一索引；release 标记线程生命周期，不当成关闭连接使用。

表由 Flyway 建立，保存器建表/删表开关均关闭。保存器连接信息来自应用同一 JdbcConnectionDetails，但官方 Builder 自建连接，不复用 Hikari 设置；当前适配器拒绝无法保留的 JDBC URL 参数，只验证本地单 PostgreSQL 地址。恢复前后都用新保存器核对数据库，不能只信 MemorySaver 基类缓存。详细恢复契约及测试见第二十二章文档。V1～V5 内容不变。
