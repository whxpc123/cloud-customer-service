package com.example.cloudcustomerservice.aftersale;

import java.time.Instant;
import static com.example.cloudcustomerservice.aftersale.AfterSaleModel.*;

/** 可重复测试的纯 Java 规则：仅检查本章已实现的条件，不推导完整退款资格。 */
public final class ReturnPrecheckRules {
    private ReturnPrecheckRules() { }
    public static Decision evaluate(OrderFacts facts,ReturnReason reason,Instant now) {
        if(facts.signedAt()==null||facts.signedAt().toInstant().isAfter(now))
            return new Decision(AssessmentStatus.NEED_MORE_INFORMATION,"签收事实缺失或异常，需要核实。");
        if(reason==null||reason==ReturnReason.UNKNOWN)
            return new Decision(AssessmentStatus.NEED_MORE_INFORMATION,"需要明确退货原因，不能代替用户猜测。");
        // 先区分质量诉求，不能拿无理由过期直接否定质量售后。
        if(reason==ReturnReason.QUALITY_ISSUE) {
            if(facts.qualityVerification()==null||facts.qualityVerification()==QualityVerification.UNVERIFIED)
                return new Decision(AssessmentStatus.NEED_QUALITY_VERIFICATION,"用户反馈质量问题，但系统尚未核验，不能承诺退货已经获批。");
            return new Decision(AssessmentStatus.NEED_MANUAL_REVIEW,"需要结合质量核验记录继续售后审核，本次没有提交申请或执行退款。");
        }
        if(!"ORDINARY".equals(facts.productType())||facts.noReasonDeadline()==null
                ||facts.noReasonDeadline().isBefore(facts.signedAt()))
            return new Decision(AssessmentStatus.NEED_MANUAL_REVIEW,"商品适用条件或截止时间缺失、异常，需要进一步审核。");
        // 截止瞬间尚未超时；仅后一刻属于过期。业务时区统一转为 Instant 比较。
        if(now.isAfter(facts.noReasonDeadline().toInstant()))
            return new Decision(AssessmentStatus.NO_REASON_WINDOW_EXPIRED,"已超过业务系统返回的无理由退货截止时间；这不等于排除其他售后渠道。");
        return new Decision(AssessmentStatus.NEED_MANUAL_REVIEW,"目前只确认尚未超过无理由期限，商品完好程度等其他条件仍需审核。");
    }
}
