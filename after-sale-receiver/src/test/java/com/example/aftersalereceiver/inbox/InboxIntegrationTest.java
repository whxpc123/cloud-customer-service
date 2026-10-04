package com.example.aftersalereceiver.inbox;

import com.example.aftersalereceiver.AfterSaleReceiverApplication;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.sql.Connection;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import static com.example.aftersalereceiver.inbox.InboxModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

/**
 * 真正的 PostgreSQL、Spring 事务代理、嵌入式 HTTP 与签名 JWT；不使用 mockJwt 绕过验签。
 * 故障触发器仅创建在此临时容器。线程交错通过数据库实际锁等待确认，不猜测 sleep 时长。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local-jwt")
@Import(InboxIntegrationTest.Database.class)
@EnabledIfEnvironmentVariable(named = "RUN_RECEIVER_TESTS", matches = "true")
class InboxIntegrationTest {
    static final String PATH = "/integration/after-sales/applications";
    static final TrustedSource SOURCE = new TrustedSource("yunshan-customer-service", "tenant-yunshan");
    static final KeyPair KEYS = keys();
    static final Path PUBLIC_KEY = publicFile();
    static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    @TestConfiguration(proxyBeanMethods = false)
    static class Database {
        @Bean @ServiceConnection PostgreSQLContainer<?> postgres() {
            return new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:0.8.2-pg17@sha256:feb68f4f15446397d8cac7f4fe48fe4586de83160d1fc48b46283312d1a33966")
                    .asCompatibleSubstituteFor("postgres")).withDatabaseName("receiver_acceptance");
        }
    }
    @DynamicPropertySource static void configuration(DynamicPropertyRegistry properties) {
        properties.add("app.local-jwt.public-key", () -> PUBLIC_KEY.toUri().toString());
        properties.add("SERVICE_TOKEN_ISSUER", () -> "urn:yunshan:local-service-issuer");
    }
    @Autowired InboxApplicationService service;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired ObjectMapper mapper;
    @Autowired PostgreSQLContainer<?> postgres;
    @LocalServerPort int port;

    /** 每例只清理本测试容器，不接触 .local 指向的两个应用库。 */
    @BeforeEach void clear() { jdbc.execute("TRUNCATE rx_after_sale_application, rx_inbox"); }

    @Test void threeRequestsReplayAfterStateChangeAndTokenRotation() throws Exception {
        String body = payload(); UUID event = id(body, "eventId");
        var first = post(port, body, event, token("valid"));
        assertThat(first.statusCode()).isEqualTo(200);
        jdbc.update("UPDATE rx_after_sale_application SET status='UNDER_REVIEW'");
        var second = post(port, body, event, token("valid"));
        var third = post(port, body, event, token("valid"));
        assertThat(mapper.readTree(second.body())).isEqualTo(mapper.readTree(first.body()));
        assertThat(mapper.readTree(third.body())).isEqualTo(mapper.readTree(first.body()));
        assertThat(count("rx_after_sale_application")).isEqualTo(1);
        assertThat(count("rx_inbox")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM rx_after_sale_application", String.class)).isEqualTo("UNDER_REVIEW");
        assertThat(jdbc.queryForObject("SELECT status FROM rx_inbox", String.class)).isEqualTo("PROCESSED");
    }

    @Test void objectOrderAndWhitespaceReplayButChangedValueConflicts() throws Exception {
        String body = payload(); UUID event = id(body, "eventId");
        Ack ack = service.receive(SOURCE, event, body);
        Map<String,Object> ordered = new TreeMap<>(mapper.readValue(body, Map.class));
        assertThat(service.receive(SOURCE, event, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(ordered))).isEqualTo(ack);
        var conflict = post(port, body.replace("按钮按不动。", "整机无法开机。"), event, token("valid"));
        assertThat(conflict.statusCode()).isEqualTo(409);
        assertThat(conflict.body()).contains("EVENT_PAYLOAD_CONFLICT");
        assertThat(service.receive(SOURCE, event, body)).isEqualTo(ack);
        assertThat(jdbc.queryForObject("SELECT user_statement->>'userDescription' FROM rx_after_sale_application", String.class)).isEqualTo("按钮按不动。");
    }

    @ParameterizedTest @ValueSource(strings = {"applicationId", "operationId"})
    void changedEventWithSameBusinessIdentityRollsBackNewInbox(String field) throws Exception {
        String original = payload(); service.receive(SOURCE, id(original,"eventId"), original);
        ObjectNode next = (ObjectNode)mapper.readTree(payload()); next.put(field, id(original, field).toString());
        var response = post(port, next.toString(), id(next.toString(), "eventId"), token("valid"));
        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(response.body()).contains("SOURCE_APPLICATION_OR_OPERATION_CONFLICT");
        assertThat(count("rx_inbox")).isEqualTo(1); assertThat(count("rx_after_sale_application")).isEqualTo(1);
    }

    @Test void sourceAndTenantAreIndependentAtServiceBoundary() throws Exception {
        String body = payload(); UUID event = id(body,"eventId");
        Ack first = service.receive(SOURCE,event,body);
        Ack otherProducer = service.receive(new TrustedSource("other-producer", SOURCE.tenantId()), event,body);
        Ack otherTenant = service.receive(new TrustedSource(SOURCE.producerId(), "other-tenant"),event,body.replace(SOURCE.tenantId(),"other-tenant"));
        assertThat(Set.of(first.remoteApplicationId(),otherProducer.remoteApplicationId(),otherTenant.remoteApplicationId())).hasSize(3);
        assertThat(count("rx_inbox")).isEqualTo(3);
    }

    @ParameterizedTest @ValueSource(strings = {"subject", "scope", "tenant"})
    void unauthorizedSourceNeverReceivesAnExistingReceipt(String kind) throws Exception {
        String body=payload(); UUID event=id(body,"eventId"); service.receive(SOURCE,event,body);
        var response=post(port,kind.equals("tenant") ? body.replace("tenant-yunshan","tenant-evil") : body,event,
                token(kind.equals("tenant") ? "valid" : kind));
        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.body()).doesNotContain("remoteApplicationId");
        assertThat(count("rx_inbox")).isEqualTo(1);
    }

    @ParameterizedTest @ValueSource(strings = {"issuer", "audience", "missingAudience", "expired", "future", "missingExpiry", "signature", "missing"})
    void invalidJwtIsRejectedBeforeBusinessWork(String kind) throws Exception {
        String body=payload(); var response=post(port,body,id(body,"eventId"),kind.equals("missing") ? null : token(kind));
        assertThat(response.statusCode()).isEqualTo(401); assertThat(count("rx_inbox")).isZero();
    }

    @Test void wrongKeyMissingKeyAndUnsupportedMethods() throws Exception {
        String body=payload();
        assertThat(post(port,body,UUID.randomUUID(),token("valid")).statusCode()).isEqualTo(400);
        assertThat(post(port,body,null,token("valid")).statusCode()).isEqualTo(400);
        var get=HTTP.send(HttpRequest.newBuilder(uri(port)).header("Authorization","Bearer "+token("valid")).GET().build(),HttpResponse.BodyHandlers.ofString());
        assertThat(get.statusCode()).isEqualTo(403);
        assertThat(count("rx_inbox")).isZero();
    }

    @Test void unsupportedContentTypeAndMalformedHeaderStayClientErrors() throws Exception {
        String body=payload();
        var request=HttpRequest.newBuilder(uri(port)).header("Authorization","Bearer "+token("valid"))
                .header("Idempotency-Key",id(body,"eventId").toString()).header("Content-Type","text/plain")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        assertThat(HTTP.send(request,HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(415);
        var malformed=HttpRequest.newBuilder(uri(port)).header("Authorization","Bearer "+token("valid"))
                .header("Idempotency-Key","not-a-uuid").header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        assertThat(HTTP.send(malformed,HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(400);
        assertThat(count("rx_inbox")).isZero();
    }

    @Test void realHttpRejectsMalformedJsonAndLargeChunkedOrFixedLengthBodies() throws Exception {
        String body=payload(); UUID event=id(body,"eventId");
        for (String invalid : List.of(body+"{}", body.replace("\"schemaVersion\":1", "\"schemaVersion\":1,\"schemaVersion\":1"),
                body.replace("\"schemaVersion\":1", "\"schemaVersion\":2"), body.replace("\"schemaVersion\":1", "\"x\":1,\"schemaVersion\":1"))) {
            assertThat(post(port,invalid,event,token("valid")).statusCode()).isEqualTo(400);
        }
        byte[] huge = " ".repeat(65537).getBytes(StandardCharsets.UTF_8);
        assertThat(post(port,new String(huge,StandardCharsets.UTF_8),event,token("valid")).statusCode()).isEqualTo(413);
        var chunked=HttpRequest.newBuilder(uri(port)).header("Authorization","Bearer "+token("valid"))
                .header("Content-Type","application/json").header("Idempotency-Key",event.toString())
                .POST(HttpRequest.BodyPublishers.ofInputStream(() -> new java.io.ByteArrayInputStream(huge))).build();
        assertThat(HTTP.send(chunked,HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(413);
        var badUtf8=HttpRequest.newBuilder(uri(port)).header("Authorization","Bearer "+token("valid"))
                .header("Content-Type","application/json").header("Idempotency-Key",event.toString())
                .POST(HttpRequest.BodyPublishers.ofByteArray(new byte[]{(byte)0xc3,(byte)0x28})).build();
        assertThat(HTTP.send(badUtf8,HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(400);
        assertThat(count("rx_inbox")).isZero();
    }

    @ParameterizedTest @ValueSource(booleans = {false,true})
    void failureAfterApplicationInsertOrAtCommitRollsBackEverything(boolean commitTime) throws Exception {
        // 函数在失败点检查业务 INSERT 已完成，防止测试只覆盖“根本没写入”的情况。
        jdbc.execute("""
            CREATE FUNCTION rx_test_fail() RETURNS trigger LANGUAGE plpgsql AS $$
            BEGIN
              IF NOT EXISTS(SELECT 1 FROM rx_after_sale_application WHERE source_event_id=NEW.event_id) THEN
                RAISE EXCEPTION 'test setup did not reach application insert';
              END IF;
              RAISE EXCEPTION 'synthetic receipt failure';
            END $$
            """);
        jdbc.execute(commitTime
                ? "CREATE CONSTRAINT TRIGGER rx_test_failure AFTER UPDATE ON rx_inbox DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION rx_test_fail()"
                : "CREATE TRIGGER rx_test_failure BEFORE UPDATE ON rx_inbox FOR EACH ROW EXECUTE FUNCTION rx_test_fail()");
        String body=payload(); UUID event=id(body,"eventId");
        try {
            var response=post(port,body,event,token("valid"));
            assertThat(response.statusCode()).isEqualTo(503);
            assertThat(response.body()).doesNotContain("PERSISTED","synthetic","SELECT");
            assertThat(count("rx_inbox")).isZero(); assertThat(count("rx_after_sale_application")).isZero();
        } finally {
            jdbc.execute("DROP TRIGGER rx_test_failure ON rx_inbox"); jdbc.execute("DROP FUNCTION rx_test_fail()");
        }
        assertThat(post(port,body,event,token("valid")).statusCode()).isEqualTo(200);
        assertThat(count("rx_after_sale_application")).isEqualTo(1);
    }

    @ParameterizedTest @ValueSource(booleans = {true,false})
    void concurrentDuplicateWaitsThenReplaysOrTakesOverAfterRollback(boolean commit) throws Exception {
        String body=payload(); UUID event=id(body,"eventId"); UUID application=id(body,"applicationId");
        UUID remote=UUID.randomUUID(); Ack previous=new Ack(event,application,remote.toString(),"PERSISTED");
        ExecutorService executor=Executors.newSingleThreadExecutor();
        try(Connection owner=dataSource.getConnection()) {
            owner.setAutoCommit(false);
            try(var insert=owner.prepareStatement("INSERT INTO rx_inbox(producer_id,tenant_id,event_id,payload) VALUES (?,?,?,?::jsonb)")) {
                insert.setString(1,SOURCE.producerId());insert.setString(2,SOURCE.tenantId());insert.setObject(3,event);insert.setString(4,body);insert.executeUpdate();
            }
            if(commit) {
                try(var insert=owner.prepareStatement("INSERT INTO rx_after_sale_application(remote_application_id,producer_id,tenant_id,source_event_id,source_application_id,source_operation_id,order_no,draft_version,user_statement) VALUES (?,?,?,?,?,?,?,1,?::jsonb->'userStatement')")) {
                    insert.setObject(1,remote);insert.setString(2,SOURCE.producerId());insert.setString(3,SOURCE.tenantId());
                    insert.setObject(4,event);insert.setObject(5,application);insert.setObject(6,id(body,"operationId"));insert.setString(7,"A10001");insert.setString(8,body);insert.executeUpdate();
                }
                try(var update=owner.prepareStatement("UPDATE rx_inbox SET status='PROCESSED',receipt=?::jsonb,processed_at=clock_timestamp() WHERE event_id=?")) {
                    update.setString(1,mapper.writeValueAsString(previous));update.setObject(2,event);update.executeUpdate();
                }
            }
            Future<Ack> waiting=executor.submit(() -> service.receive(SOURCE,event,body));
            await().pollInterval(Duration.ofMillis(20)).atMost(Duration.ofSeconds(2)).until(() -> jdbc.queryForObject(
                    "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type='Lock' AND query LIKE 'INSERT INTO rx_inbox%'",Integer.class)>0);
            assertThat(waiting.isDone()).isFalse();
            if(commit) owner.commit(); else owner.rollback();
            Ack result=waiting.get(8,TimeUnit.SECONDS);
            if(commit) assertThat(result).isEqualTo(previous);
            assertThat(service.receive(SOURCE,event,body)).isEqualTo(result);
            assertThat(count("rx_inbox")).isEqualTo(1);assertThat(count("rx_after_sale_application")).isEqualTo(1);
        } finally { executor.shutdownNow(); }
    }

    @Test void receiverCommitSurvivesARealDroppedHttpResponse() throws Exception {
        String body=payload(); UUID event=id(body,"eventId");
        var proxy=com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var first=new java.util.concurrent.atomic.AtomicBoolean(true);
        var committed=new java.util.concurrent.atomic.AtomicReference<String>();
        proxy.createContext(PATH, exchange -> {
            try {
                String incoming=new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);
                var response=post(port,incoming,UUID.fromString(exchange.getRequestHeaders().getFirst("Idempotency-Key")),
                        exchange.getRequestHeaders().getFirst("Authorization").substring(7));
                if(first.compareAndSet(true,false)) {
                    // 真实接收服务已提交，但代理不发送任何响应头/正文就断开连接。
                    committed.set(response.body());
                    return;
                }
                byte[] bytes=response.body().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type","application/json");
                exchange.sendResponseHeaders(response.statusCode(),bytes.length);
                exchange.getResponseBody().write(bytes);
            } catch(Exception error) {throw new java.io.IOException("Fixture forwarding failed",error);}
            finally {exchange.close();}
        });
        proxy.start();
        try {
            assertThatThrownBy(() -> post(proxy.getAddress().getPort(),body,event,token("valid"))).isInstanceOf(java.io.IOException.class);
            assertThat(committed.get()).isNotNull();
            assertThat(count("rx_after_sale_application")).isEqualTo(1);
            var second=post(proxy.getAddress().getPort(),body,event,token("valid"));
            var third=post(proxy.getAddress().getPort(),body,event,token("valid"));
            assertThat(second.statusCode()).isEqualTo(200);
            assertThat(mapper.readTree(second.body())).isEqualTo(mapper.readTree(committed.get()));
            assertThat(mapper.readTree(third.body())).isEqualTo(mapper.readTree(committed.get()));
            assertThat(count("rx_after_sale_application")).isEqualTo(1);
        } finally {proxy.stop(0);}
    }

    @Test void lockTimeoutReturnsRetryable503AndOriginalEventCanRecover() throws Exception {
        String body=payload(); UUID event=id(body,"eventId");
        try(Connection owner=dataSource.getConnection()) {
            owner.setAutoCommit(false);
            try(var insert=owner.prepareStatement("INSERT INTO rx_inbox(producer_id,tenant_id,event_id,payload) VALUES (?,?,?,?::jsonb)")) {
                insert.setString(1,SOURCE.producerId());insert.setString(2,SOURCE.tenantId());insert.setObject(3,event);insert.setString(4,body);insert.executeUpdate();
            }
            assertThat(post(port,body,event,token("valid")).statusCode()).isEqualTo(503);
            assertThat(count("rx_after_sale_application")).isZero();
            owner.rollback();
        }
        assertThat(post(port,body,event,token("valid")).statusCode()).isEqualTo(200);
        assertThat(count("rx_after_sale_application")).isEqualTo(1);
    }

    @Test void committedIncompleteInboxAndCorruptReceiptCannotReturnSuccess() throws Exception {
        String body=payload(); UUID event=id(body,"eventId");
        jdbc.update("INSERT INTO rx_inbox(producer_id,tenant_id,event_id,payload) VALUES (?,?,?,?::jsonb)",SOURCE.producerId(),SOURCE.tenantId(),event,body);
        assertThat(post(port,body,event,token("valid")).statusCode()).isEqualTo(503);
        jdbc.update("UPDATE rx_inbox SET status='PROCESSED',receipt='{}'::jsonb,processed_at=clock_timestamp() WHERE event_id=?",event);
        assertThat(post(port,body,event,token("valid")).statusCode()).isEqualTo(500);
        assertThat(count("rx_after_sale_application")).isZero();
    }

    @Test void lostFirstHttpReplyAndTwoActualJvmRestartsReplayDatabaseReceipt() throws Exception {
        String body=payload(); UUID event=id(body,"eventId"); String expected;
        // 第一个独立进程已提交响应后，调用方主动丢弃响应内容，模拟不能依赖第一次回执恢复。
        try(Child first=new Child()) {
            var response=post(first.port,body,event,token("valid"));
            assertThat(response.statusCode()).isEqualTo(200);
            expected=jdbc.queryForObject("SELECT receipt::text FROM rx_inbox WHERE event_id=?",String.class,event);
        }
        try(Child second=new Child()) {
            var replay=post(second.port,body,event,token("valid"));
            assertThat(replay.statusCode()).isEqualTo(200);
            assertThat(mapper.readTree(replay.body())).isEqualTo(mapper.readTree(expected));
        }
        assertThat(mapper.readTree(post(port,body,event,token("valid")).body())).isEqualTo(mapper.readTree(expected));
        assertThat(count("rx_after_sale_application")).isEqualTo(1);
    }

    /** 独立 JVM 使用同一个隔离测试库，但无共享内存；配置经环境传递，不进入命令输出。 */
    class Child implements AutoCloseable {
        final int port; final Process process;
        Child() throws Exception {
            try(var socket=new java.net.ServerSocket(0)) {port=socket.getLocalPort();}
            Path log=Files.createTempFile("receiver-jvm-", ".log");
            var builder=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),
                    "-DsocksNonProxyHosts=localhost|127.*|[::1]","-cp",System.getProperty("java.class.path"),
                    AfterSaleReceiverApplication.class.getName(),"--server.port="+port,"--spring.profiles.active=local-jwt");
            var env=builder.environment();env.put("RECEIVER_JDBC_URL",postgres.getJdbcUrl());env.put("RECEIVER_DB_USER",postgres.getUsername());
            env.put("RECEIVER_DB_PASSWORD",postgres.getPassword());env.put("APP_LOCAL_JWT_PUBLIC_KEY",PUBLIC_KEY.toUri().toString());
            env.put("SERVICE_TOKEN_ISSUER","urn:yunshan:local-service-issuer");
            process=builder.redirectErrorStream(true).redirectOutput(log.toFile()).start();
            try {
                await().atMost(Duration.ofSeconds(45)).pollInterval(Duration.ofMillis(200)).until(() -> {
                    if(!process.isAlive()) throw new IllegalStateException("Receiver child failed; inspect "+log);
                    try {return HTTP.send(HttpRequest.newBuilder(uri(port)).timeout(Duration.ofSeconds(1)).GET().build(),HttpResponse.BodyHandlers.discarding()).statusCode()==401;}
                    catch(Exception ignored){return false;}
                });
            } catch(Exception failure) {close();throw failure;}
        }
        public void close() throws Exception {process.destroyForcibly();assertThat(process.waitFor(10,TimeUnit.SECONDS)).isTrue();}
    }

    String payload() {return InboxProtocolTest.PAYLOAD.replace("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",UUID.randomUUID().toString())
            .replace("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",UUID.randomUUID().toString()).replace("cccccccc-cccc-4ccc-8ccc-cccccccccccc",UUID.randomUUID().toString());}
    UUID id(String body,String field) throws Exception {return UUID.fromString(mapper.readTree(body).get(field).asText());}
    long count(String table) {return jdbc.queryForObject("SELECT count(*) FROM "+table,Long.class);}
    static URI uri(int port) {return URI.create("http://127.0.0.1:"+port+PATH);}
    static HttpResponse<String> post(int port,String body,UUID event,String token) throws Exception {
        var request=HttpRequest.newBuilder(uri(port)).timeout(Duration.ofSeconds(10)).header("Content-Type","application/json");
        if(token!=null)request.header("Authorization","Bearer "+token);
        if(event!=null)request.header("Idempotency-Key",event.toString());
        return HTTP.send(request.POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }
    static KeyPair keys() {try{var generator=KeyPairGenerator.getInstance("RSA");generator.initialize(2048);return generator.generateKeyPair();}catch(Exception e){throw new IllegalStateException(e);}}
    static Path publicFile() {
        try {Path path=Files.createTempFile("receiver-test-key-", ".pub");path.toFile().deleteOnExit();
            Files.writeString(path,"-----BEGIN PUBLIC KEY-----\n"+Base64.getMimeEncoder(64,new byte[]{'\n'}).encodeToString(KEYS.getPublic().getEncoded())+"\n-----END PUBLIC KEY-----\n");return path;
        }catch(Exception e){throw new IllegalStateException(e);}
    }
    /** 每次合法令牌都有不同 jti；签名无效例使用另一把私钥，其余例仍正确签名。 */
    static String token(String kind) throws Exception {
        Instant now=Instant.now();var claims=new JWTClaimsSet.Builder().issuer(kind.equals("issuer") ? "bad-issuer" : "urn:yunshan:local-service-issuer")
                .subject(kind.equals("subject") ? "other-producer" : "yunshan-customer-service")
                .audience(kind.equals("audience") ? "wrong-service" : "after-sale-receiver")
                .claim("scope",kind.equals("scope") ? "read" : "after-sale.ingest").jwtID(UUID.randomUUID().toString())
                .issueTime(Date.from(now)).notBeforeTime(Date.from(kind.equals("future") ? now.plusSeconds(3600) : now.minusSeconds(10)));
        if(kind.equals("missingAudience"))claims.audience((java.util.List<String>) null);
        if(!kind.equals("missingExpiry"))claims.expirationTime(Date.from(kind.equals("expired") ? now.minusSeconds(300) : now.plusSeconds(300)));
        var jwt=new SignedJWT(new JWSHeader(JWSAlgorithm.RS256),claims.build());
        jwt.sign(new RSASSASigner(kind.equals("signature") ? keys().getPrivate() : KEYS.getPrivate()));return jwt.serialize();
    }
}
