package com.example.cloudcustomerservice.aftersale;

import java.util.*;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.*;
import static com.example.cloudcustomerservice.aftersale.AfterSaleModel.*;

/** 每轮新建，只保存本轮实际执行结果；模型可填参数不包括身份、政策版本或截止时间。 */
public final class AfterSaleTools {
    private final ReturnAssessmentService service;
    private final List<Assessment> assessments=new ArrayList<>();
    private int calls;
    public AfterSaleTools(ReturnAssessmentService service){this.service=service;}
    @Tool(name="inspectReturnRequest",resultConverter=AfterSaleToolResultConverter.class,description="查询当前账户自己的订单，取得适用政策并执行只读售后预检查。claimedReason只是用户诉求，不是已核验事实。不会创建申请、批准退货或执行退款。订单号必须来自用户当前消息或无歧义的用户历史，不得编造。")
    public synchronized Assessment inspect(@ToolParam(description="用户明确提及的订单号，A 加五位数字",required=false) String orderNo,
            @ToolParam(description="用户原因 QUALITY_ISSUE、CHANGE_OF_MIND、UNKNOWN；不明确用UNKNOWN",required=false) ReturnReason claimedReason,ToolContext context){
        if(context==null||!(context.getContext().get("actor") instanceof Actor actor))throw new SecurityException("Missing server actor");
        if(++calls>3)throw new IllegalStateException("Tool call budget exceeded");
        Object allowed=context.getContext().get("allowedOrders");
        String normalized=orderNo==null?"":orderNo.strip().toUpperCase(Locale.ROOT);
        // 即使模型猜中了某个属于当前用户的编号，也不能查询用户没有指定的订单。
        if(!(allowed instanceof Set<?> ids)||!ids.contains(normalized))throw new IllegalArgumentException("Order not specified by user");
        Assessment assessment;
        try {assessment=service.assess(actor,orderNo,claimedReason);}
        catch(RuntimeException ex){org.slf4j.LoggerFactory.getLogger(AfterSaleTools.class).warn("[AFTER SALE] requestId={} errorType={}",context.getContext().get("requestId"),ex.getClass().getSimpleName());assessment=service.unavailable(claimedReason);}
        assessments.add(assessment);return assessment;
    }
    public synchronized List<Assessment> assessments(){return List.copyOf(assessments);}
}
