package com.example.cloudcustomerservice.conversation;

/**
 * 新建会话的响应；创建 ID 本身不调用模型或预写历史。
 *
 * @param conversationId 随机生成的 UUID 字符串，后续消息和清空操作复用此值
 */
public record CreateConversationResponse(String conversationId) {
}
