package com.example.cloudcustomerservice.agent;

import com.example.cloudcustomerservice.acceptance.AcceptanceDatabase;
import com.example.cloudcustomerservice.aftersale.ReturnAssessmentService;
import com.fasterxml.jackson.databind.*;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static com.example.cloudcustomerservice.aftersale.AfterSaleModel.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 真实 Cookie、CSRF、Tomcat、数据库事务和 Agent；订单与模型受控，隔离容器不触碰用户知识库。 */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "spring.ai.dashscope.api-key=offline-placeholder","app.ai.log-payload=false",
    "handoff.accounts.customer1001.password=draft-test-only-password",
    "handoff.accounts.customer2002.password=draft-test-only-password",
    "handoff.accounts.support9001.password=draft-test-only-password"})
@ActiveProfiles({"local","knowledge"}) @Import(AcceptanceDatabase.class)
@EnabledIfEnvironmentVariable(named="RUN_ACCEPTANCE_TESTS",matches="true")
class DraftAgentHttpTest {
    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean ChatModel model;
    @MockitoBean ReturnAssessmentService assessment;
    Client owner,other,support;
    @BeforeEach void setup()throws Exception{
        assertThat(jdbc.queryForObject("select current_database()",String.class)).isEqualTo("stage1_acceptance");
        owner=new Client("customer1001");other=new Client("customer2002");support=new Client("support9001");
        when(model.getDefaultOptions()).thenReturn(com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions.builder().model("controlled-http").build());
        when(model.call(any(Prompt.class))).thenAnswer(i->{Prompt p=i.getArgument(0);long n=p.getInstructions().stream().filter(ToolResponseMessage.class::isInstance).count();
            return n==0?DraftAgentTest.tool("inspectAfterSale"):n==1?DraftAgentTest.tool("readDraftTemplate"):DraftAgentTest.text(DraftAgentTest.CANDIDATE);});
        when(assessment.assess(any(),anyString(),any())).thenReturn(DraftAgentTest.assessment("A10001",AssessmentStatus.NEED_QUALITY_VERIFICATION,true));
    }
    String create()throws Exception{return owner.call("/api/handoff/conversations","POST","{}",201).path("conversationId").asText();}
    String body(String id)throws Exception{return json.writeValueAsString(Map.of("conversationId",id,"orderNo","A10001","reason","QUALITY_ISSUE","task","整理草稿，不要提交","inspectionOnly",false));}
    String path(){return "/internal/draft-agent/runs";}
    HttpRequest.Builder base(String path){return HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(12));}
    final class Client {
        final HttpClient http=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();
        String csrf;
        Client(String name)throws Exception{
            csrf=call("/internal/handoff/session","GET",null,200).path("csrfToken").asText();
            var r=http.send(base("/internal/handoff/login").header("Content-Type","application/x-www-form-urlencoded").header("X-CSRF-TOKEN",csrf)
                .POST(HttpRequest.BodyPublishers.ofString("username="+name+"&password=draft-test-only-password")).build(),HttpResponse.BodyHandlers.discarding());
            assertThat(r.statusCode()).isEqualTo(204);csrf=call("/internal/handoff/session","GET",null,200).path("csrfToken").asText();
        }
        JsonNode call(String path,String method,String data,int expected)throws Exception{
            var b=base(path).header("Content-Type","application/json");if(csrf!=null)b.header("X-CSRF-TOKEN",csrf);
            var r=http.send(b.method(method,data==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(data)).build(),HttpResponse.BodyHandlers.ofString());
            assertThat(r.statusCode()).as(r.body()).isEqualTo(expected);return r.body().isEmpty()?json.nullNode():json.readTree(r.body());
        }
    }
    @Test void realAgentReturnsCandidateButDoesNotWriteFormalMessages()throws Exception{
        String id=create();var result=owner.call(path(),"POST",body(id),200);assertThat(result.path("status").asText()).isEqualTo("CANDIDATE_UNVALIDATED");
        assertThat(result.path("submitted").asBoolean()).isFalse();assertThat(result.path("refundExecuted").asBoolean()).isFalse();
        assertThat(owner.call("/api/handoff/conversations/"+id+"/messages","GET",null,200).path("messages")).isEmpty();
        verify(assessment).assess(new Actor("tenant-yunshan",1001),"A10001",ReturnReason.QUALITY_ISSUE);
    }
    @Test void loginCsrfSupportAndOwnershipStopBeforeModel()throws Exception{
        String id=create();var payload=body(id);
        var anonymous=HttpClient.newHttpClient().send(base(path()).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(payload)).build(),HttpResponse.BodyHandlers.discarding());
        assertThat(anonymous.statusCode()).isIn(401,403);
        var noCsrf=owner.http.send(base(path()).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(payload)).build(),HttpResponse.BodyHandlers.discarding());assertThat(noCsrf.statusCode()).isEqualTo(403);
        other.call(path(),"POST",payload,404);support.call(path(),"POST",payload,403);verify(model,never()).call(any(Prompt.class));
    }
    @Test void waitingHumanBlocksAgentAndMalformedInputDoesNotCallModel()throws Exception{
        String id=create();owner.call(path(),"POST",body(id).replace("A10001","../secret"),400);
        owner.call("/api/handoff/conversations/"+id+"/handoff","POST",null,200);owner.call(path(),"POST",body(id),409);verify(model,never()).call(any(Prompt.class));
    }
    @Test void handoffDuringGenerationSuppressesLateCandidate()throws Exception{lateChange(false);}
    @Test void logoutDuringGenerationSuppressesLateCandidate()throws Exception{lateChange(true);}
    /** 闩锁固定并发顺序：模型先开始，状态事务已提交，再允许模型返回，不用随机 sleep 制造竞态。 */
    void lateChange(boolean logout)throws Exception{
        String id=create(),payload=body(id);var started=new CountDownLatch(1);var release=new CountDownLatch(1);
        when(model.call(any(Prompt.class))).thenAnswer(i->{started.countDown();assertThat(release.await(5,TimeUnit.SECONDS)).isTrue();return DraftAgentTest.text("未完成");});
        var pool=Executors.newSingleThreadExecutor();
        try{var pending=pool.submit(()->owner.call(path(),"POST",payload,200));assertThat(started.await(5,TimeUnit.SECONDS)).isTrue();
            if(logout)owner.call("/internal/handoff/logout","POST",null,204);
            else owner.call("/api/handoff/conversations/"+id+"/handoff","POST",null,200);
            release.countDown();var result=pending.get(6,TimeUnit.SECONDS);assertThat(result.path("status").asText()).isEqualTo("STATE_CHANGED");assertThat(result.path("candidateText").isNull()).isTrue();assertThat(result.path("assessment").isNull()).isTrue();
            assertThat(jdbc.queryForObject("select count(*) from ai.cs_message where conversation_id=? and role='ASSISTANT'",Integer.class,UUID.fromString(id))).isZero();
        }finally{release.countDown();pool.shutdownNow();}
    }
}
