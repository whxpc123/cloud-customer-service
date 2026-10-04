package com.example.cloudcustomerservice.outbox;

import java.io.IOException;
import com.example.cloudcustomerservice.reconcile.ReconcileModel;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/** 固定目的地 HTTP 适配器：不使用模型、不跟随重定向，只接受匹配当前事件的持久化回执。 */
@Component
@Profile("local & knowledge & outbox-delivery")
public class RemoteAfterSaleClient {

    /** 远端合同回执；PERSISTED 表示接收方已持久化，不表示售后审核通过。 */
    public record Ack(
            UUID eventId,
            UUID applicationId,
            String remoteApplicationId,
            String status
    ) {
    }

    /** 仅允许安全错误码进入日志或数据库，不记录令牌、正文或远端错误页面。 */
    public static final class DeliveryFailure
            extends RuntimeException {

        private final String code;
        private final boolean retryable;

        public DeliveryFailure(
                String code,
                boolean retryable) {
            super(code);
            this.code = code;
            this.retryable = retryable;
        }

        public String code() {
            return code;
        }

        public boolean retryable() {
            return retryable;
        }
    }

    private final RestClient client;
    private final ObjectMapper mapper;
    private final URI endpoint;

    /** 目标只能由服务端配置；HTTPS 或本机 127.0.0.1 HTTP，连接 2 秒、读取 8 秒。 */
    public RemoteAfterSaleClient(
            ObjectMapper mapper,
            @Value("${app.remote-after-sale.endpoint}")
            URI endpoint,
            @Value("${app.remote-after-sale.token}")
            String token) {

        boolean https =
                "https".equalsIgnoreCase(endpoint.getScheme());

        boolean localHttp =
                "http".equalsIgnoreCase(endpoint.getScheme())
                && "127.0.0.1".equals(endpoint.getHost());

        if ((!https && !localHttp)
                || endpoint.getHost() == null
                || endpoint.getUserInfo() != null
                || token == null
                || token.isBlank()
                || token.contains("\r") || token.contains("\n")
                || endpoint.getFragment() != null || endpoint.getQuery() != null) {

            throw new IllegalArgumentException(
                    "Invalid remote endpoint configuration"
            );
        }

        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .followRedirects(
                        HttpClient.Redirect.NEVER
                )
                .build();

        JdkClientHttpRequestFactory factory =
                new JdkClientHttpRequestFactory(httpClient);

        factory.setReadTimeout(Duration.ofSeconds(8));

        this.client = RestClient.builder()
                .requestFactory(factory)
                .defaultHeader(
                        HttpHeaders.AUTHORIZATION,
                        "Bearer " + token
                )
                .build();

        this.mapper = mapper;
        this.endpoint = endpoint;
    }

    /** 发送原 eventId 与原正文；网络未确认可重试，错误/超大/错号回执必须停止核查。 */
    public Ack deliver(OutboxStore.Claim claim) {

        try {
            return client.post()
                    .uri(endpoint)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(
                            "Idempotency-Key",
                            claim.eventId().toString()
                    )
                    .body(claim.payload())
                    .exchange((request, response) -> {

                        int status =
                                response.getStatusCode().value();

                        if (status == 408
                                || status == 429
                                || status >= 500) {

                            throw new DeliveryFailure(
                                    "HTTP_" + status,
                                    true
                            );
                        }

                        if (status != 200 && status != 201) {
                            throw new DeliveryFailure(
                                    "HTTP_" + status,
                                    false
                            );
                        }

                        byte[] bytes = response
                                .getBody()
                                .readNBytes(65_537);

                        if (bytes.length > 65_536) {
                            throw new DeliveryFailure(
                                    "ACK_TOO_LARGE",
                                    false
                            );
                        }

                        Ack ack;

                        try {
                            ack = mapper.readValue(
                                    bytes,
                                    Ack.class
                            );
                        }
                        catch (IOException ex) {
                            throw new DeliveryFailure(
                                    "INVALID_ACK",
                                    false
                            );
                        }

                        if (ack == null
                                || !claim.eventId()
                                        .equals(ack.eventId())
                                || !claim.applicationId()
                                        .equals(ack.applicationId())
                                || !"PERSISTED".equals(ack.status())
                                || ack.remoteApplicationId() == null
                                || ack.remoteApplicationId().isBlank()
                                || ack.remoteApplicationId().length()
                                        > 200) {

                            throw new DeliveryFailure(
                                    "ACK_MISMATCH",
                                    false
                            );
                        }

                        return ack;
                    });
        }
        catch (ResourceAccessException ex) {
            throw new DeliveryFailure(
                    "NETWORK_UNCONFIRMED",
                    true
            );
        }
    }
    /** 只向固定查询子路径提交原事件；不会失败后回退到 deliver，也不会自动重发。 */
    public ReconcileModel.Reply lookup(UUID eventId, String payload) {
        URI lookupEndpoint = URI.create(endpoint.toASCIIString().replaceAll("/+$", "") + "/lookup");
        try {
            return client.post().uri(lookupEndpoint).contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON).header("Idempotency-Key", eventId.toString())
                    .body(payload).exchange((request, response) -> {
                        int status = response.getStatusCode().value();
                        if (status != 200) throw new DeliveryFailure("LOOKUP_HTTP_" + status, false);
                        byte[] bytes = response.getBody().readNBytes(65_537);
                        if (bytes.length > 65_536) throw new DeliveryFailure("LOOKUP_RESPONSE_TOO_LARGE", false);
                        try {
                            // 查询回执是判定证据，拒绝重复键、未知字段和尾随对象，避免含混解释。
                            return mapper.copy().enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                                    .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                                            com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                                    .readValue(bytes, ReconcileModel.Reply.class);
                        } catch (IOException error) {
                            throw new DeliveryFailure("LOOKUP_INVALID_JSON", false);
                        }
                    });
        } catch (ResourceAccessException error) {
            throw new DeliveryFailure("LOOKUP_NETWORK_UNCONFIRMED", false);
        }
    }

}
