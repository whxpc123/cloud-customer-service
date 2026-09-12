package com.example.cloudcustomerservice.intent;

/** 第三章：由业务定义稳定分类，OTHER 与 UNKNOWN 分别表示范围外和无法确定。 */
public enum CustomerIntent {
    /** 商品参数、规格、库存及使用方式咨询。 */
    PRODUCT_CONSULTATION,
    /** 订单创建、支付、取消等状态查询。 */
    ORDER_QUERY,
    /** 发货、运输、签收及物流异常咨询。 */
    LOGISTICS_QUERY,
    /** 提出退货或退款申请，不代表获准或已执行退款。 */
    REFUND_REQUEST,
    /** 要求人工或真人客服。 */
    HUMAN_SERVICE,
    /** 明确与商城客服无关。 */
    OTHER,
    /** 信息不足、语义模糊、模型调用或转换失败。 */
    UNKNOWN
}
