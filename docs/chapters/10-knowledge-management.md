# 第十章补充：知识库管理台与基础评测

这次按文档管理、知识问答和评测分析三个参考页面，在既有 Spring Boot 应用内增加管理界面。用户确认本次评测范围为自建测试题、引用命中、拒答检查和耗时，不推进第十一章。

## 启动和入口

继续使用 Java 17、`DASHSCOPE_API_KEY` 环境变量和 `local,knowledge` profiles。先运行 `./scripts/start-knowledge-db.sh`，再从 IDEA 重启 `CloudCustomerServiceApplication`。默认管理入口为 <http://127.0.0.1:18080/internal/knowledge-admin>，客服工作台、第九章和第十章页面均增加入口。

本次实际验收的新版 JAR 从终端运行于 **18082**，因为 IDEA 原生窗口自动重启操作无效并报告 `noWindowsAvailable`；原 IDEA 18080 进程仍运行旧版，不将终端启动记作 IDEA 启动通过。可直接使用 <http://127.0.0.1:18082/internal/knowledge-admin>，IDEA 成功重启后再用 18080。

## 页面和数据流

1. **概览与文档管理**：真实数据库来源数量、已发布/回收站数量；按名称/来源 ID、状态、格式搜索，服务器分页。文档详情包括版本、格式、原始字节大小、哈希、时间、全文预览及全部切片，切片可展开查看元数据。
2. **导入**：支持 TXT、Markdown、PDF、DOCX、PPTX 和粘贴正文，复用第八章 Reader、切分、预览与后台导入。原始字节与 Reader 提取正文在切分前保存，在向量发布短事务中同时写入。失败保留旧版；同名重新导入会整体替换并发布。
3. **原件与回收站**：新文档可以精确下载原文件。旧文档只有已存知识块，详情标注“历史知识块合并预览”，可导出块正文；原始文件、原排版、上传时间不能从切片反推。回收站只更新发布状态，恢复不重新调用 Embedding；混合状态/草稿不能通过恢复变成已发布。
4. **知识问答**：会话列表、新建/切换、演示身份、清空记忆、每轮实际检索来源和接口 JSON。复用第十章 Advisor 链，仍以当前问题检索，没有 Query Rewrite、流式输出或订单工具混合路由。
5. **评测分析**：填写运行名称和 1–20 道问题、无证据拒答期望、可选来源 ID 列表。一次一个题集顺序执行，每题使用独立会话，完成后清空。输入及结果保存到 PostgreSQL；刷新可继续查看，支持复制题集、展开结果、仅看差异/失败和最近 50 次历史。

前端安全 Markdown 子集只支持文字排版、表格、列表、粗体和代码；资料中的 HTML、脚本或图片不会执行。PDF/Office 显示 Reader 提取正文，可下载原件核对排版，不声称原生还原 Office 页面。

## 基础指标的精确定义

| 指标 | 实现含义 | 分母及边界 |
| --- | --- | --- |
| 期望来源命中率 | 返回的实际 `references` 至少包含一个配置的来源 ID | 只计算有期望来源、执行成功的题；未配置或服务失败不计分。不是逐句引用正确率。 |
| 无证据拒答匹配率 | `status == NO_EVIDENCE` 是否等于题目的 `shouldRefuse` | 服务异常/不可用不算正确拒答；模型自然语言说“无法回答”但状态为 ANSWERED，仍算非 NO_EVIDENCE。 |
| 平均整轮耗时 | 每题从问答执行开始到记忆清理结束的毫秒数，再取平均 | 只对成功执行题求平均，包含检索、生成和清理，不是首 Token 延迟；不含排队时间。 |
| 执行失败 | 问答异常或 TEMPORARILY_UNAVAILABLE 的已保存题数 | 单列失败，不把失败从页面隐藏或误算为拒答成功。 |

没有实现忠实度、回答相关性、上下文精确率/召回率或 LLM 裁判。本次题集不配置标准答案，没有自然语言准确率。来源命中不能证明回答所有句子都符合资料。

## API

所有新接口在 `/internal/knowledge-admin` 下，仅启用 `local & knowledge`。固定服务器演示租户 `tenant-yunshan`、中文售后库，浏览器不能指定任意租户或 SQL。

| 方法与路径 | 用途 |
| --- | --- |
| GET `/documents?query=&status=PUBLISHED&type=ALL&page=1&pageSize=15` | 分页文档来源；状态可 ALL/PUBLISHED/ARCHIVED/OTHER |
| GET `/documents/{sourceId}` | 完整详情、预览和切片 |
| GET `/documents/{sourceId}/download` | 原始文件字节，attachment + nosniff；旧资料返回 404 |
| GET `/documents/{sourceId}/export` | 导出已入库知识块正文，不冒充原件 |
| PATCH `/documents/{sourceId}/archive` | `{"archived":true}` 归档；false 恢复 |
| POST `/evaluations` | 保存题集并后台运行，202；队列已满 429 |
| GET `/evaluations` | 最近 50 次摘要 |
| GET `/evaluations/{id}` | 持久化题集和逐题结果 |

评测请求示例（点击执行会调用真实模型）：

```json
{
  "name": "售后资料更新回归",
  "cases": [
    {"question": "因质量问题退货，运费由谁承担？", "shouldRefuse": false, "expectedSources": ["refund-policy"]},
    {"question": "你们老板最喜欢什么咖啡？", "shouldRefuse": true, "expectedSources": []}
  ]
}
```

导入仍使用 `/internal/knowledge/preview`、`/preview/file`、`/previews/{id}/import`、`/jobs/{id}`。问答仍使用 `/internal/advisor-rag/conversations`、`/{id}/messages`、`/{id}/memory`；未再增加一套客户问答协议。IDEA HTTP Client 示例见项目根目录 `requests.http`。

## 实现阅读路径

- `KnowledgeOriginal` / `PreparedKnowledge` / `KnowledgePreparationService`：原始字节、提取正文与预览载体。
- `KnowledgeBatchWriter`：向量、旧块清理、原件统一事务提交；V2 原件表新增迁移，不修改 V1。
- `KnowledgeDocumentCatalog` / `KnowledgeAdminController`：来源目录、同库范围校验、快照详情下载、归档和恢复。
- `KnowledgeEvaluationService` / `KnowledgeEvaluationController`：输入校验、有界后台队列、确定性比较、V3 运行记录和重启中断恢复。
- `knowledge-admin/index.html` / `static/knowledge-admin.js` / `.css`：hash 导航和独立页面，动态文本通过 DOM 构造，慢请求不能覆盖已切换页面。

## 验收记录 · 2026-09-14

- `RUN_PGVECTOR_TESTS=true JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home ./mvnw package`：**145 项测试，0 失败、0 错误、0 跳过**，包含 14 项真实专用 PostgreSQL 集成测试。普通测试无需真实百炼 Key；需要数据库的测试显式使用专用测试库。
- 新增测试覆盖下载原件字节一致性、元数据/目录、旧资料诚实展示、来源隔离、归档后真实向量检索排除、恢复、事务回滚和更新时间，以及评测输入校验、每题隔离、失败计分、持久化和中断恢复。无 knowledge profile 时新页面/接口不启用。
- 浏览器从上传入口导入合成 Markdown“管理台验收样例”，实际 2 个切片、487 B 原件。下载 SHA-256 与上传文件一致；预览表格/粗体正确，脚本示例仅显示为文本。原文与 Reader 切片分开保留。
- 知识问答实测“星河保温杯标签核对的观察时间”，回答三个工作日，并保留“仅适用于验收样例”的限制；返回两个真实切片。清空记忆确认、新建会话可用。
- 真实评测 `11e503b1-eac6-48b8-88d6-7f1db99c6125`：上述问题命中期望来源、1700 ms；老板咖啡问题 NO_EVIDENCE、143 ms。来源命中 1/1、拒答匹配 2/2、平均耗时四舍五入 922 ms、失败 0。筛选与复制题集通过；复制不自动运行。这个两题结果只证明本次操作流程，不是整体准确率。
- 文档名称/格式筛选、归档取消、归档确认、恢复发布、回收站列表通过。验收样例最终留在回收站，原有资料保持不变。
- 最后修改仅为前端导航收尾和排版，JS 语法检查及资源重新打包通过。重启最终 JAR 后，浏览器仍可读取两题已完成评测；已发布 5 份、回收站 1 份，页面会话记录刷新后保留。
- 桌面浏览器布局检查通过，验收时控制台无 error；未宣称真实手机验收。

## 当前限制

这是本地演示管理台，无真实登录或管理员权限体系，不应直接开放为公网管理服务。演示用户请求头不是认证。原件当前存数据库，每份限制 5 MB；大量文件需进一步设计存储、配额及生命周期。

聊天列表保存在当前浏览器标签页的 sessionStorage，最多 20 个会话；服务端模型记忆仍在内存，重启后失忆，页面记录不会自动补送。归档只影响新的检索，不能撤回已有会话记忆或评测快照里的旧证据。

评测执行器为单实例，一个执行、两个排队；重启遗留运行标为 INTERRUPTED，保留已完成题目，不自动重发收费调用。没有多实例调度、取消任务、题库文件导入或独立草稿存储，题集随运行保存，可复制修改后复测。
