package com.example.cloudcustomerservice.conversation;

import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/conversations")
public class CustomerConversationController {
    private final CustomerConversationService service;

    public CustomerConversationController(CustomerConversationService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CreateConversationResponse createConversation() {
        return new CreateConversationResponse(UUID.randomUUID().toString());
    }

    @PostMapping("/{conversationId}/messages")
    public ChatTurnResponse sendMessage(@PathVariable String conversationId,
            @RequestHeader(value = "X-Demo-User-Id", required = false) Long currentUserId,
            @RequestBody SendMessageRequest request) {
        return service.chat(conversationId, currentUserId, request.message());
    }

    @DeleteMapping("/{conversationId}/memory")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void clearMemory(@PathVariable String conversationId,
            @RequestHeader(value = "X-Demo-User-Id", required = false) Long currentUserId) {
        service.clearMemory(conversationId, currentUserId);
    }
}
