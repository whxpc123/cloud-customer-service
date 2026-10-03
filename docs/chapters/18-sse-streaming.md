# 第十八章：状态订阅与真实模型流式传输

本章将已保存的接待状态推送到客户页面，并增加独立的模型流实验。继续使用 Java 17、Spring Boot 3.5.8、Spring AI 1.1.2、Spring AI Alibaba 1.1.2.2 和端口 18080，密钥仍从 `DASHSCOPE_API_KEY` 读取。没有新增数据库迁移。

## 两条独立链路

| 页面 / API | 数据来源 | 完成的含义 |
| --- | --- | --- |
| `/internal/routing`，GET `/api/handoff/conversations/{id}/events` | 第十七章数据库接待回执 | 接待模式和版本已持久化；SSE 本身只读取 |
| `/internal/stream-lab`，POST `/internal/stream-lab/answer` | 独立 ChatClient 的 `stream().content()` | 本次实验文字输出结束；没有执行业务或保存正式消息 |

正式客服、订单工具、售后预检查和知识问答仍使用原有完整回答链。CallAdvisor、证据检查、售后解释过滤和第十七章版本发布门没有绕过或机械替换为流式方法。实验客户端不装配 Memory、RAG、工具或业务 Advisor；共享模型若配置全局工具，入口返回 503，避免工具配置意外继承。

## 先看状态订阅

1. 用第十七章本地账户在统一客服页登录。原账户文件继续使用，不重新生成密码。
2. 创建会话并申请人工，获得数据库受理号。
3. 客服用原 API 领取、结束；客户页面自动更新，无需点击刷新。
4. `CLOSED` 会关闭状态流并禁用发送；新咨询需要新会话。

服务器在开始流之前校验登录、`customer:chat` 权限和会话归属；不存在或不属于当前账户的会话返回 404。身份不从请求正文、查询参数、`X-Demo-User-Id` 或事件 ID 获取。GET 订阅不创建记录、不申请人工、不启动模型。

每次短查询结束后等待 2 秒再查询，阻塞 JDBC 放在独立有界调度器中，不在 Reactor 的计时线程执行，也不因慢查询叠加同一订阅的轮询。查询事务与连接获取均有 3 秒边界。每次工作线程读取都重新检查当前 HttpSession 中的身份和权限，并临时设置独立 SecurityContext，完成后恢复；退出登录后下一次查询会终止流。

```text
id: 1
retry: 3000
event: conversation.state
data: {"conversationId":"...","mode":"WAITING_HUMAN","version":1,...}

:ping

```

第一帧立即发送已验证的当前快照，后续相同版本不重复发送；15 秒发送一次注释心跳，心跳没有业务含义。每条连接最多 4 分钟，原生 EventSource 随后重连。`Last-Event-ID` **不用于历史重放**：重连只返回当下快照，中间状态可能被合并或错过。需要完整历史时读取正式记录接口。

浏览器的 POST 回执、SSE 事件、历史查询共用版本检查，不允许旧状态覆盖新状态。状态变化时补读正式消息，自己的发送操作和手动刷新也会读取记录。本章没有实现“每条消息的推送”：例如另一个浏览器写入同版本的补充消息，需点击“刷新状态与记录”获取。

## 再看模型流

在统一客服页登录后，点击“模型流式实验”。输入一般性问题后，前端用带 Cookie 和 CSRF 的 POST fetch 读取 ReadableStream，逐片段安全写入 TextNode，保留空格和换行；不会先 `response.text()` 再拆字播放。

```http
POST /internal/stream-lab/answer
Accept: text/event-stream
Content-Type: application/json
X-CSRF-TOKEN: <登录后重新取得的令牌>

{"question":"请用三行解释 SSE，每行说明一个要点。"}
```

每轮生成一个 UUID；四类事件都含 `turnId`、从 1 开始连续递增的 `sequence` 和 `text`，SSE `id` 为 `turnId:sequence`。

```text
id: <UUID>:1
event: turn.started
data: {"turnId":"<UUID>","sequence":1,"text":"已进入模型生成流程。"}

id: <UUID>:2
event: answer.delta
data: {"turnId":"<UUID>","sequence":2,"text":"SSE 是……\n"}

id: <UUID>:3
event: turn.completed
data: {"turnId":"<UUID>","sequence":3,"text":"本次模型文本输出结束，未执行任何业务，也未保存正式消息。"}

```

异常、空闲超时、总时限或字符限额触发 `turn.failed`，不会再发送 completed，也不向浏览器泄露供应商异常正文。连接 EOF、HTTP 200 或“已经收到一些字”都不等于完成；前端只在收到明确终结事件后标记文本结束。序号重复可忽略，缺号、跨轮、非法格式或终结后的新增事件视为协议错误。

前端解析器支持 UTF-8 字符跨网络块、LF / CRLF / CR、注释、多行 data、粘包和空 data；未以空行结束的尾帧不会当成完整事件处理。单帧和单行均有 65536 个 UTF-16 代码单元上限。点击停止或离开页面会 Abort，已收到的文字保留为未完成草稿；不自动重试 POST，也不承诺远端立刻停止计算或计费。

## 代码阅读顺序

- `stream/ConversationStateStreamController` → `StreamIdentity` → `SseStreams.states`：预检查、工作线程身份和状态协议。
- `stream/LocalModelStreamController` → `SseStreams.answer`：独立客户端、真实增量输出、四种事件与超时。
- `stream/SseConnections`、`SseRuntimeConfiguration`、`SseSettings`：连接租约和资源边界。
- `static/routing-lab.js`：EventSource 生命周期、版本检查和历史补读。
- `static/sse-client.mjs`、`static/stream-lab.js`：流解析、回合校验、取消和展示。
- `SseStreamsTest`、`SseHttpIntegrationTest`、`scripts/tests/sse-client.test.mjs`：协议和端到端行为。

应用显式保持 Servlet / Spring MVC，增加 `spring-webflux` 只为响应式客户端与 SSE 类型支持，没有切换到 Netty 服务器。MVC 流写出执行器核心 4 / 最大 16 / 队列 128；JDBC 调度器最大 8 线程、每线程队列 100。框架负责唯一订阅，不另行 subscribe 做日志或指标。原 `PayloadLoggingChatModel` 继续观察流，`--app.ai.log-payload=true` 时可看提示词和模型片段；不要将本地日志提交 Git。

| `app.sse.*` 配置 | 默认值 |
| --- | --- |
| `poll-interval` / `heartbeat-interval` | 2s / 15s |
| `state-lifetime` | 4m |
| `model-idle-timeout` / `model-deadline` | 30s / 2m |
| `max-characters` | 32768（Java 字符长度） |
| `max-connections` / `max-connections-per-account` | 64 / 4，两类 SSE 共享 |

独立模型客户端输出最多 512 tokens。连接额度在框架订阅时获取，完成、异常或取消均释放；达到上限返回 429。资源参数用于本机教学，未做生产容量压测，不是多实例全局限流。TCP 断线需下次写入/心跳或超时才能被服务端观察，取消不代表已经完成业务。

## 验证记录

2026-10-03 至 10-04，本机执行：

```bash
RUN_PGVECTOR_TESTS=true JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home ./mvnw package
node --test scripts/tests/sse-client.test.mjs
python3 scripts/check-sse.py --model
```

- Java **318 项通过、0 失败、0 跳过**，含 **53 项真实 PostgreSQL 集成测试**。未设置 `RUN_PGVECTOR_TESTS` 时这些数据库测试跳过。
- 新增 Java 24 项：15 项协议/资源/线程身份测试、9 项真实 Tomcat HTTP + PostgreSQL 测试。后者仅替换模型，不把 MockMvc 内存结果冒充网络流；覆盖所有权、登录/CSRF、四种接待状态、重连快照、退出后关闭、首片段先到、失败、断线取消、全局工具拒绝和连接限额。
- 前端 **122 项通过**，含每个可能字节断点、中文/emoji、边界换行、粘包、协议非法输入、清理与回合检查。
- 实际 18080 + Qwen：一条 SSE 连接依次收到 BOT v0 / WAITING_HUMAN v1 / HUMAN_ACTIVE v2 / CLOSED v3；重连只读 CLOSED 快照。模型输出 16 段，首段 **578 ms**，整轮 **1766 ms**，170 字符且保留换行，明确收到 completed。这是单次本机观测，不是延迟保证。
- 浏览器独立实际调用：看到生成中首片段，完成后共 18 段、首段 253 ms、整轮 1569 ms；另一轮在 78 段时点击停止，显示“未完成草稿”，没有自动重发。客服通过 API 领取/结束时，页面无需刷新自动更新到 HUMAN_ACTIVE / CLOSED，结束后连接关闭。
- 1280 px 和 390 px 页面宽度检查无横向溢出；浏览器控制台无错误。

`check-sse.py` 默认只验证本机状态，添加 `--model` 才调用真实模型并消耗额度；会创建并结束一条验收会话。仅允许本机 18080 或代理实验端口 18081，密码来自忽略的本地账户文件，结果写入 `/tmp/ch18-sse-results.json`，不输出凭证或完整回答。

当前版本从终端启动的不可变 JAR 运行于 18080。IDEA 运行配置仍可导入，使用 JDK 17、`local,knowledge` 和同一环境变量；本次自动切换项目再次遇到 `AXError.invalidUIElement`，**没有验证 IDEA 内启动成功**。手动接管 IDEA 前须先停止占用 18080 的本项目终端进程。

## 代理与边界

响应包含 `Cache-Control: no-store`、`X-Accel-Buffering: no`。提供 [Nginx 本地参考配置](../examples/nginx-sse.conf)：关闭缓冲、缓存和 gzip，保留 HTTP/1.1，读超时 60 秒。当前机器没有 Nginx，本次只验证直连，没有声称穿过真实反向代理仍能立即到达；有 Nginx 后可运行 `python3 scripts/check-sse.py --base http://127.0.0.1:18081 --model` 验证。

本章没有事件持久化/精确重放、断点续传、多实例广播、真人消息通道，也没有将未经完整验证的订单或售后草稿发送为正式答案。用户看到的进度仅代表真实接待快照或实际模型片段，不模拟检索、工具、审批等未观测阶段。
