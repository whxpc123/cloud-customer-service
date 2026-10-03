package com.example.cloudcustomerservice.agent;

import com.example.cloudcustomerservice.aftersale.AfterSaleModel.*;
import com.example.cloudcustomerservice.handoff.HandoffModel.Receipt;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;

/** 业务任务 ID、每轮 ID 与检查点摘要分开；响应不泄漏内部 threadId、检查点正文或认证对象。 */
public final class DraftTaskModel {
    private DraftTaskModel() { }
    public enum Status { READY, RUNNING, CANDIDATE_UNVALIDATED, NEEDS_ATTENTION, FAILED, CLOSED }
    /** 身份每次由 HTTP 适配器重新取得，查询函数只在当前请求中使用，绝不保存进任务或检查点。 */
    public record Access(Actor actor,Function<UUID,Receipt> reception) {
        public Access {Objects.requireNonNull(actor);Objects.requireNonNull(reception);}
    }
    public record TaskSummary(UUID taskId,UUID conversationId,String orderNo,ReturnReason reason,
            Status status,int turnNo,Instant createdAt) { }
    /** 从 MemorySaver 的当前检查点纯读取，不调用 invoke，不提供客户端可指定的 checkpointId。 */
    public record StateSummary(int checkpointCount,int messageCount,int userMessages,int assistantMessages,int toolMessages) {
        static StateSummary empty(){return new StateSummary(0,0,0,0,0);}
    }
    public record TaskView(TaskSummary task,DraftRun lastRun,StateSummary state) { }
}
