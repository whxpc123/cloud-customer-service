package com.example.cloudcustomerservice.conversation;

/**
 * 客户发送给当前会话的消息，身份取自演示请求头而不是正文。
 *
 * @param message 本轮客户原文，服务层要求非空且不超过 4000 个 UTF-16 代码单元
 */
public record SendMessageRequest(String message) {
}
