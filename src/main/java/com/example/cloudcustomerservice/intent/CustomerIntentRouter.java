package com.example.cloudcustomerservice.intent;

/**
 * 演示将结构化枚举接入 Java 分支逻辑；返回的文字仅说明建议流程。
 * 本类没有连接人工坐席、订单或退款系统，不能把路由说明当成操作执行结果。
 */
public class CustomerIntentRouter {
    /**
     * 按已校验的意图选择流程说明；UNKNOWN 继续追问，退款类也只做咨询路由。
     */
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
