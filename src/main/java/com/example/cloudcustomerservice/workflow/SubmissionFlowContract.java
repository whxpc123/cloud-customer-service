package com.example.cloudcustomerservice.workflow;

import com.example.cloudcustomerservice.aftersale.AfterSaleModel.Actor;
import java.util.*;

/** 编排层与业务能力之间的契约；Graph 不负责替业务实现权限、审批消费或事务。 */
public final class SubmissionFlowContract {
    private SubmissionFlowContract() { }

    public enum Approval { PENDING, APPROVED, REJECTED, INVALID }
    public enum SimulationOutcome { COMPLETED, BLOCKED, UNKNOWN }

    /** 身份来自应用入口，标识来自已核对的业务对象；actor 不写进 Graph State。 */
    public record Command(Actor actor, UUID taskId, long draftVersion, UUID approvalId) {
        public Command {
            Objects.requireNonNull(actor); Objects.requireNonNull(taskId); Objects.requireNonNull(approvalId);
            if (draftVersion <= 0) throw new IllegalArgumentException("草稿版本必须为正数");
        }
    }

    /** 只返回允许展示的字段；不把 OverAllState、内部 threadId 或检查点直接交给浏览器。 */
    public record Result(UUID taskId, long draftVersion, UUID approvalId, String status,
            List<String> trace, Map<String, String> checks, boolean frameworkEnded,
            boolean actualSubmitted, boolean refundExecuted) {
        public Result { trace = List.copyOf(trace); checks = Map.copyOf(checks); }
    }

    public interface BusinessSteps {
        /** 实际接线必须核对所有权、当前版本和内容确认；false 是已知失效，不吞掉权限异常。 */
        boolean draftStillConfirmed(Command command) throws Exception;

        /** 实际接线必须核对审批的所有者、操作范围、参数、版本、有效期和消费状态。 */
        Approval readVerifiedApproval(Command command) throws Exception;

        /**
         * 进入执行节点后仍需重新核验授权与草稿。State 里的 APPROVED 只是旧观察值。
         * 本章仅允许模拟；UNKNOWN 进入核查，不能擅自解释为失败并重试。
         */
        SimulationOutcome simulateIfStillAuthorized(Command command) throws Exception;
    }
}
