package com.example.cloudcustomerservice.conversation;

import java.util.List;
import com.example.cloudcustomerservice.intent.IntentRecognitionResult;
import com.example.cloudcustomerservice.tool.OrderLookupResult;

/**
 * 一轮客服调用的完整响应，前端只需调用一次会话接口。
 *
 * @param conversationId 客户端会话 ID，不含内部用户前缀
 * @param intent 通过 Java 校验后的结构化意图
 * @param answer 客服模型的自然语言回复
 * @param orderLookups 本轮真正执行的订单查询结果，空列表表示未执行工具
 */
public record ChatTurnResponse(String conversationId, IntentRecognitionResult intent, String answer,
        List<OrderLookupResult> orderLookups) {
    /**
     * 工具未执行时使用空列表；对执行记录作防御性复制，避免后续追加改变已返回响应。
     */
    public ChatTurnResponse {
        orderLookups = orderLookups == null ? List.of() : List.copyOf(orderLookups);
    }
}
