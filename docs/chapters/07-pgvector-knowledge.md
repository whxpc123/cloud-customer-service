# 第七章：PgVectorStore 与持久化知识检索

本章根据用户提供的《第七章：八万条知识，该把这些向量放在哪里？》实现。版本标签 `chapter-07`，上一版本 `chapter-06`。继续使用 Java 17、Spring AI 1.1.2 和环境变量 `DASHSCOPE_API_KEY`。

> 后续补充：自有 TXT / Markdown / PDF 上传与正文导入已实现，见 [真实导入说明](07-real-document-import.md)。下文保留原 chapter-07 的验收记录。

## 现在可以做什么

打开 <http://localhost:18080/internal/knowledge>，手动导入四条课程知识，输入问题，查看按分数排列的正文、类别、来源文件 ID、版本、块编号和原始 JSON。可以调整 Top K 和阈值；没有满足条件的知识时真实返回空列表。聊天工作台、语义实验室与知识库页面可以相互跳转。

```text
手动导入四个 Document
  → 正文 Embedding（document，1024 维）
  → PgVectorStore.add
  → PostgreSQL：id + content + metadata + embedding

用户问题
  → Embedding（query，1024 维）
  → PgVectorStore.similaritySearch
  → SQL 元数据过滤 + 余弦距离 + 阈值 + Top K
  → 知识片段、分数与来源
```

本章没有调用 ChatModel 生成检索答案，没有将政策加入客服 Prompt。四条政策是文章中的课程样例，不代表真实商城承诺；没有导入 82,437 条知识。

## 启动与持久化

需要 Java 17、Docker Desktop、Python 3，以及有效的 `DASHSCOPE_API_KEY`。在项目根目录执行：

```bash
./scripts/start-knowledge-db.sh
docker compose ps
```

脚本首次生成随机密码，保存于 `.local/postgres.env` 和 `.local/database.properties`，文件权限 600，目录创建权限 700。这些文件被 Git 忽略，不回显密码。再次启动保留原配置；一份配置缺失时拒绝覆盖。不要在保留数据库卷时删除密码文件，新生成的密码不会自动修改既有 PostgreSQL 用户的密码。

数据库只映射到 `127.0.0.1:15432`，库名 `cloud_customer_service`、用户 `cloud_ai`。固定镜像摘要对应 PostgreSQL 17.10 / pgvector 0.8.2，数据保存在 Compose 命名卷 `cloud-customer-service_cloud_customer_pgdata`。`docker compose stop` / `start` 和普通 `down` 保留卷；`down -v` 会删除数据库数据，不用于正常停止。

IDEA 导入 Maven 后选择共享的 `CloudCustomerServiceApplication` 配置，工作目录为项目目录，JDK 17。配置已启用 `local,knowledge`，直接运行。首次启动由 Flyway 执行 V1，之后验证版本并保留知识。

终端启动：

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
export PATH="$JAVA_HOME/bin:$PATH"
# 使用已设置的 DASHSCOPE_API_KEY，不需要换变量名。
SPRING_PROFILES_ACTIVE=local,knowledge ./mvnw spring-boot:run \
  -Dspring-boot.run.jvmArguments='-DsocksNonProxyHosts=localhost|127.*|[::1]'
```

JAR 启动：

```bash
java '-DsocksNonProxyHosts=localhost|127.*|[::1]' \
  -jar target/cloud-customer-service-0.0.1-SNAPSHOT.jar \
  --spring.profiles.active=local,knowledge
```

本机 SOCKS 配置曾导致 PgJDBC 回环主机解析失败，出现 `UnknownHostException: 127.0.0.1`。上述 JVM 参数只为应用的本地连接指定代理绕过，IDEA 与 Maven Surefire 已配置；未修改全局代理。

前六章单独运行：使用 `local` profile，或不开启 profile。默认配置排除 DataSource / Flyway / PgVector 自动配置，因此无需数据库；第七章页面及 API 只有同时启用 `local` 和 `knowledge` 才注册。环境开关不是认证，演示接口不应作为公开管理 API 使用。

远程/自管数据库可通过 `KNOWLEDGE_DB_URL`、`KNOWLEDGE_DB_USER`、`KNOWLEDGE_DB_PASSWORD` 覆盖。Docker 启动脚本管理的是本地数据库；修改应用密码变量不会修改 Docker 已初始化的数据库密码。

## 核心文件和选择

| 文件 | 职责 |
| --- | --- |
| `compose.yml`、`scripts/start-knowledge-db.sh` | 固定版本数据库、持久化卷、本地密码 |
| `application-knowledge.yml` | 数据源、Flyway 和 VectorStore 配置 |
| `V1__create_knowledge_vector_store.sql` | vector / hstore / uuid-ossp 扩展、ai schema、vector(1024) 表、HNSW 余弦索引 |
| `KnowledgeVectorConfiguration` | 等待 Flyway，再创建并验证 PgVectorStore |
| `KnowledgeEmbeddingModel` | 区分 document/query，仅嵌入正文，每批最多 10 条，校验并按返回索引重排 |
| `LocalKnowledgeDocuments` | 四条正文，稳定 UUID 和完整元数据 |
| `LocalKnowledgeImportService` | 调用 VectorStore.add，重复导入 upsert |
| `KnowledgeSearchService` | 输入边界、过滤条件、相似度检索及来源映射 |
| `LocalKnowledge*Controller`、`KnowledgeErrors` | 本地接口、页面及错误响应 |
| `knowledge-lab/index.html`、`static/knowledge-lab.*` | Spring Boot 提供的页面，无独立前端服务 |

ID 由 `tenantId|sourceId|sourceVersion|chunkIndex` 确定。元数据包含租户、知识库、发布状态、语言、来源、版本、块序号、类别、模型、维度、嵌入配置版本和切分版本。

搜索强制过滤 `tenant-yunshan + PUBLISHED + after-sales + zh-CN`，数据库返回后再次检查范围。当前租户由服务端常量决定，正文/请求头传入的租户不会覆盖它；未实现真实登录认证。新版本块有新 ID，旧版本的发布与下线仍需后续生命周期管理，本章没有自动清理旧版本。

与文章示例相比：数据库单独使用 knowledge profile；端口为本机 15432；密码随机生成；批次为 10；知识库明确采用 document/query 不同角色。保留第六章同角色相似度实验，不注册第二个 EmbeddingModel Bean。

另外，没有使用先 delete 再 add：已检查 Spring AI 1.1.2 PgVectorStore 的 add 会先生成向量，再用 `ON CONFLICT UPDATE` 写入。嵌入服务失败时保留旧知识。它不是跨批次原子发布方案，尚未提供生产级版本切换事务。

## API 和日志

```http
POST /internal/knowledge/seed
```

返回 `{"importedDocuments":4}`，表示本次写入/更新数量，并非累加数量。每次点击会重新调用向量服务。

```http
POST /internal/knowledge/search
Content-Type: application/json

{"query":"电子发票去哪里申请？","topK":5,"threshold":0.6}
```

返回 `query` 与 `hits`。每个 hit 包含 `documentId/content/score/sourceId/sourceVersion/chunkIndex/category`。`score` 来自 PgVectorStore 的 `1 - cosine_distance`，不是正确概率。接口不返回完整向量。

query 为 1–2000 个 Java UTF-16 字符单位且不能全空白；topK 为 1–10，默认 5；threshold 为有限的 0–1 数值，默认 0.60。非法业务输入返回 400 / INVALID_INPUT；非法 JSON 由 MVC 返回 400；数据库或模型调用异常返回 502 / KNOWLEDGE_UNAVAILABLE，不暴露上游异常详情。真实接口调用会消耗百炼额度。

IDEA 可搜索 `[KNOWLEDGE IMPORT]`、`[KNOWLEDGE SEARCH]`、`[KNOWLEDGE EMBEDDING]`、`[KNOWLEDGE ERROR]`，只记录数量、角色、维度、阈值及异常类型，不记录密码、完整向量或知识正文。原有聊天提示词和模型回复日志继续使用原开关。

## 验证与复现

2026-09-13 的完整 `RUN_PGVECTOR_TESTS=true ./mvnw package` 已通过 85 项测试，零失败、零错误、零跳过。Java 源码验证后只调整了页面 CSS，另执行跳过测试的打包刷新静态资源。自动测试中的模型均为替身，不访问百炼。

普通 `./mvnw test` 共 85 项，其中 81 项运行、4 项集成测试跳过。运行真实数据库集成测试时，先启动数据库，再检查并按需创建专用测试库：

```bash
./scripts/start-knowledge-db.sh
docker compose exec -T postgres psql -U cloud_ai -d postgres \
  -tAc "SELECT datname FROM pg_database WHERE datname = 'cloud_customer_service_test'"
# 上一条无结果时才创建；已经存在则跳过本条。
docker compose exec -T postgres createdb -U cloud_ai cloud_customer_service_test
RUN_PGVECTOR_TESTS=true ./mvnw package
```

`PgVectorPersistenceTest` 固定连接 `cloud_customer_service_test`，只清理此库的知识表，并校验当前库名。它覆盖真实表/索引/维度、重复导入、四种元数据过滤、Top K/阈值、搜索只向量化问题、重新导入嵌入失败时保留旧行。普通单元测试另覆盖输入与非法模型结果，环境测试验证未启用时为 404。

IDEA 实际运行 Java 17 / local,knowledge / 18080，并用真实 text-embedding-v4 完成以下实验；记录见 [07-live-observations.json](07-live-observations.json)。

| 实验 | 实际结果 |
| --- | --- |
| 空库检索 | 200，空 hits |
| 连续导入两次 | 两次成功，SQL 均为 4 行，向量 1024 维 |
| 退款问题，默认 0.60 | 空 hits |
| 退款问题，阈值 0 | 运费约 0.5606、退货条件约 0.5602，之后为物流、发票 |
| 物流问题，默认 0.60 | LOGISTICS_EXCEPTION，约 0.6653 |
| 发票问题，默认 0.60 | INVOICE_POLICY，约 0.6409 |
| 退款 Top K = 1 / 3 / 10，阈值 0 | 1 / 3 / 4 条 |
| 退款阈值 0 / 0.5 / 0.7 / 0.9 | 4 / 2 / 0 / 0 条 |
| 修改样例租户 / 状态为 DRAFT | 分别被排除，验证后恢复原元数据 |
| 空问题 | 400 |
| 重启数据库容器与 IDEA 应用 | 4 行内容/元数据/向量指纹一致，未重导即能检索发票 |
| 第六章比较 / 原会话聊天 | 真实请求均 200 |

浏览器验证导入、空结果、阈值调整、结果来源与 JSON，以及三个页面之间导航。检查桌面、390px 与 320px 视口；320px 三个页面均无横向溢出。这是浏览器窄屏模拟，没有真机验收。最后补查时浏览器控制工具提示 Codex auth token is unavailable，未能追加读取控制台日志；此前交互验收已完成，IDEA 运行状态另行确认。

## 解释边界

- 0.60 是待评测的起始阈值，实际退款问题会漏召回；降低阈值增加召回，也会带入无关知识。不能凭四个样例选择生产阈值。
- 已验证 HNSW 索引存在，不代表四行查询一定使用该索引；查询规划器可能选择顺序扫描。未进行八万条规模、并发、召回率或延迟基准测试。
- 修改模型、维度或嵌入方式后应规划新表/索引与重新嵌入，不能混用不兼容向量。当前固定使用 v4 / 1024。
- 本地数据库用户可建扩展，正式部署应由平台预装扩展并收紧应用权限。备份恢复、版本治理和认证尚未实现。
- 文档读取、自动切分、RAG、重排与混合检索不在本章范围。
