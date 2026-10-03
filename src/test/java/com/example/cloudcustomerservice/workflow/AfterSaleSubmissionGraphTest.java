package com.example.cloudcustomerservice.workflow;

import com.alibaba.cloud.ai.graph.*;
import com.example.cloudcustomerservice.aftersale.AfterSaleModel.Actor;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import static org.assertj.core.api.Assertions.*;
import static com.example.cloudcustomerservice.workflow.SubmissionFlowContract.*;
import static com.alibaba.cloud.ai.graph.action.AsyncNodeAction.node_async;
import static com.alibaba.cloud.ai.graph.action.AsyncEdgeAction.edge_async;

/** 真实 StateGraph + 确定性业务替身，验证实际路线和调用次数，不调用模型或数据库。 */
class AfterSaleSubmissionGraphTest {
    final Command command = new Command(new Actor("fixture-tenant", 1001), UUID.randomUUID(), 2, UUID.randomUUID());

    static class Steps implements BusinessSteps {
        boolean valid = true; Approval approval = Approval.PENDING;
        SimulationOutcome outcome = SimulationOutcome.COMPLETED;
        int reads, attempts;
        public boolean draftStillConfirmed(Command c) { return valid; }
        public Approval readVerifiedApproval(Command c) { reads++; return approval; }
        public SimulationOutcome simulateIfStillAuthorized(Command c) { attempts++; return outcome; }
    }

    /** 七条业务路线覆盖三个分支点，特别检查不应执行的路线没有碰到模拟方法。 */
    @ParameterizedTest @CsvSource({
        "true,PENDING,COMPLETED,WAITING_APPROVAL,waiting,1,0",
        "true,REJECTED,COMPLETED,REJECTED,rejected,1,0",
        "false,APPROVED,COMPLETED,BLOCKED,blocked,0,0",
        "true,INVALID,COMPLETED,BLOCKED,blocked,1,0",
        "true,APPROVED,COMPLETED,SIMULATION_COMPLETED,completed,1,1",
        "true,APPROVED,BLOCKED,BLOCKED,blocked,1,1",
        "true,APPROVED,UNKNOWN,RECONCILIATION_REQUIRED,reconcile,1,1"
    })
    void realRoutesRespectBusinessBoundaries(boolean valid, Approval approval, SimulationOutcome outcome,
            String status, String terminal, int reads, int attempts) throws Exception {
        var steps = new Steps(); steps.valid=valid; steps.approval=approval; steps.outcome=outcome;
        var result = new AfterSaleSubmissionGraph(steps).run(command);
        var expected = new ArrayList<>(List.of("check_draft"));
        if (reads > 0) expected.add("check_approval");
        if (attempts > 0) expected.add("simulate_submit");
        expected.add(terminal);
        assertThat(result.status()).isEqualTo(status); assertThat(result.trace()).containsExactlyElementsOf(expected);
        assertThat(steps.reads).isEqualTo(reads); assertThat(steps.attempts).isEqualTo(attempts);
        assertThat(result.frameworkEnded()).isTrue(); assertThat(result.actualSubmitted()).isFalse(); assertThat(result.refundExecuted()).isFalse();
        // 节点仅返回增量后，初始标识应保留，trace 应按次序追加且不重复历史。
        assertThat(result.taskId()).isEqualTo(command.taskId()); assertThat(result.draftVersion()).isEqualTo(2);
        assertThat(result.approvalId()).isEqualTo(command.approvalId()); assertThat(result.trace()).doesNotHaveDuplicates();
        assertThat(result.checks()).doesNotContainKeys("actor", "threadId", "token");
        if (reads == 0) assertThat(result.checks()).doesNotContainKey("approval");
    }

    @Test void eachInvocationStartsWithFreshState() throws Exception {
        var steps = new Steps(); var graph = new AfterSaleSubmissionGraph(steps);
        var first = graph.run(command); steps.approval=Approval.REJECTED; var second = graph.run(command);
        assertThat(first.status()).isEqualTo("WAITING_APPROVAL");
        assertThat(second.trace()).containsExactly("check_draft","check_approval","rejected");
        assertThat(steps.attempts).isZero();
    }

    @Test void missingApprovalFailsInsteadOfDefaultApproval() {
        var steps = new Steps(); steps.approval=null;
        assertThatThrownBy(() -> new AfterSaleSubmissionGraph(steps).run(command)).isInstanceOf(Exception.class);
        assertThat(steps.attempts).isZero();
    }

    @Test void executionExceptionPropagatesWithoutRetryOrCompletedResult() {
        var calls = new AtomicInteger();
        var steps = new Steps() {
            @Override public SimulationOutcome simulateIfStillAuthorized(Command c) { calls.incrementAndGet(); throw new IllegalStateException("受控执行异常"); }
        };
        steps.approval=Approval.APPROVED;
        assertThatThrownBy(() -> new AfterSaleSubmissionGraph(steps).run(command)).isInstanceOf(Exception.class);
        assertThat(calls).hasValue(1);
    }

    @Test void definitionExportsRealCompiledGraphWithoutCallingBusinessMethods() throws Exception {
        var steps = new Steps(); var definition = new AfterSaleSubmissionGraph(steps).definition(command);
        assertThat(steps.reads).isZero(); assertThat(steps.attempts).isZero();
        assertThat(definition.mermaid()).contains("check_draft", "check_approval", "simulate_submit", "reconcile", "UNKNOWN");
        assertThat(definition.edges()).noneMatch(e -> e.from().equals("reconcile") && e.to().equals("simulate_submit"));
        assertThat(definition.nodes()).hasSize(10); assertThat(definition.edges()).hasSize(15);
    }

    /** 固定框架版本的错误标签回归：未映射标签必须报错，不得选择批准后继。 */
    @Test void unknownConditionalLabelFailsClosedInRealFramework() throws Exception {
        var reached = new AtomicInteger();
        var graph = new StateGraph().addNode("read", node_async(s -> Map.of()))
            .addNode("execute", node_async(s -> { reached.incrementAndGet(); return Map.of(); }))
            .addEdge(StateGraph.START,"read")
            .addConditionalEdges("read",edge_async(s -> "BROKEN_LABEL"),Map.of("APPROVED","execute"))
            .addEdge("execute",StateGraph.END).compile();
        assertThatThrownBy(() -> graph.invoke(Map.of())).isInstanceOf(Exception.class);
        assertThat(reached).hasValue(0);
    }

    @Test void malformedStateAndCommandAreRejected() {
        assertThatThrownBy(() -> AfterSaleSubmissionGraph.requiredText(new OverAllState(Map.of()),"approval")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> AfterSaleSubmissionGraph.requiredText(new OverAllState(Map.of("approval",true)),"approval")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new Command(command.actor(),command.taskId(),0,command.approvalId())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void fixtureUnknownRecordsOneEffectButNeverRetriesAndLaterRunIsIsolated() {
        var lab = new SubmissionFlowLab();
        var unknown = lab.run(command.actor(), SubmissionFlowLab.Scenario.UNKNOWN_RESULT);
        assertThat(unknown.result().status()).isEqualTo("RECONCILIATION_REQUIRED");
        assertThat(unknown.counters()).isEqualTo(new SubmissionFlowLab.Counters(1,1,1,0));
        var pending = lab.run(command.actor(),SubmissionFlowLab.Scenario.PENDING);
        assertThat(pending.counters()).isEqualTo(new SubmissionFlowLab.Counters(1,0,0,0));
        assertThat(pending.runId()).isNotEqualTo(unknown.runId());
        assertThatThrownBy(() -> lab.run(command.actor(),SubmissionFlowLab.Scenario.APPROVAL_READ_ERROR)).isInstanceOf(SubmissionFlowLab.GraphFailed.class);
    }
}
