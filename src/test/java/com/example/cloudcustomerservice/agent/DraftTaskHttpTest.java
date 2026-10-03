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
class DraftTaskHttpTest {
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
        when(model.call(any(Prompt.class))).thenAnswer(i->DraftTaskTest.next(i.getArgument(0)));
        when(assessment.assess(any(),anyString(),any())).thenReturn(DraftAgentTest.assessment("A10001",AssessmentStatus.NEED_QUALITY_VERIFICATION,true));
    }
    String create()throws Exception{return owner.call("/api/handoff/conversations","POST","{}",201).path("conversationId").asText();}
    String body(String id)throws Exception{return json.writeValueAsString(Map.of("conversationId",id,"orderNo","A10001","reason","QUALITY_ISSUE"));}
    String path(){return "/internal/local-draft-tasks/tasks";}
    String task(String conversation)throws Exception{return owner.call(path(),"POST",body(conversation),201).path("taskId").asText();}
    String turn(){return "{\"message\":\"请整理候选，不要提交\"}";}
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
    @Test void createContinueRefreshAndDiscardDoNotWriteFormalChat()throws Exception{
        String conversation=create(),id=task(conversation);verify(model,never()).call(any(Prompt.class));
        Set<String> runs=new HashSet<>();
        for(int n=1;n<=3;n++){
            var r=owner.call(path()+"/"+id+"/turns","POST",turn(),200);
            assertThat(r.path("task").path("taskId").asText()).isEqualTo(id);
            assertThat(r.path("task").path("turnNo").asInt()).isEqualTo(n);
            assertThat(r.path("state").path("userMessages").asInt()).isEqualTo(n);
            assertThat(r.path("lastRun").path("status").asText()).isEqualTo("CANDIDATE_UNVALIDATED");
            runs.add(r.path("lastRun").path("runId").asText());
        }
        assertThat(runs).hasSize(3);var loaded=owner.call(path()+"/"+id,"GET",null,200);
        assertThat(loaded.path("lastRun").path("candidateText").asText()).contains("尚未提交");
        assertThat(loaded.toString()).doesNotContain("threadId","checkpointId","csrfToken");
        verify(model,times(9)).call(any(Prompt.class));verify(assessment,times(3)).assess(any(),anyString(),any());
        owner.call(path()+"/"+id,"DELETE",null,204);owner.call(path()+"/"+id,"GET",null,404);
        assertThat(owner.call("/api/handoff/conversations/"+conversation+"/messages","GET",null,200).path("messages")).isEmpty();
    }
    @Test void loginCsrfSupportAndAllForeignTaskOperationsAreBlocked()throws Exception{
        String conversation=create(),id=task(conversation);var payload=body(conversation);
        var anonymous=HttpClient.newHttpClient().send(base(path()).GET().build(),HttpResponse.BodyHandlers.discarding());assertThat(anonymous.statusCode()).isEqualTo(401);
        for(String endpoint:List.of(path(),path()+"/"+id+"/turns")){
            var r=owner.http.send(base(endpoint).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(payload)).build(),HttpResponse.BodyHandlers.discarding());assertThat(r.statusCode()).isEqualTo(403);
        }
        var noCsrfDelete=owner.http.send(base(path()+"/"+id).DELETE().build(),HttpResponse.BodyHandlers.discarding());assertThat(noCsrfDelete.statusCode()).isEqualTo(403);
        other.call(path(),"POST",payload,404);support.call(path(),"GET",null,403);
        other.call(path()+"/"+id,"GET",null,404);other.call(path()+"/"+id,"DELETE",null,404);other.call(path()+"/"+id+"/turns","POST",turn(),404);
        assertThat(other.call(path(),"GET",null,200).toString()).doesNotContain(id);verify(model,never()).call(any(Prompt.class));
    }
    @Test void waitingHumanHidesSavedCandidateAndBlocksNewTurns()throws Exception{
        String conversation=create(),id=task(conversation);owner.call(path()+"/"+id+"/turns","POST",turn(),200);
        owner.call("/api/handoff/conversations/"+conversation+"/handoff","POST",null,200);
        owner.call(path(),"POST",body(conversation),409);owner.call(path()+"/"+id+"/turns","POST",turn(),409);
        var r=owner.call(path()+"/"+id,"GET",null,200);assertThat(r.path("lastRun").isNull()).isTrue();assertThat(r.path("task").path("status").asText()).isEqualTo("CLOSED");
        owner.call(path()+"/"+id,"DELETE",null,204);verify(model,times(3)).call(any(Prompt.class));
    }
    @Test void malformedInputsNeverReachModel()throws Exception{
        String conversation=create();owner.call(path(),"POST",body(conversation).replace("A10001","../secret"),400);String id=task(conversation);
        owner.call(path()+"/"+id+"/turns","POST","{}",400);owner.call(path()+"/bad-id","GET",null,400);verify(model,never()).call(any(Prompt.class));
    }
    @Test void handoffDuringGenerationClosesTaskAndSuppressesLateFacts()throws Exception{lateChange(false);}
    @Test void logoutDuringGenerationClosesTaskAndSuppressesLateFacts()throws Exception{lateChange(true);}
    /** 以闩锁让模型调用与权限变化形成确定顺序，验证 late result 不会重新展示旧候选。 */
    void lateChange(boolean logout)throws Exception{
        String conversation=create(),id=task(conversation);var started=new CountDownLatch(1);var release=new CountDownLatch(1);
        when(model.call(any(Prompt.class))).thenAnswer(i->{started.countDown();assertThat(release.await(5,TimeUnit.SECONDS)).isTrue();return DraftTaskTest.next(i.getArgument(0));});
        var pool=Executors.newSingleThreadExecutor();
        try{var pending=pool.submit(()->owner.call(path()+"/"+id+"/turns","POST",turn(),200));assertThat(started.await(5,TimeUnit.SECONDS)).isTrue();
            if(logout)owner.call("/internal/handoff/logout","POST",null,204);else owner.call("/api/handoff/conversations/"+conversation+"/handoff","POST",null,200);
            // 转人工会合法写入一条 SYSTEM 通知；固定变化后的消息数，验证 Agent 不再追加任何消息。
            int committed=jdbc.queryForObject("select count(*) from ai.cs_message where conversation_id=?",Integer.class,UUID.fromString(conversation));
            release.countDown();var r=pending.get(6,TimeUnit.SECONDS);assertThat(r.path("task").path("status").asText()).isEqualTo("CLOSED");
            assertThat(r.path("lastRun").path("candidateText").isNull()).isTrue();assertThat(r.path("lastRun").path("assessment").isNull()).isTrue();
            assertThat(jdbc.queryForObject("select count(*) from ai.cs_message where conversation_id=?",Integer.class,UUID.fromString(conversation))).isEqualTo(committed);
        }finally{release.countDown();pool.shutdownNow();}
    }
}
