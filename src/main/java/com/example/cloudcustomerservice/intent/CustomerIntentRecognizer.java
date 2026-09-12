package com.example.cloudcustomerservice.intent;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

@Service
public class CustomerIntentRecognizer {
    /** 本章的本地输入上限，按 Java String.length() 计数，避免超长文本继续调用模型。 */
    public static final int MAX_MESSAGE_LENGTH = 4000;

    private static final Logger log = LoggerFactory.getLogger(CustomerIntentRecognizer.class);
    private final ChatClient intentChatClient;

    public CustomerIntentRecognizer(@Qualifier("intentChatClient") ChatClient intentChatClient) {
        this.intentChatClient = intentChatClient;
    }

    public IntentRecognitionResult recognize(String message) {
        if (message == null || message.isBlank() || message.length() > MAX_MESSAGE_LENGTH) {
            return IntentRecognitionResult.fallback();
        }
        try {
            IntentRecognitionResult result = intentChatClient
                    .prompt()
                    .options(ChatOptions.builder().temperature(0.1).build())
                    .user(user -> user.text("""
                            请识别下面客户消息的业务意图。

                            <customer_message>
                            {message}
                            </customer_message>
                            """).param("message", message))
                    .call()
                    .entity(IntentRecognitionResult.class);
            return validate(result, message);
        }
        catch (RuntimeException ex) {
            // 异常 message 和堆栈也可能包含原文或模型输出，因此只记录长度和错误类型。
            log.warn("Intent recognition failed, messageLength={}, errorType={}",
                    message.length(), ex.getClass().getSimpleName());
            return IntentRecognitionResult.fallback();
        }
    }

    private IntentRecognitionResult validate(IntentRecognitionResult result, String message) {
        if (result == null || result.intent() == null
                || !Double.isFinite(result.confidence())
                || result.confidence() < 0.0 || result.confidence() > 1.0) {
            return IntentRecognitionResult.fallback();
        }

        String orderNo = result.orderNo();
        if (orderNo != null) {
            orderNo = orderNo.strip();
            // 只做本章的来源及基本长度校验，不证明订单存在或属于当前用户。
            if (orderNo.isEmpty() || orderNo.length() > 64 || !message.contains(orderNo)) {
                return IntentRecognitionResult.fallback();
            }
        }
        if (result.missingFields().stream().anyMatch(field -> !"orderNo".equals(field))) {
            return IntentRecognitionResult.fallback();
        }
        boolean needsOrder = switch (result.intent()) {
            case ORDER_QUERY, LOGISTICS_QUERY, REFUND_REQUEST -> true;
            default -> false;
        };
        // 当前契约只跟踪 orderNo，最终缺失状态由 Java 按实际提取结果计算。
        List<String> missingFields = needsOrder && orderNo == null ? List.of("orderNo") : List.of();
        return new IntentRecognitionResult(result.intent(), orderNo, result.confidence(), missingFields);
    }
}
