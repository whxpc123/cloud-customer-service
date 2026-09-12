package com.example.cloudcustomerservice.intent;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/intents")
public class CustomerIntentController {
    private final CustomerIntentRecognizer recognizer;

    public CustomerIntentController(CustomerIntentRecognizer recognizer) {
        this.recognizer = recognizer;
    }

    @PostMapping("/recognize")
    public IntentRecognitionResult recognize(@RequestBody IntentRecognitionRequest request) {
        return recognizer.recognize(request.message());
    }
}
