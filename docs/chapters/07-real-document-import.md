# 第七章补充：真正导入 TXT、Markdown、PDF 与正文

原 `chapter-07` 的导入按钮只能写入代码中的四条课程样例，没有上传文件或接收自定义正文的入口。用户提出“知识库不能真正的导入”，并明确需要 TXT / Markdown、粘贴正文和 PDF，因此本次补齐真实导入。保留原标签，补充版本为 `chapter-07-import`。

## 使用方法

IDEA 使用原来的 `local,knowledge` 配置启动，打开 <http://localhost:18080/internal/knowledge>，刷新旧页面。

1. 在“导入自己的资料”中选择“上传文件”，选择 TXT、Markdown 或 PDF。资料名称默认使用文件名，也可以修改。
2. 或选择“粘贴正文”，填写资料名称和正文。
3. 点击“解析并导入知识”，等待显示字符数及知识块数。
4. 在下方提问，检索结果会显示上传的正文、资料名称、版本和块编号。

同一租户下，同名资料会整体替换旧内容；需要保留两份时使用不同名称。名称区分大小写，前后空白会去除，Unicode 按 NFC 规范化。文件中的指令、Markdown 链接、HTML 不会被执行。

文件最多 5 MB；PDF 最多 100 页；提取后的正文或粘贴内容最多 50000 个 UTF-16 字符单位。TXT / Markdown 需要 UTF-8。PDF 使用 Apache PDFBox 3.0.6 提取文字，不含 OCR；扫描版或空白 PDF 没有可提取文字时会提示先做 OCR。加密、损坏和不支持的格式会拒绝导入。原文件不保存，正文与元数据会保存在 PostgreSQL；向量服务继续从 `DASHSCOPE_API_KEY` 读取凭证。

## 代码与数据流程

- `KnowledgeDocumentReader` 校验大小与格式、严格解码 UTF-8、提取 PDF 文字，并限制页数与提取字符数。
- `CustomKnowledgeImportController` 提供文件和 JSON 两种入口，仅在 `local & knowledge` 注册。
- `CustomKnowledgeImportService` 将正文按最多 1000 字符分块，避免切断 UTF-16 代理对。此为简易切分，不包含标题感知、段落重叠、表格重建或 PDF 页码溯源。
- `sourceId` 根据服务端租户与资料名称生成，使用独立的 upload 命名空间，不会覆盖课程样例。`sourceVersion` 是正文 SHA-256；块 ID 根据 sourceId 和块序号生成。同内容重导不增加行数。
- 沿用 document 角色、1024 维、每批最多 10 条的向量包装器。页面不返回完整向量。
- 同名替换使用同一个数据库事务：取得按来源划分的 PostgreSQL 事务锁 → 向量化与 upsert → 删除该来源旧版本遗留块 → 提交。任一环节失败全部回滚；不同名称的资料与课程样例保留。同来源并发导入串行执行。
- 上传知识立即标记为本地云杉售后库的已发布知识；仍是演示租户，没有登录认证或发布审核。`language=zh-CN` 为固定检索标签，不进行自动语言识别。

JSON 接口：

```http
POST /internal/knowledge/import
Content-Type: application/json

{"sourceName":"积分规则","text":"会员积分每月十五号结算。"}
```

上传接口：

```bash
curl http://localhost:18080/internal/knowledge/import/file \
  -F 'file=@/绝对路径/政策.pdf' \
  -F 'sourceName=售后政策'
```

`sourceName` 在上传接口可省略，默认取文件名；名称为 1–120 字符。响应包含 `sourceId/sourceName/sourceVersion/importedDocuments/characters`。非法正文/文件返回 400，超大 multipart 返回 413，数据库或向量服务故障返回 502。页面用纯文本展示错误，不渲染上传内容中的 HTML。

## 2026-09-13 验证

`RUN_PGVECTOR_TESTS=true ./mvnw package`：**92 项全部通过**，零失败、零错误、零跳过。普通 `./mvnw test` 运行 85 项并跳过 7 项需专用 PostgreSQL 的测试。测试库仍为 `cloud_customer_service_test`，模型使用替身，不消耗百炼额度。

新增测试覆盖真实 PDF 生成与文字提取、UTF-8 / 换行 / BOM、无文字 PDF、损坏 / 空 / 二进制 / 超大文件、multipart 接口、资料名称校验、长文缩短后的旧块清理、同名重复导入、其他来源保留、写入后清理失败的完整事务回滚，以及嵌入失败保留旧内容。

IDEA 实际同步依赖并重新运行：Java 17.0.20.1、local + knowledge、端口 18080，控制台确认启动成功并记录真实 custom-import。所有真实验收资料均为自建演示内容：

| 验收项 | 结果 |
| --- | --- |
| 浏览器上传 Markdown | 40 字符 / 1 块，星河会员问题命中该文件，约 0.8187 |
| 浏览器上传文字 PDF | 71 字符 / 1 块，Moonlight 问题命中该 PDF，约 0.8118 |
| 浏览器上传 TXT | 34 字符 / 1 块，后续查询命中自提规则 |
| 浏览器粘贴与同名更新 | 32 字符改为 24 字符，查询返回“每月十五号”新内容，约 0.8144 |
| SQL 检查 | 四份自建资料各 1 行，均为 1024 维；重复导入未增行 |
| 无文字 PDF | 400，页面明确提示 OCR |
| 超过 5 MB 文件 | 实际 multipart 请求返回 413 |
| 浏览器布局和错误 | 390px / 320px 无横向溢出，未捕获到控制台 error；未做真实手机验证 |

样例分数可能随请求变化，不作为召回阈值标定。真实观察保存在 [07-import-live-observations.json](07-import-live-observations.json)。验收结束清理本次自建的四份测试资料，原课程样例和其他资料保留。

当前仍只做检索，未把知识加入客服 ChatModel；OCR、Word / Excel、复杂 ETL 和 RAG 不在本次补充范围。
