package com.example.cloudcustomerservice.intent;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 第三章的独立意图实验接口，便于直接观察结构化 JSON。
 * 可视化客服已经通过会话接口内部调用识别服务，前端聊天不必再请求此接口。
 */
@RestController
@RequestMapping("/api/intents")
public class CustomerIntentController {
    private final CustomerIntentRecognizer recognizer;

    /**
     * 注入识别器，保留独立实验入口同时让会话服务复用相同分类实现。
     */
    public CustomerIntentController(CustomerIntentRecognizer recognizer) {
        this.recognizer = recognizer;
    }

    /**
     * 分类当前请求原文；业务上无法识别时返回 UNKNOWN 结构，而不是编造类别。
     */
    @PostMapping("/recognize")
    public IntentRecognitionResult recognize(@RequestBody IntentRecognitionRequest request) {
        return recognizer.recognize(request.message());
    }
}
