package com.example.cloudcustomerservice.hitl;

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import com.alibaba.cloud.ai.graph.*;
import com.alibaba.cloud.ai.graph.action.InterruptionMetadata;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.hook.hip.*;
import com.alibaba.cloud.ai.graph.agent.hook.modelcalllimit.ModelCallLimitHook;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import com.example.cloudcustomerservice.aftersale.AfterSaleModel.Actor;
import com.example.cloudcustomerservice.config.PayloadLoggingChatModel;
import com.example.cloudcustomerservice.draft.DraftModels;
import com.fasterxml.jackson.databind.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.*;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * 一个所有者、一份固定草稿、一条受审调用的进程内实验。
 * 使用真实 ReactAgent / MemorySaver / HITL；不修改准备任务的 runId，也不冒充跨重启审批。
 */
public final class HitlLabSession {
    public enum Choice { APPROVE, REJECT }
    public enum Phase { NEW, RUNNING, WAITING_APPROVAL, RESUMING, REJECTED, EXPIRED, STALE,
        SIMULATION_COMPLETED, NO_EXECUTION, NEW_APPROVAL_REQUIRED, RECOVERY_REQUIRED }
    /** 只传业务卡片；内部 threadId、检查点及 InterruptionMetadata 永不返回浏览器。 */
    public record ApprovalCard(UUID approvalId, DraftModels.View reviewedDraft, String toolCallId,
            String toolName, String arguments, Instant expiresAt, String scope) { }
    public record DecisionReceipt(UUID approvalId, Choice choice, long decidedBy, Instant decidedAt) { }
    public record View(UUID executionId, UUID taskId, long draftVersion, long version, Phase phase,
            ApprovalCard card, DecisionReceipt decision, int simulatedExecutions, int modelCalls,
            boolean simulated, boolean actualSubmitted, boolean refundExecuted) { }
    /** 等待图返回的调度器由外层提供；同一个池为整个应用限流，不每次新建线程。 */
    @FunctionalInterface interface Runner { Optional<NodeOutput> run(Callable<Optional<NodeOutput>> work) throws Exception; }

    final UUID id = UUID.randomUUID();
    final Actor owner;
    final DraftModels.View frozen;
    private final String threadId = "submission-lab:" + UUID.randomUUID();
    private final ObjectMapper json;
    private final Clock clock;
    private final Duration approvalTtl;
    private final ReactAgent agent;
    private final SubmissionProbe probe;
    private final ReentrantLock gate = new ReentrantLock();
    private final AtomicInteger modelCalls = new AtomicInteger();
    private volatile Operation operation;
    private volatile View view;
    private InterruptionMetadata pending;
    private ApprovalCard card;
    private DecisionReceipt decision;
    private long version;
    private Phase phase = Phase.NEW;

    HitlLabSession(ChatModel model, ObjectMapper json, Actor owner, DraftModels.View frozen,
            boolean logPayload, Clock clock, Duration approvalTtl) {
        this.owner = owner; this.frozen = frozen; this.json = json; this.clock = clock; this.approvalTtl = approvalTtl;
        requireNoGlobalTools(model);
        probe = new SubmissionProbe(frozen.taskId(), frozen.draftVersion());
        var hook = HumanInTheLoopHook.builder().approvalOn(SubmissionProbe.TOOL_NAME,
                ToolConfig.builder().description("请审批这一次模拟提交，不会创建正式申请。").build()).build();
        var logged = new PayloadLoggingChatModel(model, logPayload, "hitl:" + id);
        ChatModel bounded = new ChatModel() {
            @Override public ChatOptions getDefaultOptions() { return model.getDefaultOptions(); }
            @Override public ChatResponse call(Prompt prompt) {
                Operation current = operation;
                check(current);
                if (modelCalls.incrementAndGet() > 4) throw new IllegalStateException("本场实验模型调用已达四次上限");
                var response = logged.call(prompt);
                check(current);
                // 在框架执行任何工具之前检查白名单及完整参数，额外/未知调用一律停止。
                if (response == null || response.getResults().size() != 1) throw new IllegalStateException("模型结果不唯一");
                var calls = response.getResult().getOutput().getToolCalls();
                if (!calls.isEmpty()) {
                    if (calls.size() != 1) throw new IllegalStateException("本实验只允许单条工具调用");
                    var call = calls.get(0); validateCall(call.id(), call.name(), call.arguments());
                }
                return response;
            }
        };
        agent = ReactAgent.builder().name("after_sale_submission_lab").model(bounded)
                .chatOptions(DashScopeChatOptions.builder().temperature(0.0).maxToken(1000)
                        .internalToolExecutionEnabled(false).toolNames(Set.of()).tools(List.of()).build())
                .systemPrompt("""
                    这是云杉本地人工审批实验。你只负责提出 simulateSubmitAfterSale 的工具调用，参数使用服务器指定的任务和草稿版本。
                    提出工具调用不等于执行：HumanInTheLoopHook 会在工具执行前暂停，由应用展示操作卡并取得人工决策。
                    首轮直接输出一条准确的工具调用，不要用“等待审批”“请确认”等普通文字代替调用，也不要自己询问批准。
                    每次只提出一个工具调用，不修改 taskId 或 draftVersion。收到工具返回的人工拒绝后，直接结束，不再请求或绕过审批。
                    不创建正式申请，不批准售后，不执行退款；不得宣称真实提交成功。
                    """)
                .tools(ToolCallbacks.from(probe)).parallelToolExecution(false).wrapSyncToolsAsAsync(false)
                // 1.1.2.2 需要显式注册该 Hook，才能把 returnDirect 的工具标记转成真正的图结束。
                .hooks(hook, new com.alibaba.cloud.ai.graph.agent.hook.returndirect.ReturnDirectModelHook(), ModelCallLimitHook.builder().runLimit(4).exitBehavior(ModelCallLimitHook.ExitBehavior.ERROR).build())
                .saver(new MemorySaver()).build();
        publish();
    }

    /** 开始后必须得到真实中断，且工具计数仍为零；普通助手文字不能代替待审批卡片。 */
    View start(Actor caller, Runnable verify, Runner runner) {
        enter(caller);
        try {
            if (phase != Phase.NEW) throw conflict("实验已经开始");
            verify.run(); operation = new Operation(verify); transition(Phase.RUNNING);
            try {
                var output = runner.run(() -> agent.invokeAndGetOutput(
                        "请提出模拟工具调用，taskId：" + frozen.taskId() + "，draftVersion：" + frozen.draftVersion() + "。审批暂停由框架处理。", config()));
                check(operation);
                if (output.isEmpty() || !(output.get() instanceof InterruptionMetadata interruption))
                    throw new IllegalStateException("没有获得工具执行前中断");
                var call = expectedCall(interruption);
                if (probe.executions() != 0) throw new IllegalStateException("批准前发生执行");
                pending = interruption;
                card = new ApprovalCard(UUID.randomUUID(), frozen, call.getId(), call.getName(), call.getArguments(),
                        clock.instant().plus(approvalTtl), "SIMULATE_SUBMISSION_ONLY");
                transition(Phase.WAITING_APPROVAL);
            } catch (Exception e) { transition(Phase.RECOVERY_REQUIRED); }
            finally { stop(); publish(); }
            return view;
        } finally { gate.unlock(); }
    }

    /** 决策只接受应用自己的枚举；反馈由服务端保留的原调用构造，不接收浏览器图状态。 */
    View decide(Actor caller, UUID approvalId, Long expectedVersion, Choice choice, Runnable verify, Runner runner) {
        enter(caller);
        try {
            expire();
            if (choice == null || expectedVersion == null || approvalId == null) throw new IllegalArgumentException("需要审批编号、版本和明确决策");
            if (phase != Phase.WAITING_APPROVAL || version != expectedVersion || !card.approvalId().equals(approvalId))
                throw conflict("审批已变化或已经消费，请刷新；未再次恢复");
            try { verify.run(); }
            catch (RuntimeException ex) { transition(Phase.STALE); throw ex; }
            var original = expectedCall(pending);
            var feedback = InterruptionMetadata.ToolFeedback.builder(original)
                    .result(choice == Choice.APPROVE ? InterruptionMetadata.ToolFeedback.FeedbackResult.APPROVED
                            : InterruptionMetadata.ToolFeedback.FeedbackResult.REJECTED)
                    .description(choice == Choice.APPROVE ? "批准这一笔模拟操作。" : "拒绝这一笔操作，停止，不得改参数重试。") .build();
            var reply = InterruptionMetadata.builder().nodeId(pending.node()).state(pending.state()).addToolFeedback(feedback).build();
            decision = new DecisionReceipt(approvalId, choice, caller.userId(), clock.instant());
            operation = new Operation(verify); transition(Phase.RESUMING);
            if (choice == Choice.APPROVE) { Operation current = operation; probe.permit(() -> check(current)); }
            try {
                var output = runner.run(() -> agent.invokeAndGetOutput("", RunnableConfig.builder().threadId(threadId)
                        .addMetadata(RunnableConfig.HUMAN_FEEDBACK_METADATA_KEY, reply).build()));
                check(operation);
                // 新中断永不沿用旧许可。本章不继续处理新请求，需显式新建独立实验。
                transition(output.isPresent() && output.get() instanceof InterruptionMetadata ? Phase.NEW_APPROVAL_REQUIRED
                        : choice == Choice.REJECT ? Phase.REJECTED
                        : probe.executions() == 1 ? Phase.SIMULATION_COMPLETED : Phase.NO_EXECUTION);
            } catch (Exception ex) { transition(Phase.RECOVERY_REQUIRED); }
            finally { stop(); publish(); }
            return view;
        } finally { gate.unlock(); }
    }

    /** 快照不访问图；运行中也能读取实际计数。审批超时通过服务端时钟判定。 */
    View snapshot(Actor caller) {
        owner(caller);
        if (gate.tryLock()) { try { expire(); publish(); } finally { gate.unlock(); } }
        return view;
    }
    /** 清理仅释放本场内存实验，不删除持久化草稿或客服记录；运行中不能清理。 */
    void discard(Actor caller) {
        enter(caller);
        try { stop(); pending = null; card = null; } finally { gate.unlock(); }
    }
    private void expire() { if (phase == Phase.WAITING_APPROVAL && !clock.instant().isBefore(card.expiresAt())) transition(Phase.EXPIRED); }
    private void enter(Actor caller) { owner(caller); if (!gate.tryLock()) throw conflict("本场实验正在处理，请刷新查询"); }
    private void owner(Actor caller) { if (!owner.equals(caller)) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"未找到可访问的审批实验"); }
    private void transition(Phase next) { phase = next; version++; publish(); }
    private void publish() { view = new View(id, frozen.taskId(), frozen.draftVersion(), version, phase, card, decision, probe.executions(), modelCalls.get(), true, false, false); }
    private RunnableConfig config() { return RunnableConfig.builder().threadId(threadId).build(); }
    private void stop() { if (operation != null) operation.active = false; probe.permit(null); operation = null; }
    private static final class Operation {
        volatile boolean active = true;
        final Runnable verify;
        Operation(Runnable verify) { this.verify = verify; }
    }
    /** 每段模型调用和受保护工具前后检查当前请求身份/接待/草稿；等待人工期间不保留 Session。 */
    private void check(Operation current) {
        if (current == null || !current.active) throw new IllegalStateException("实验已停止");
        current.verify.run();
        if (!current.active) throw new IllegalStateException("实验已停止");
    }
    private InterruptionMetadata.ToolFeedback expectedCall(InterruptionMetadata value) {
        if (value.toolFeedbacks().size() != 1 || !value.getToolsAutomaticallyApproved().isEmpty())
            throw new IllegalStateException("需要且仅允许一条待审批调用");
        var call = value.toolFeedbacks().get(0); validateCall(call.getId(),call.getName(),call.getArguments()); return call;
    }
    private void validateCall(String id, String name, String args) {
        try {
            if (id == null || id.isBlank() || id.length() > 256 || !SubmissionProbe.TOOL_NAME.equals(name)
                    || args == null || args.length() > 2000) throw new IllegalArgumentException();
            // 严格拒绝重复 JSON 键，防止卡片看到的参数与工具反序列化后的参数不一致。
            JsonNode value = json.reader().with(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION).with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(args);
            if (value == null || !value.isObject() || value.size() != 2 || !value.path("taskId").isTextual()
                    || !frozen.taskId().toString().equals(value.path("taskId").asText()) || !value.path("draftVersion").isIntegralNumber()
                    || !value.path("draftVersion").canConvertToLong() || value.path("draftVersion").asLong() != frozen.draftVersion()) throw new IllegalArgumentException();
        } catch (Exception ex) { throw new IllegalStateException("模型提出的操作与被审查草稿不一致"); }
    }
    private static void requireNoGlobalTools(ChatModel model) {
        var options = model.getDefaultOptions();
        if (options instanceof ToolCallingChatOptions t && (!t.getToolNames().isEmpty() || !t.getToolCallbacks().isEmpty())
                || options instanceof DashScopeChatOptions d && d.getTools() != null && !d.getTools().isEmpty())
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"模型配置了全局工具，不能运行审批实验");
    }
    static ResponseStatusException conflict(String message) { return new ResponseStatusException(HttpStatus.CONFLICT,message); }
}
