package com.example.cloudcustomerservice.agent;

import com.example.cloudcustomerservice.aftersale.AfterSaleModel.Assessment;
import java.util.List;

/** 一次实验的回执，不是持久化任务或正式客服消息；只记录可观察的工具结果，不输出思维链。 */
public record DraftRun(String runId, Status status, String message, String candidateText,
        Assessment assessment, List<String> executedSteps, int modelCalls, int toolCalls,
        long elapsedMs, boolean submitted, boolean refundExecuted) {
    public enum Status { CANDIDATE_UNVALIDATED, INSPECTED, NEEDS_ATTENTION, RUN_FAILED, STATE_CHANGED }
    public DraftRun {
        executedSteps = List.copyOf(executedSteps);
        if (submitted || refundExecuted) throw new IllegalArgumentException("本章没有提交或退款能力");
    }
    /** 接待状态已变，连候选文本与事实快照都不再返回，调用方应刷新正式会话。 */
    public DraftRun hidden() {
        return new DraftRun(runId, Status.STATE_CHANGED, "会话状态已变化，本次结果已隐藏，请查看统一客服。",
                null, null, List.of(), modelCalls, toolCalls, elapsedMs, false, false);
    }
}
