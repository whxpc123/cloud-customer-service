package com.example.cloudcustomerservice.conversation;

import com.example.cloudcustomerservice.intent.IntentRecognitionResult;

public record ChatTurnResponse(String conversationId, IntentRecognitionResult intent, String answer) {
}
