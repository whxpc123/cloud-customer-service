package com.example.cloudcustomerservice.conversation;

import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/**
 * 第四、五章的对话 HTTP 入口：新建会话、发送消息、清空记忆。
 * 客户端一次发送即可取得自然语言回复、结构化意图和真实工具执行记录。
 * X-Demo-User-Id 仅用于本地演示身份选择，不能当作生产认证机制。
 */
@RestController
@RequestMapping("/api/conversations")
public class CustomerConversationController {
    private final CustomerConversationService service;

    /**
     * 注入一轮会话编排服务，HTTP 层不重复实现分类、记忆和工具调用。
     */
    public CustomerConversationController(CustomerConversationService service) {
        this.service = service;
    }

    /**
     * 分配新的会话编号并返回 201；历史在首次发送消息时才由 Advisor 写入。
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CreateConversationResponse createConversation() {
        return new CreateConversationResponse(UUID.randomUUID().toString());
    }

    /**
     * 将路径 ID、演示身份与正文交给会话服务；HTTP 层不自行调用两遍分类接口。
     */
    @PostMapping("/{conversationId}/messages")
    public ChatTurnResponse sendMessage(@PathVariable String conversationId,
            @RequestHeader(value = "X-Demo-User-Id", required = false) Long currentUserId,
            @RequestBody SendMessageRequest request) {
        return service.chat(conversationId, currentUserId, request.message());
    }

    /**
     * 清空当前身份下该会话的模型记忆，成功返回 204 无正文。
     * 不删除浏览器保存的页面记录，也不清空其他用户的同名外部会话。
     */
    @DeleteMapping("/{conversationId}/memory")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void clearMemory(@PathVariable String conversationId,
            @RequestHeader(value = "X-Demo-User-Id", required = false) Long currentUserId) {
        service.clearMemory(conversationId, currentUserId);
    }
}
