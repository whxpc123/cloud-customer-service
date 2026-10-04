package com.example.aftersalereceiver.inbox;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.*;

/** 无数据库的协议边界；真实事务、JWT 和 HTTP 验证另见 InboxIntegrationTest。 */
class InboxProtocolTest {
    static final String PAYLOAD = """
        {"schemaVersion":1,"eventId":"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
        "eventType":"AFTER_SALE_APPLICATION_CREATED","applicationId":"bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
        "operationId":"cccccccc-cccc-4ccc-8ccc-cccccccccccc","tenantId":"tenant-yunshan",
        "orderNo":"A10001","draftVersion":1,"userStatement":{"userDescription":"按钮按不动。","requestedHandling":"申请审核。"},
        "occurredAt":"2026-10-04T08:00:00+08:00"}
        """;
    final InboxProtocol protocol = new InboxProtocol(JsonMapper.builder().addModule(new JavaTimeModule()).build(),
            Validation.buildDefaultValidatorFactory().getValidator());

    @Test void validVersion() { assertThat(protocol.parse(PAYLOAD).draftVersion()).isEqualTo(1); }

    @ParameterizedTest
    @ValueSource(strings = {"null", "{}", "[]", "true", "{", "trailing", "duplicate", "unknown", "nestedUnknown",
            "nestedDuplicate", "schema", "fraction", "stringNumber", "missing", "blank", "order", "time", "negative", "longText", "nullStatement"})
    void strictInvalidInput(String kind) {
        String value = switch (kind) {
            case "trailing" -> PAYLOAD + "{}";
            case "duplicate" -> PAYLOAD.replace("\"schemaVersion\":1", "\"schemaVersion\":1,\"schemaVersion\":1");
            case "unknown" -> PAYLOAD.replace("\"schemaVersion\":1", "\"secret\":true,\"schemaVersion\":1");
            case "nestedUnknown" -> PAYLOAD.replace("\"userDescription\":", "\"approved\":true,\"userDescription\":");
            case "nestedDuplicate" -> PAYLOAD.replace("\"userDescription\":", "\"userDescription\":\"a\",\"userDescription\":");
            case "schema" -> PAYLOAD.replace("\"schemaVersion\":1", "\"schemaVersion\":2");
            case "fraction" -> PAYLOAD.replace("\"draftVersion\":1", "\"draftVersion\":1.5");
            case "stringNumber" -> PAYLOAD.replace("\"draftVersion\":1", "\"draftVersion\":\"1\"");
            case "missing" -> PAYLOAD.replace("\"draftVersion\":1,", "");
            case "blank" -> PAYLOAD.replace("按钮按不动。", "  ");
            case "order" -> PAYLOAD.replace("A10001", "B10001");
            case "time" -> PAYLOAD.replace("2026-10-04T08:00:00+08:00", "昨天");
            case "negative" -> PAYLOAD.replace("\"draftVersion\":1", "\"draftVersion\":0");
            case "longText" -> PAYLOAD.replace("按钮按不动。", "中".repeat(1001));
            case "nullStatement" -> PAYLOAD.replace("{\"userDescription\":\"按钮按不动。\",\"requestedHandling\":\"申请审核。\"}", "null");
            default -> kind;
        };
        assertThatThrownBy(() -> protocol.parse(value)).isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException)e).getStatusCode().value()).isEqualTo(400));
    }
    @Test void byteLimitAndTrustedIdentity() {
        assertThatThrownBy(() -> protocol.parse("中".repeat(22000))).isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException)e).getStatusCode().value()).isEqualTo(413));
        assertThatThrownBy(() -> new InboxModels.TrustedSource("bad/producer", "tenant-yunshan")).isInstanceOf(IllegalArgumentException.class);
    }
}
