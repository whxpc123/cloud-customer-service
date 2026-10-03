package com.example.cloudcustomerservice.hitl;

import java.util.*;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/** 每场实验独立的模拟器；没有售后写入依赖，计数不是正式提交或数据库幂等凭据。 */
public final class SubmissionProbe {
    public static final String TOOL_NAME = "simulateSubmitAfterSale";
    private final UUID taskId;
    private final long draftVersion;
    private volatile Permit permit;
    private int executions;

    public SubmissionProbe(UUID taskId, long draftVersion) {
        this.taskId = Objects.requireNonNull(taskId);
        this.draftVersion = draftVersion;
    }

    /** 仅应用服务可安装一次操作的许可；不是 Tool，模型无法给自己授权。 */
    synchronized void permit(Runnable verify) { permit = verify == null ? null : new Permit(verify); }
    private record Permit(Runnable verify) { }

    @Tool(name = TOOL_NAME, returnDirect = true, description = "模拟提交指定任务的指定草稿版本。必须先经过人工批准，只记录模拟执行次数，不创建售后申请，不执行退款。")
    public Map<String, Object> simulate(
            @ToolParam(description = "本次任务的完整 UUID") String taskId,
            @ToolParam(description = "本次已确认的草稿版本") long draftVersion) {
        if (!this.taskId.toString().equals(taskId) || this.draftVersion != draftVersion)
            throw new IllegalArgumentException("操作与被审查草稿不一致");
        Permit current = permit;
        if (current == null) throw new SecurityException("本次操作尚未批准");
        // 不持有 Java 锁等待数据库查询；超时、注销或草稿变化时先停止，不增加计数。
        current.verify().run();
        synchronized (this) {
            if (permit != current) throw new SecurityException("本次许可已经撤销");
            executions = 1;
            return Map.of("simulated", true, "taskId", this.taskId, "draftVersion", this.draftVersion,
                    "simulatedExecutions", executions, "actualSubmitted", false, "refundExecuted", false);
        }
    }

    public synchronized int executions() { return executions; }
}
