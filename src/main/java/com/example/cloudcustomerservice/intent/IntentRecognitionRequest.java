package com.example.cloudcustomerservice.intent;

/**
 * 独立意图接口的 JSON 请求体，不携带历史或用户身份。
 *
 * @param message 要分类的客户原文，非法输入会降级 UNKNOWN
 */
public record IntentRecognitionRequest(String message) {
}
