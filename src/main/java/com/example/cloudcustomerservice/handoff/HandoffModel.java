package com.example.cloudcustomerservice.handoff;

import java.time.OffsetDateTime;
import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;

/** 第十七章持久化接待契约；身份由认证适配器生成，状态和版本来自数据库。 */
public final class HandoffModel {
    private HandoffModel() { }
    public enum Mode { BOT, WAITING_HUMAN, HUMAN_ACTIVE, CLOSED }
    public record Actor(String tenantId, long accountId) {
        public Actor {
            if (tenantId == null || !tenantId.matches("[a-zA-Z0-9_-]{1,64}") || accountId <= 0)
                throw new IllegalArgumentException("无效的已验证身份");
        }
    }
    /** 有领取人和时间才代表被领取；不提供未实现的在线状态、排队位置或预计等待时间。 */
    public record Receipt(UUID conversationId, UUID handoffId, Mode mode, long version, Long assignedAgentId,
            OffsetDateTime requestedAt, OffsetDateTime acceptedAt, OffsetDateTime closedAt, String message) { }
    public record Message(long id, String role, String content, JsonNode payload, long version, OffsetDateTime createdAt) { }
    /** 游标按持久化消息序号前进；分页不把模型窗口冒充完整聊天记录。 */
    public record History(Receipt receipt, List<Message> messages, long nextCursor, boolean hasMore) { }
    public record Turn(Receipt receipt, UUID generationId, long userMessageId, boolean replay,
            List<org.springframework.ai.chat.messages.Message> history) { }
    /** published=false 时不返回候选 answer/payload，前端只能刷新已提交历史。 */
    public record Delivery(Receipt receipt, boolean published, String deliveryStatus, Message message) { }
}
