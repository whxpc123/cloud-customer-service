# 第二十五章：用可执行 Graph 定义售后分支

入口：<http://127.0.0.1:18080/internal/draft-tasks/flow>。保留第 24 章审批页和第 23 章草稿页，并增加相互跳转。

## 本章完成的范围

新增真实 Spring AI Alibaba `StateGraph`：检查草稿 → 检查操作审批 → 模拟执行或停止。`BusinessSteps` 提供三项业务能力，节点返回增量，条件边选择路线，业务状态与框架 END 分开。

页面是**独立、确定性的路线实验**：服务器预设八组夹具，Graph 真正执行，页面显示实际轨迹与调用计数。夹具中的任务和审批 UUID 都是虚构标识，不读取真实数据库草稿、不批准真实操作、不调用模型、不创建售后申请、不执行退款。浏览器选择“批准”场景仅改变测试条件，不构成业务授权。

第 24 章的 APPROVE 已经恢复并消费了那笔受保护调用，不能拿完成回执再次执行。本章没有把该内存 Session 当成持久化审批平台，也没有重写原准备任务、草稿确认和 HITL 机制。接真实业务时仍需实现审批记录、绑定复核、原子消费和业务幂等；不能直接将夹具换成正式提交服务。

## 代码位置与职责

| 文件 | 责任 |
| --- | --- |
| `workflow/SubmissionFlowContract.java` | 已认证命令、三项业务契约、白名单结果投影 |
| `workflow/AfterSaleSubmissionGraph.java` | StateGraph、状态更新策略、节点、条件边、编译与执行 |
| `workflow/SubmissionFlowLab.java` | 八个明确的服务器夹具，每次独立计数、命令与图 |
| `workflow/SubmissionFlowController.java` | 本机、客户登录、CSRF、严格 DTO 和错误分类 |
| `submission-flow/index.html`、`static/submission-flow.js/css` | 场景选择、可点击流程图、真实轨迹、计数与状态解释 |

Java 的业务契约、关键方法与控制点附有中文注释。依赖保持 JDK 17、Spring Boot 3.5.8、Spring AI 1.1.2、Alibaba 1.1.2.2，没有跟随文章示例切换到 1.1.2.0。

## 图就是执行定义

`NodeSpec` 与 `EdgeSpec` 是一份结构定义：构造器据此调用 `addNode`、`addEdge`、`addConditionalEdges`，页面也从这份定义绘制连线。同时提供 `CompiledGraph.getGraph(MERMAID, ...)` 的原始导出和 `.mmd` 下载，没有手写一份与代码无关的 Mermaid。

```text
START → check_draft
  INVALID → blocked → END
  VALID → check_approval
    PENDING → waiting → END
    REJECTED → rejected → END
    INVALID → blocked → END
    APPROVED → simulate_submit
      COMPLETED → completed → END
      BLOCKED → blocked → END
      UNKNOWN → reconcile → END
```

`reconcile` 没有返回 `simulate_submit` 的边。一次未知结果只尝试一次，不自动重试。

标识、检查结果和 `status` 使用 `ReplaceStrategy`；`trace` 使用 `AppendStrategy`。节点每次只返回自己的字段和 `List.of(nodeId)`，不会把已有 trace 再追加一遍。命令中的已认证 Actor 由节点闭包使用，不写入共享状态。API 只返回允许展示的标识、三项检查、业务状态和轨迹，不返回整个 OverAllState、threadId、检查点或 Session。

## 八个实验场景

| 场景 | 实际终态 | 审批读取 | 进入模拟方法 | 模拟副作用 |
| --- | --- | ---: | ---: | ---: |
| 等待操作审批 | WAITING_APPROVAL | 1 | 0 | 0 |
| 明确拒绝 | REJECTED | 1 | 0 | 0 |
| 草稿已失效 | BLOCKED | 0 | 0 | 0 |
| 审批无效 | BLOCKED | 1 | 0 | 0 |
| 批准并模拟完成 | SIMULATION_COMPLETED | 1 | 1 | 1 |
| 批准后条件变化 | BLOCKED | 1 | 1 | 0 |
| 执行结果不确定 | RECONCILIATION_REQUIRED | 1 | 1 | 1 |
| 审批读取异常 | HTTP 500 / GRAPH_FAILED | 不返回正常结果 | 不返回正常结果 | 不返回正常结果 |

UNKNOWN 场景特意在测试探针计数一次副作用后返回未知。这证明“没收到确定结果”不能解释为“肯定没执行”；探针读数也不能代替生产系统的业务确认。异常场景不返回伪造的成功结果，页面清除上一轮轨迹与计数，也不会把缺失值填成 0 或 APPROVED。

## API 与边界

这些接口只在 `local & knowledge` 启用。匿名仅能加载页面壳；目录和执行均沿用本机访问限制、客户权限与既有 Session。客服角色不能运行，写请求需要 CSRF。每次至多四场路线实验并发；固定夹具没有网络、SQL 或模型调用。

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| GET | `/internal/draft-tasks/flow` | 页面 |
| GET | `/internal/draft-tasks/flow/definition` | 编译后的导出、同源结构定义、场景目录，不执行节点 |
| POST | `/internal/draft-tasks/flow/runs` | `{"scenario":"UNKNOWN_RESULT"}`，独立运行一次 |

请求只允许 `scenario`。传入 `taskId`、`actor`、`approval`、`status` 等额外字段会被拒绝；不存在上传任意 Graph State 或选择类名执行的接口。HTTP 200 表示得到了正常结果，具体业务含义必须读取 `result.status`，不能只看 `frameworkEnded`。

每次运行使用独立的内部 threadId 和默认内存 Saver，没有长期注册表。刷新仅重新读定义，不执行图，页面结果会清空；没有持久化审计、跨请求恢复或跨进程恢复。`waiting → END` 是本次检查结束，不是第 24 章的框架 HITL 中断。

## 固定版本核对与验证

已经核对本机 Maven 1.1.2.2 sources JAR 的 `StateGraph`、`CompiledGraph`、`CompileConfig`、`AppendStrategy` 和 `AsyncNodeAction`。`node_async` 是同步 NodeAction 的 CompletableFuture 适配，不自动给 JDBC 或模型调用创建后台线程；`recursionLimit(20)` 是图迭代预算，不等于网络超时。

概念可参照[官方 Graph 核心文档](https://java2ai.com/docs/frameworks/graph-core/core/core-library/)。官方在线页面可能面向其他版本，本项目的编译结果、固定版本源码与实际测试优先。

```bash
RUN_PGVECTOR_TESTS=true RUN_ACCEPTANCE_TESTS=true \
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home ./mvnw package
node --check src/main/resources/static/submission-flow.js
```

新增 32 项测试：真实 Graph 路线、增量合并、每轮隔离、异常与未知标签、图定义导出，以及真实 MVC/安全过滤链/CSRF/严格输入保护。所有业务能力均为明确的测试替身，不将这些测试描述为真实草稿授权或生产提交验证。

2026-10-04 实际验收结果：

- 全量 `package`：482 项测试，0 失败、0 错误、0 跳过，包含既有的 123 项真实 PostgreSQL 验证。本章新增 32 项测试；业务步骤采用明确的测试替身。
- 浏览器逐项运行七个正常场景，业务终态、真实 trace 和调用计数与上表一致。UNKNOWN 为 1 次方法调用、1 次测试副作用，随后进入核查，没有自动重试。
- 读取异常返回 HTTP 500 / GRAPH_FAILED，页面清除旧轨迹，计数显示“—”，不伪造正常结果。重新加载后的浏览器控制台无错误。
- 实际刷新只发出页面资源、Session 和图定义的 GET，没有 POST 执行请求；结果恢复为“尚未运行”。Enter / Space 可查看节点职责，重绘后保留键盘焦点。
- `.mmd` 已通过页面下载，2171 字节，文件内容与页面展示的编译图导出逐字一致。
- 1280 px 桌面检查通过；390 px 下页面宽度和 scrollWidth 都为 390，图面单独横向滚动。窄屏运行 UNKNOWN 也得到正确轨迹和终态；验收后恢复默认尺寸。
- 最终 JAR 已通过终端在 18080 启动，使用 `local,knowledge` 配置，页面与 API 实际可用。本章未在 IDEA 内启动，不将终端验证描述为 IDEA 验证。

本次未更改数据库结构，也未把图接到真实草稿审批和提交服务。已有章节的数据库测试通过，不代表本章已实现真实审批消费、生产提交、永久审计或恢复能力。
