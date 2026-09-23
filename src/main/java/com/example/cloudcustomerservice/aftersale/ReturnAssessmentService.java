package com.example.cloudcustomerservice.aftersale;

import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import static com.example.cloudcustomerservice.aftersale.AfterSaleModel.*;

/** 固定编排顺序：订单归属与事实 → 指定版本证据 → 有限程序规则；没有写订单/支付操作。 */
@Service
@Profile("local & knowledge")
public class ReturnAssessmentService {
    private final OrderFactsReader orders;private final ApplicablePolicyRetriever policies;private final Clock clock;
    public ReturnAssessmentService(OrderFactsReader orders,ApplicablePolicyRetriever policies,@Qualifier("afterSaleClock") Clock clock){this.orders=orders;this.policies=policies;this.clock=clock;}
    public Assessment assess(Actor actor,String orderNo,ReturnReason claim) {
        Objects.requireNonNull(actor);Instant checkedAt=clock.instant();ReturnReason reason=claim==null?ReturnReason.UNKNOWN:claim;
        if(orderNo==null||orderNo.length()>64||!orderNo.strip().toUpperCase(Locale.ROOT).matches("A[0-9]{5}"))
            return result(AssessmentStatus.NEED_ORDER_NO,null,reason,"请提供正确的订单号：A 加五位数字。",List.of(),checkedAt);
        String normalized=orderNo.strip().toUpperCase(Locale.ROOT);
        var owned=orders.findOwned(actor,normalized);
        if(owned.isEmpty())return result(AssessmentStatus.NOT_ACCESSIBLE,null,reason,"未找到当前账户可以访问的订单。",List.of(),checkedAt);
        OrderFacts facts=owned.get();if(!normalized.equals(facts.orderNo()))throw new IllegalStateException("Order adapter mismatch");
        var evidence=policies.retrieve(actor,facts,reason);
        if(evidence.isEmpty())return result(AssessmentStatus.NO_EVIDENCE,facts,reason,"已查询订单，但没有取得适用版本的政策依据，不能据此承诺能否退货。",List.of(),checkedAt);
        var decision=ReturnPrecheckRules.evaluate(facts,reason,checkedAt);
        return result(decision.status(),facts,reason,decision.explanation(),evidence,checkedAt);
    }
    /** 故障仍用注入时钟，测试不会因系统当前日期漂移；异常正文不返回模型或浏览器。 */
    public Assessment unavailable(ReturnReason reason){return result(AssessmentStatus.TEMPORARILY_UNAVAILABLE,null,reason==null?ReturnReason.UNKNOWN:reason,"订单或知识服务暂不可用，尚未完成检查。",List.of(),clock.instant());}
    private Assessment result(AssessmentStatus status,OrderFacts facts,ReturnReason reason,String explanation,List<PolicyEvidence> evidence,Instant time){return new Assessment(status,facts,reason,explanation,evidence,time,false);}
}
