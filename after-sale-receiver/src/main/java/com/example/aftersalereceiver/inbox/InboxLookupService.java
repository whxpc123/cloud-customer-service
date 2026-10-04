package com.example.aftersalereceiver.inbox;

import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import static com.example.aftersalereceiver.inbox.InboxModels.*;

/** 查询权威数据库的单条快照；未观察到不等于失败，不调用任何创建逻辑。 */
@Service
public class InboxLookupService {

    public record Reply(
            UUID eventId,
            String state,
            Ack receipt
    ) {
    }

    private record Stored(
            boolean samePayload,
            String status,
            String receiptJson,
            UUID remoteId,
            UUID sourceApplicationId,
            UUID sourceOperationId
    ) {
    }

    private final JdbcTemplate jdbc;
    private final InboxProtocol protocol;
    private final ObjectMapper mapper;

    public InboxLookupService(
            JdbcTemplate jdbc,
            InboxProtocol protocol,
            ObjectMapper mapper) {

        this.jdbc = jdbc;
        this.protocol = protocol;
        this.mapper = mapper;
    }

    @Transactional(
            propagation = Propagation.REQUIRES_NEW,
            isolation = Isolation.READ_COMMITTED,
            readOnly = true,
            timeout = 3
    )
    public Reply lookup(
            TrustedSource source,
            UUID headerEventId,
            String json) {

        CreateApplicationEvent event =
                protocol.parse(json);

        if (!event.eventId().equals(headerEventId)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "IDEMPOTENCY_KEY_MISMATCH"
            );
        }

        if (!source.tenantId().equals(event.tenantId())) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "TENANT_NOT_ALLOWED"
            );
        }

        List<Stored> rows = jdbc.query("""
                SELECT
                    i.payload = CAST(? AS jsonb) AS same_payload,
                    i.status,
                    i.receipt::text AS receipt_json,
                    a.remote_application_id,
                    a.source_application_id,
                    a.source_operation_id
                FROM rx_inbox i
                LEFT JOIN rx_after_sale_application a
                  ON a.producer_id = i.producer_id
                 AND a.tenant_id = i.tenant_id
                 AND a.source_event_id = i.event_id
                WHERE i.producer_id = ?
                  AND i.tenant_id = ?
                  AND i.event_id = ?
                """,
                (rs, rowNum) -> new Stored(
                        rs.getBoolean("same_payload"),
                        rs.getString("status"),
                        rs.getString("receipt_json"),
                        rs.getObject(
                                "remote_application_id",
                                UUID.class
                        ),
                        rs.getObject(
                                "source_application_id",
                                UUID.class
                        ),
                        rs.getObject(
                                "source_operation_id",
                                UUID.class
                        )
                ),
                json,
                source.producerId(),
                source.tenantId(),
                event.eventId()
        );

        if (rows.isEmpty()) {
            return new Reply(
                    event.eventId(),
                    "NOT_OBSERVED",
                    null
            );
        }

        if (rows.size() != 1) {
            return inconsistent(event.eventId());
        }

        Stored stored = rows.get(0);

        if (!stored.samePayload()) {
            return new Reply(
                    event.eventId(),
                    "PAYLOAD_CONFLICT",
                    null
            );
        }

        if (!"PROCESSED".equals(stored.status())
                || stored.receiptJson() == null
                || stored.remoteId() == null
                || !event.applicationId()
                        .equals(stored.sourceApplicationId())
                || !event.operationId()
                        .equals(stored.sourceOperationId())) {

            return inconsistent(event.eventId());
        }

        try {
            Ack ack = mapper.readValue(
                    stored.receiptJson(),
                    Ack.class
            );

            if (ack == null
                    || !event.eventId().equals(ack.eventId())
                    || !event.applicationId()
                            .equals(ack.applicationId())
                    || !stored.remoteId().toString()
                            .equals(ack.remoteApplicationId())
                    || !"PERSISTED".equals(ack.status())) {

                return inconsistent(event.eventId());
            }

            return new Reply(
                    event.eventId(),
                    "PERSISTED",
                    ack
            );
        }
        catch (JsonProcessingException ex) {
            return inconsistent(event.eventId());
        }
    }

    private Reply inconsistent(UUID eventId) {
        return new Reply(
                eventId,
                "INCONSISTENT",
                null
        );
    }
}
