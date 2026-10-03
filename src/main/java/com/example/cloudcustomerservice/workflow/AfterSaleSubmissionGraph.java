package com.example.cloudcustomerservice.workflow;

import com.alibaba.cloud.ai.graph.*;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import com.alibaba.cloud.ai.graph.state.strategy.*;
import java.util.*;
import static com.alibaba.cloud.ai.graph.StateGraph.*;
import static com.alibaba.cloud.ai.graph.action.AsyncNodeAction.node_async;
import static com.alibaba.cloud.ai.graph.action.AsyncEdgeAction.edge_async;
import static com.example.cloudcustomerservice.workflow.SubmissionFlowContract.*;

/**
 * 第 25 章的一次性分支图：节点做业务，条件边决定路线，终点只结束本次检查。
 * 每次运行都新建图和默认内存 Saver；WAITING_APPROVAL 不是可恢复的 HITL 中断。
 */
public final class AfterSaleSubmissionGraph {
    public static final String NAME = "after-sale-submission-lab-v1";

    /** 名称、路由字段和终态属于执行定义；坐标与中文说明供同一份定义的页面展示。 */
    public record NodeSpec(String id, String title, String description, String routeKey,
            String terminalStatus, int x, int y) { }
    public record EdgeSpec(String from, String to, String label) { }
    public record Definition(String name, List<NodeSpec> nodes, List<EdgeSpec> edges, String mermaid) { }

    private static final List<NodeSpec> NODES = List.of(
        new NodeSpec(START, "开始本次检查", "每次从入口重新核验，不恢复上一轮图。", null, null, 430, 30),
        new NodeSpec("check_draft", "检查草稿", "检查当前版本和内容确认；失效时连审批读取都不继续。", "draft_check", null, 430, 125),
        new NodeSpec("check_approval", "检查操作审批", "读取并核验具体操作审批。内容确认不能代替操作批准。", "approval", null, 430, 265),
        new NodeSpec("simulate_submit", "受控模拟执行", "进入后再次检查；条件变化可阻断，结果未知必须核查。", "simulation_result", null, 760, 400),
        new NodeSpec("blocked", "阻断", "草稿、审批或执行前条件不满足，停止本次尝试。", null, "BLOCKED", 30, 550),
        new NodeSpec("waiting", "等待审批", "结束本次检查，不占用线程等待，也没有建立恢复点。", null, "WAITING_APPROVAL", 250, 550),
        new NodeSpec("rejected", "已拒绝", "拒绝分支不能进入模拟执行节点。", null, "REJECTED", 470, 550),
        new NodeSpec("completed", "模拟完成", "仅表示模拟完成；没有售后申请或退款。", null, "SIMULATION_COMPLETED", 690, 550),
        new NodeSpec("reconcile", "结果待核查", "结果不确定，不能自动连回执行节点或重试。", null, "RECONCILIATION_REQUIRED", 910, 550),
        new NodeSpec(END, "本次图结束", "END 不等于业务成功，请读取明确的业务状态。", null, null, 470, 695)
    );
    // 这一份边定义同时用于 addEdge / addConditionalEdges 和浏览器连线，避免图与代码分别维护。
    private static final List<EdgeSpec> EDGES = List.of(
        edge(START, "check_draft", ""),
        edge("check_draft", "check_approval", "VALID"), edge("check_draft", "blocked", "INVALID"),
        edge("check_approval", "waiting", "PENDING"), edge("check_approval", "rejected", "REJECTED"),
        edge("check_approval", "blocked", "INVALID"), edge("check_approval", "simulate_submit", "APPROVED"),
        edge("simulate_submit", "completed", "COMPLETED"), edge("simulate_submit", "blocked", "BLOCKED"),
        edge("simulate_submit", "reconcile", "UNKNOWN"),
        edge("waiting", END, ""), edge("rejected", END, ""), edge("blocked", END, ""),
        edge("completed", END, ""), edge("reconcile", END, "")
    );
    private final BusinessSteps steps;

    /** 注入的能力必须自行履行业务契约，不能因为放进图中就视为已经安全。 */
    public AfterSaleSubmissionGraph(BusinessSteps steps) { this.steps = Objects.requireNonNull(steps); }

    /** 只接受业务命令；没有接受外部 approval、status 或任意 Graph State 的入口。 */
    public Result run(Command command) throws GraphStateException {
        Objects.requireNonNull(command);
        var executable = build(command).compile(CompileConfig.builder().recursionLimit(20).build());
        var input = Map.<String, Object>of("task_id", command.taskId().toString(),
            "draft_version", command.draftVersion(), "approval_id", command.approvalId().toString(),
            "status", "RUNNING", "trace", List.of());
        var config = RunnableConfig.builder().threadId("graph-lab:" + UUID.randomUUID()).build();
        var state = executable.invoke(input, config).orElseThrow(() -> new IllegalStateException("图未返回最终状态"));
        String status = requiredText(state, "status");
        if (NODES.stream().noneMatch(n -> status.equals(n.terminalStatus())))
            throw new IllegalStateException("图没有到达明确的业务结果节点");
        Object value = state.value("trace").orElseThrow(() -> new IllegalStateException("缺少执行轨迹"));
        if (!(value instanceof List<?> list) || list.stream().anyMatch(v -> !(v instanceof String)))
            throw new IllegalStateException("执行轨迹类型不正确");
        // 白名单投影只含三个观察结果；未执行的检查保持缺席，而不是补成 APPROVED。
        var checks = new LinkedHashMap<String, String>();
        for (String key : List.of("draft_check", "approval", "simulation_result"))
            if (state.value(key).isPresent()) checks.put(key, requiredText(state, key));
        return new Result(UUID.fromString(requiredText(state, "task_id")),
            ((Number) state.value("draft_version").orElseThrow()).longValue(),
            UUID.fromString(requiredText(state, "approval_id")), status,
            list.stream().map(String.class::cast).toList(), checks, true, false, false);
    }

    /** 编译和导出使用与运行完全相同的结构；此方法不执行任何节点或业务调用。 */
    public Definition definition(Command command) throws GraphStateException {
        var executable = build(command).compile(CompileConfig.builder().recursionLimit(20).build());
        return new Definition(NAME, NODES, EDGES,
            executable.getGraph(GraphRepresentation.Type.MERMAID, NAME, true).content());
    }

    /** 注册节点后按元数据连接条件边。普通边表示固定顺序，不拿多条普通边代替互斥选择。 */
    private StateGraph build(Command command) throws GraphStateException {
        StateGraph graph = new StateGraph(NAME, () -> {
            Map<String, KeyStrategy> strategies = new HashMap<>();
            for (String key : List.of("task_id", "draft_version", "approval_id", "draft_check", "approval", "simulation_result", "status"))
                strategies.put(key, new ReplaceStrategy());
            strategies.put("trace", new AppendStrategy());
            return strategies;
        });
        for (NodeSpec node : NODES) {
            if (node.id().equals(START) || node.id().equals(END)) continue;
            if (node.terminalStatus() != null) {
                graph.addNode(node.id(), node_async(state -> update(node.id(), "status", node.terminalStatus())));
            } else {
                graph.addNode(node.id(), node_async(state -> switch (node.id()) {
                    case "check_draft" -> update(node.id(), "draft_check", steps.draftStillConfirmed(command) ? "VALID" : "INVALID");
                    case "check_approval" -> update(node.id(), "approval", Objects.requireNonNull(steps.readVerifiedApproval(command), "缺少审批结果").name());
                    case "simulate_submit" -> update(node.id(), "simulation_result", Objects.requireNonNull(steps.simulateIfStillAuthorized(command), "缺少模拟结果").name());
                    default -> throw new IllegalStateException("未实现的业务节点");
                }));
            }
        }
        for (NodeSpec node : NODES) {
            var outgoing = EDGES.stream().filter(e -> e.from().equals(node.id())).toList();
            if (node.routeKey() != null) {
                var mappings = new LinkedHashMap<String, String>();
                outgoing.forEach(e -> mappings.put(e.label(), e.to()));
                graph.addConditionalEdges(node.id(), edge_async(state -> requiredText(state, node.routeKey())), mappings);
            } else for (var e : outgoing) graph.addEdge(e.from(), e.to());
        }
        return graph;
    }

    /** 每个节点只交出自己的轨迹增量；AppendStrategy 负责追加，禁止重复拼接全部历史。 */
    private static Map<String, Object> update(String node, String key, String value) {
        return Map.of(key, value, "trace", List.of(node));
    }

    /** 缺少字段、类型错误或空标签都抛错，绝不默认走批准或成功分支。 */
    static String requiredText(OverAllState state, String key) {
        Object value = state.value(key).orElseThrow(() -> new IllegalStateException("缺少状态字段：" + key));
        if (!(value instanceof String text) || text.isBlank()) throw new IllegalStateException("状态字段类型不正确：" + key);
        return text;
    }

    private static EdgeSpec edge(String from, String to, String label) { return new EdgeSpec(from, to, label); }
}
