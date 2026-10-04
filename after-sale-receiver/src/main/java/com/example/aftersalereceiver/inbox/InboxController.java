package com.example.aftersalereceiver.inbox;

import java.util.UUID;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.CharacterCodingException;
import jakarta.servlet.http.HttpServletRequest;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import static com.example.aftersalereceiver.inbox.InboxModels.*;

/** 服务身份先通过 JWT 验证与固定映射，再有界读取正文并调用事务代理。 */
@RestController
@RequestMapping("/integration/after-sales/applications")
public class InboxController {

    private final InboxApplicationService service;

    private final String allowedSubject;
    private final TrustedSource source;

    public InboxController(
            InboxApplicationService service,

            @Value("${app.ingress.producer-subject}")
            String allowedSubject,

            @Value("${app.ingress.producer-id}")
            String producerId,

            @Value("${app.ingress.tenant-id}")
            String tenantId) {

        this.service = service;
        this.allowedSubject = allowedSubject;
        this.source = new TrustedSource(
                producerId,
                tenantId
        );
    }

    @PostMapping(consumes = "application/json", produces = "application/json")
    public Ack receive(
            @AuthenticationPrincipal Jwt jwt,

            @RequestHeader("Idempotency-Key")
            UUID headerEventId,

            HttpServletRequest request) throws Exception {

        if (jwt == null) {
            throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED
            );
        }

        if (!allowedSubject.equals(jwt.getSubject())) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "PRODUCER_NOT_ALLOWED"
            );
        }

        // 既检查 Content-Length，也限制实际读取量，覆盖 chunked 和虚假的长度声明。
        // 最多持有 64 KiB + 1 字节；不会先让 StringHttpMessageConverter 读取整个大请求。
        if (request.getContentLengthLong() > InboxProtocol.MAX_BYTES) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "EVENT_TOO_LARGE");
        }
        byte[] body = request.getInputStream().readNBytes(InboxProtocol.MAX_BYTES + 1);
        if (body.length > InboxProtocol.MAX_BYTES) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "EVENT_TOO_LARGE");
        }
        String json;
        try {
            json = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(body)).toString();
        } catch (CharacterCodingException error) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_EVENT_ENCODING");
        }
        return service.receive(
                source,
                headerEventId,
                json
        );
    }
}
