package com.example.cloudcustomerservice.workflow;

import com.example.cloudcustomerservice.aftersale.AfterSaleModel.Actor;
import java.util.*;
import java.util.concurrent.Semaphore;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import static com.example.cloudcustomerservice.workflow.SubmissionFlowContract.*;

/**
 * 有意隔离的确定性教学夹具，不接真实草稿、审批存储或售后服务。
 * 场景选择是设置实验条件，不是替登录用户批准任何业务操作。
 */
@Service @Profile("local & knowledge")
public class SubmissionFlowLab {
    public static final String MODE = "DETERMINISTIC_FIXTURE";
    public enum Scenario {
        PENDING("等待操作审批", "草稿有效，操作还未批准；不应进入模拟执行。"),
        REJECTED("明确拒绝", "草稿有效，操作已拒绝；模拟方法应保持零调用。"),
        INVALID_DRAFT("草稿已失效", "第一步已发现草稿失效；审批读取也不应发生。"),
        INVALID_APPROVAL("审批无效", "草稿有效，但审批绑定或有效期不满足要求。"),
        APPROVED("批准并模拟完成", "审批已批准，执行前再次检查仍有效；模拟一次。"),
        CHANGED_BEFORE_EXECUTION("批准后条件变化", "前面读到批准，进入模拟方法后复核失败；不产生模拟副作用。"),
        UNKNOWN_RESULT("执行结果不确定", "测试探针模拟一次副作用后返回 UNKNOWN；进入核查，不能自动重试。"),
        APPROVAL_READ_ERROR("审批读取异常", "人为注入读取异常，验证错误不会变成默认批准或成功。" );
        final String title, description;
        Scenario(String title, String description) { this.title = title; this.description = description; }
    }
    public record ScenarioInfo(Scenario id, String title, String description) { }
    public record Catalog(String mode, AfterSaleSubmissionGraph.Definition definition, List<ScenarioInfo> scenarios) { }
    public record Counters(int approvalReads, int simulationCalls, int simulatedEffects, int modelCalls) { }
    public record Run(UUID runId, String mode, Scenario scenario, Result result, Counters counters) { }
    /** 只暴露错误分类；不把内部异常、状态或服务对象序列化到浏览器。 */
    public static final class GraphFailed extends RuntimeException {
        public GraphFailed(Throwable cause) { super("路线实验未正常结束", cause); }
    }
    private final Semaphore capacity = new Semaphore(4);

    /** 展示定义不运行节点；使用固定占位命令仅满足图构造的类型契约，不代表真实业务对象。 */
    public Catalog catalog() {
        try {
            var graph = new AfterSaleSubmissionGraph(new FixtureSteps(Scenario.PENDING));
            return new Catalog(MODE, graph.definition(command(new Actor("fixture-only", 1))),
                Arrays.stream(Scenario.values()).map(s -> new ScenarioInfo(s, s.title, s.description)).toList());
        } catch (Exception e) { throw new GraphFailed(e); }
    }

    /** 每次点击创建独立命令、计数器与图；不读取旧结果、不保存恢复点、不调用模型。 */
    public Run run(Actor actor, Scenario scenario) {
        if (scenario == null) throw new IllegalArgumentException("请选择实验场景");
        if (!capacity.tryAcquire()) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "正在运行的路线实验较多，请稍后操作");
        try {
            var steps = new FixtureSteps(scenario);
            Result result = new AfterSaleSubmissionGraph(steps).run(command(actor));
            return new Run(UUID.randomUUID(), MODE, scenario, result,
                new Counters(steps.approvalReads, steps.simulationCalls, steps.simulatedEffects, 0));
        } catch (Exception e) { throw new GraphFailed(e); }
        finally { capacity.release(); }
    }

    /** 随机标识只用于本次状态流转；从不拿这些标识去查询草稿或审批表。 */
    private static Command command(Actor actor) { return new Command(actor, UUID.randomUUID(), 2, UUID.randomUUID()); }

    /** 有限的服务器夹具，不接收浏览器传来的 approval/status 或可执行节点名。 */
    private static final class FixtureSteps implements BusinessSteps {
        private final Scenario scenario;
        private int approvalReads, simulationCalls, simulatedEffects;
        FixtureSteps(Scenario scenario) { this.scenario = scenario; }

        @Override public boolean draftStillConfirmed(Command command) { return scenario != Scenario.INVALID_DRAFT; }

        @Override public Approval readVerifiedApproval(Command command) {
            approvalReads++;
            return switch (scenario) {
                case PENDING -> Approval.PENDING;
                case REJECTED -> Approval.REJECTED;
                case INVALID_APPROVAL -> Approval.INVALID;
                case APPROVAL_READ_ERROR -> throw new IllegalStateException("受控夹具：审批读取失败");
                case APPROVED, CHANGED_BEFORE_EXECUTION, UNKNOWN_RESULT -> Approval.APPROVED;
                case INVALID_DRAFT -> throw new IllegalStateException("失效草稿不应进入审批读取");
            };
        }

        @Override public SimulationOutcome simulateIfStillAuthorized(Command command) {
            simulationCalls++;
            // 进入节点和产生副作用分开计数。这个检查模拟的是执行前复核，不能省略。
            if (scenario == Scenario.CHANGED_BEFORE_EXECUTION) return SimulationOutcome.BLOCKED;
            if (scenario != Scenario.APPROVED && scenario != Scenario.UNKNOWN_RESULT)
                throw new IllegalStateException("不允许的场景触达执行方法");
            simulatedEffects++;
            // UNKNOWN 故意发生在模拟副作用之后，证明未知结果不能被当成可安全重试。
            return scenario == Scenario.UNKNOWN_RESULT ? SimulationOutcome.UNKNOWN : SimulationOutcome.COMPLETED;
        }
    }
}
