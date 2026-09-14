package com.example.cloudcustomerservice.tool;

/**
 * 工具查询结果码，用于区分成功、缺参、格式错误、无可访问记录和依赖故障。
 * 它与订单状态不同：FOUND 表示查询成功，具体是否发货要看结果中的 status。
 */
public enum OrderLookupCode {
    /**
     * 查到当前用户可访问的订单。
     */
    FOUND,
    /**
     * 缺少目标订单号，需要追问。
     */
    MISSING_ORDER_NO,
    /**
     * 订单号未通过格式或长度校验。
     */
    INVALID_ORDER_NO,
    /**
     * 未找到可访问的记录，不区分不存在与非本人。
     */
    NOT_FOUND,
    /**
     * 缺少有效的应用侧演示身份。
     */
    AUTHENTICATION_REQUIRED,
    /**
     * 订单依赖异常，本次无法确认状态。
     */
    TEMPORARILY_UNAVAILABLE
}
