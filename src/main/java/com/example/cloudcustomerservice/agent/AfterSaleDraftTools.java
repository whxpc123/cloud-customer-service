package com.example.cloudcustomerservice.agent;

import com.example.cloudcustomerservice.aftersale.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.*;
import org.springframework.ai.tool.annotation.Tool;
import static com.example.cloudcustomerservice.aftersale.AfterSaleModel.*;

/** 每轮实例绑定服务器身份和用户指定的订单。工具参数为空，模型没有机会改用户、租户或订单。 */
public final class AfterSaleDraftTools {
    private final ReturnAssessmentService service;
    private final Actor actor;
    private final String orderNo;
    private final ReturnReason reason;
    private final boolean inspectionOnly;
    private final DraftRunBudget budget;
    private final List<String> events = new CopyOnWriteArrayList<>();
    private volatile Assessment assessment;
    private final Function<Supplier<Assessment>,Assessment> lookup;
    private volatile boolean inspected, templateRead, limitExceeded;
    private volatile int calls;

    AfterSaleDraftTools(ReturnAssessmentService service, Actor actor, String orderNo, ReturnReason reason,
            boolean inspectionOnly, DraftRunBudget budget) {
        this(service,actor,orderNo,reason,inspectionOnly,budget,Supplier::get);
    }
    AfterSaleDraftTools(ReturnAssessmentService service, Actor actor, String orderNo, ReturnReason reason,
            boolean inspectionOnly, DraftRunBudget budget, Function<Supplier<Assessment>,Assessment> lookup) {
        this.lookup=lookup;
        this.service=service; this.actor=actor; this.orderNo=orderNo; this.reason=reason;
        this.inspectionOnly=inspectionOnly; this.budget=budget;
    }
    /** 缓存也计入八次调用预算；结果仅本轮有效，下一次运行重新查事实和政策。 */
    @Tool(name="inspectAfterSale", resultConverter=AfterSaleToolResultConverter.class,
            description="只读核验服务器已绑定订单的归属、事实、适用政策和程序结论。用户质量投诉不是质量核验结果。不会提交、批准或退款。")
    public synchronized Assessment inspectAfterSale() {
        enter();
        if (inspected) { events.add("inspectAfterSale:CACHED"); return assessment; }
        inspected=true;
        try {
            var result=lookup.apply(()->service.assess(actor,orderNo,reason));
            budget.remainingMillis();
            assessment=result;
        }
        catch (RuntimeException ex) {
            // 失败也缓存；不把 SQL、凭证或供应商异常正文交给模型，不在本轮反复冲击故障服务。
            assessment=service.unavailable(reason);
        }
        events.add("inspectAfterSale:" + assessment.status());
        return assessment;
    }
    /** 返回明确的结构化阻断结果，模型可以据此停止；不能靠提示词代替 Java 前置条件。 */
    @Tool(name="readDraftTemplate", description="核验成功且有适用政策后，读取候选草稿的结构要求。只检查模式禁止生成草稿；不创建或提交任何业务记录。")
    public synchronized Map<String,Object> readDraftTemplate() {
        enter();
        if (inspectionOnly || !canDraft()) {
            events.add("readDraftTemplate:BLOCKED");
            return Map.of("available",false,"message","需要先取得可访问订单和适用依据；只检查模式不提供草稿模板。");
        }
        events.add(templateRead ? "readDraftTemplate:CACHED" : "readDraftTemplate:READ");
        templateRead=true;
        return Map.of("available",true,"instructions","""
                标题：候选草稿，未经审核，尚未提交。
                依次列出：订单号；用户描述（明确标注为用户诉求）；系统已核实事实；适用政策来源及版本；
                尚未核验或缺少的信息；用户希望的处理方式。只复述实际工具返回的数据，不编造申请编号。
                质量为 UNVERIFIED 时必须说明质量问题尚未核验，不能承诺获批或退款。
                政策没有明确材料清单时写“材料要求待人工确认”，不新增拍照、寄回等强制步骤。
                末尾：本次仅整理候选草稿，没有提交申请、批准退货或执行退款。
                """);
    }
    private void enter() {
        budget.remainingMillis();
        if (++calls > 8) {
            if (!limitExceeded) events.add("TOOL_LIMIT_EXCEEDED");
            limitExceeded=true;
            throw new IllegalStateException("工具调用次数已达上限");
        }
    }
    boolean canDraft() {
        return assessment != null && assessment.verifiedFacts()!=null && !assessment.evidence().isEmpty()
                && !Set.of(AssessmentStatus.NOT_ACCESSIBLE,AssessmentStatus.NO_EVIDENCE,
                    AssessmentStatus.TEMPORARILY_UNAVAILABLE,AssessmentStatus.NEED_ORDER_NO).contains(assessment.status());
    }
    Assessment assessment() { return assessment; }
    boolean templateRead() { return templateRead; }
    boolean limitExceeded() { return limitExceeded; }
    int calls() { return calls; }
    List<String> events() { return List.copyOf(events); }
}
