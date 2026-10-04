package com.example.cloudcustomerservice.outbox;

import com.example.cloudcustomerservice.reconcile.ReconcileModel;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

/** 实际 HTTP 客户端查询固定子路径：严格响应、无重定向、无创建回退。 */
class RemoteLookupTest {
    HttpServer server; final UUID event=UUID.randomUUID();final AtomicInteger creates=new AtomicInteger(),lookups=new AtomicInteger();
    @AfterEach void close(){if(server!=null)server.stop(0);}
    RemoteAfterSaleClient client(int status,String body)throws Exception{
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/applications",x->{try{
            if(!x.getRequestURI().getPath().equals("/applications/lookup")){creates.incrementAndGet();x.sendResponseHeaders(500,-1);return;}
            lookups.incrementAndGet();assertThat(x.getRequestMethod()).isEqualTo("POST");
            assertThat(x.getRequestHeaders().getFirst("Idempotency-Key")).isEqualTo(event.toString());
            assertThat(new String(x.getRequestBody().readAllBytes(),StandardCharsets.UTF_8)).isEqualTo("{\"original\":true}");
            byte[] bytes=body.getBytes(StandardCharsets.UTF_8);x.getResponseHeaders().set("Location","http://127.0.0.1:1/create");
            x.sendResponseHeaders(status,bytes.length);x.getResponseBody().write(bytes);
        }finally{x.close();}});server.start();
        return new RemoteAfterSaleClient(new ObjectMapper(),URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/applications/"),"test-only-token");
    }
    void fails(int status,String body,String code)throws Exception{
        var c=client(status,body);assertThatThrownBy(()->c.lookup(event,"{\"original\":true}"))
            .isInstanceOfSatisfying(RemoteAfterSaleClient.DeliveryFailure.class,e->assertThat(e.code()).isEqualTo(code));
        assertThat(creates.get()).isZero();assertThat(lookups.get()).isEqualTo(1);
    }
    @ParameterizedTest @ValueSource(ints={201,202,302,400,401,403,404,409,500,503})
    void non200NeverMeansNotObserved(int status)throws Exception{fails(status,"sensitive error body","LOOKUP_HTTP_"+status);}
    @ParameterizedTest @ValueSource(strings={"<html>","{", "{}{}", "{\"state\":\"NOT_OBSERVED\",\"state\":\"PERSISTED\"}","{\"unknown\":true}"})
    void ambiguousJsonIsRejected(String body)throws Exception{fails(200,body,"LOOKUP_INVALID_JSON");}
    @Test void limitIsEnforced()throws Exception{fails(200,"x".repeat(65537),"LOOKUP_RESPONSE_TOO_LARGE");}
    @Test void validReplyIsReturnedForSeparateEvidenceInspection()throws Exception{
        var c=client(200,"{\"eventId\":\""+event+"\",\"state\":\"NOT_OBSERVED\",\"receipt\":null}");
        assertThat(c.lookup(event,"{\"original\":true}")).isEqualTo(new ReconcileModel.Reply(event,"NOT_OBSERVED",null));
        assertThat(creates.get()).isZero();
    }
}
