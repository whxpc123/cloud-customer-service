package com.example.cloudcustomerservice.conversation;

import java.util.List;
import com.example.cloudcustomerservice.intent.IntentRecognitionResult;
import com.example.cloudcustomerservice.tool.OrderLookupResult;

public record ChatTurnResponse(String conversationId, IntentRecognitionResult intent, String answer,
        List<OrderLookupResult> orderLookups) {
    public ChatTurnResponse {
        orderLookups = orderLookups == null ? List.of() : List.copyOf(orderLookups);
    }
}
