package com.example.cloudcustomerservice.intent;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

public record IntentRecognitionResult(
        @JsonProperty(required = true) CustomerIntent intent,
        String orderNo,
        @JsonProperty(required = true) double confidence,
        List<String> missingFields
) {
    public IntentRecognitionResult {
        missingFields = missingFields == null ? List.of() : List.copyOf(missingFields);
    }

    public static IntentRecognitionResult fallback() {
        return new IntentRecognitionResult(CustomerIntent.UNKNOWN, null, 0.0, List.of());
    }
}
