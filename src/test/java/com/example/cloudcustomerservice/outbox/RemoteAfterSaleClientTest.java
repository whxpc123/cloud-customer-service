package com.example.cloudcustomerservice.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 本机 HTTP 协议测试：HTTP 200 也必须核对持久化合同，错误正文不可写进错误码。 */
class RemoteAfterSaleClientTest {
    final ObjectMapper json=new ObjectMapper();
    final OutboxStore.Claim claim=new OutboxStore.Claim(UUID.randomUUID(),UUID.randomUUID(),"{}",1,UUID.randomUUID());
    HttpServer server;
    @AfterEach void close(){if(server!=null)server.stop(0);}
    RemoteAfterSaleClient client(int status,String body)throws Exception{
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",x->{x.getRequestBody().readAllBytes();byte[] bytes=body.getBytes(StandardCharsets.UTF_8);x.getResponseHeaders().set("Location","http://127.0.0.1:1/never");x.sendResponseHeaders(status,bytes.length);x.getResponseBody().write(bytes);x.close();});server.start();
        return new RemoteAfterSaleClient(json,URI.create("http://127.0.0.1:"+server.getAddress().getPort()),"test-only-token");
    }
    String ack(String event,String application,String remote,String status)throws Exception{return json.writeValueAsString(Map.of("eventId",event,"applicationId",application,"remoteApplicationId",remote,"status",status));}
    void failure(RemoteAfterSaleClient client,String code,boolean retryable){assertThatThrownBy(()->client.deliver(claim)).isInstanceOfSatisfying(RemoteAfterSaleClient.DeliveryFailure.class,e->{assertThat(e.code()).isEqualTo(code);assertThat(e.retryable()).isEqualTo(retryable);});}
    @ParameterizedTest @ValueSource(ints={408,429,500,503})
    void temporaryStatusRetriesWithoutSavingErrorBody(int status)throws Exception{failure(client(status,"secret body"),"HTTP_"+status,true);}
    @ParameterizedTest @ValueSource(ints={202,302,400,401,403,409})
    void otherStatusRequiresReviewAndDoesNotFollowRedirect(int status)throws Exception{failure(client(status,"not persisted"),"HTTP_"+status,false);}
    @ParameterizedTest @ValueSource(strings={"<html>login</html>","{","{}","null"})
    void badSuccessBodyIsNeverDelivered(String body)throws Exception{failure(client(200,body),body.equals("{}")||body.equals("null")?"ACK_MISMATCH":"INVALID_ACK",false);}
    @Test void oversizedAckIsRejected()throws Exception{failure(client(200,"x".repeat(65537)),"ACK_TOO_LARGE",false);}
    @ParameterizedTest @ValueSource(strings={"event","application","remote","status"})
    void everyReceiptIdentityMustMatch(String field)throws Exception{failure(client(200,ack(field.equals("event")?UUID.randomUUID().toString():claim.eventId().toString(),field.equals("application")?UUID.randomUUID().toString():claim.applicationId().toString(),field.equals("remote")?" ":"remote1",field.equals("status")?"ACCEPTED":"PERSISTED")),"ACK_MISMATCH",false);}
    @ParameterizedTest @ValueSource(ints={200,201})
    void onlyMatchingPersistentAckSucceeds(int status)throws Exception{assertThat(client(status,ack(claim.eventId().toString(),claim.applicationId().toString(),"remote1","PERSISTED")).deliver(claim).remoteApplicationId()).isEqualTo("remote1");}
    @ParameterizedTest @ValueSource(strings={"http://example.com/a","http://localhost/a","file:///tmp/a","https://user:password@example.com/a","https://example.com/a?token=x","https://example.com/a#fragment"})
    void unsafeOrAmbiguousEndpointIsRejected(String uri){assertThatThrownBy(()->new RemoteAfterSaleClient(json,URI.create(uri),"test-only-token")).isInstanceOf(IllegalArgumentException.class);}
    @Test void missingTokenIsRejected(){assertThatThrownBy(()->new RemoteAfterSaleClient(json,URI.create("https://example.com/a")," ")).isInstanceOf(IllegalArgumentException.class);}
    @Test void localAckSaveFailureIsNotReclassifiedAsRemoteFailure(){
        var store=mock(OutboxStore.class);var remote=mock(RemoteAfterSaleClient.class);
        when(store.claimOne()).thenReturn(Optional.of(claim));when(remote.deliver(claim)).thenReturn(new RemoteAfterSaleClient.Ack(claim.eventId(),claim.applicationId(),"saved","PERSISTED"));
        when(store.delivered(claim,"saved")).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("commit unknown"));
        new OutboxRelay(store,remote).tick();verify(store,never()).failed(any(),anyString(),anyBoolean());verify(remote,times(1)).deliver(claim);
    }
}
