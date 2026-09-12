package com.example.cloudcustomerservice.intent;

/** 章节中的 Java switch 示例：仅返回流程说明，不调用业务系统、不执行退款。 */
public class CustomerIntentRouter {
    public String decideNextStep(IntentRecognitionResult result) {
        return switch (result.intent()) {
            case PRODUCT_CONSULTATION -> "进入商品咨询流程";
            case ORDER_QUERY -> "进入订单查询流程";
            case LOGISTICS_QUERY -> "进入物流查询流程";
            case REFUND_REQUEST -> "进入退款申请流程";
            case HUMAN_SERVICE -> "进入人工客服流程";
            case OTHER -> "返回客服服务范围说明";
            case UNKNOWN -> "继续向用户追问";
        };
    }
}
