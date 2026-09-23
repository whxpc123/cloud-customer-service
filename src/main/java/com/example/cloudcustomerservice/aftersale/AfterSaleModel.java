package com.example.cloudcustomerservice.aftersale;

import java.time.*;
import java.util.*;

/** 第十五章证据契约：用户诉求、系统事实、政策快照和程序结论分别保存，不定义批准或退款成功状态。 */
public final class AfterSaleModel {
    private AfterSaleModel() { }
    /** 仅允许服务端适配器构造；不能从模型参数或 X-Demo-User-Id 取得身份。 */
    public record Actor(String tenantId,long userId) {
        public Actor {if(tenantId==null||!tenantId.matches("[a-zA-Z0-9_-]{1,80}")||userId<=0)throw new IllegalArgumentException("Invalid actor");}
    }
    /** 用户提出的原因，不是系统核验结果。 */
    public enum ReturnReason { QUALITY_ISSUE, CHANGE_OF_MIND, UNKNOWN }
    public enum QualityVerification { UNVERIFIED, CONFIRMED, REJECTED }
    public enum AssessmentStatus { NEED_ORDER_NO, NOT_ACCESSIBLE, NO_EVIDENCE, NEED_MORE_INFORMATION,
        NEED_QUALITY_VERIFICATION, NEED_MANUAL_REVIEW, NO_REASON_WINDOW_EXPIRED, TEMPORARILY_UNAVAILABLE }
    /** 截止时间与适用版本由业务适配器提供，模型不能临时根据自然语言政策计算或选择。 */
    public record OrderFacts(String orderNo,String productType,OffsetDateTime signedAt,OffsetDateTime noReasonDeadline,
            QualityVerification qualityVerification,String policySourceId,String policyVersion) { }
    /** 取自工具内部的本次检索，不读取外层 RAG Advisor 的 Context 冒充来源。 */
    public record PolicyEvidence(String documentId,String sourceId,String sourceVersion,String text) { }
    public record Decision(AssessmentStatus status,String explanation) { }
    /** checkedAt 是本次查询时间；refundExecuted 在本章只能为 false。 */
    public record Assessment(AssessmentStatus status,OrderFacts verifiedFacts,ReturnReason claimedReason,String explanation,
            List<PolicyEvidence> evidence,Instant checkedAt,boolean refundExecuted) {
        public Assessment {evidence=evidence==null?List.of():List.copyOf(evidence);
            if(refundExecuted)throw new IllegalArgumentException("只读预检查不能执行退款");}
    }
    /** 实现必须同时约束租户、用户和订单号；不可访问与不存在统一为空。 */
    public interface OrderFactsReader {Optional<OrderFacts> findOwned(Actor actor,String orderNo);}
}
