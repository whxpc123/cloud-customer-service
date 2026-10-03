package com.example.cloudcustomerservice;

import com.example.cloudcustomerservice.handoff.*;
import com.example.cloudcustomerservice.routing.*;
import com.example.cloudcustomerservice.security.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;
import static com.example.cloudcustomerservice.handoff.HandoffModel.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 用真实 PostgreSQL、Spring 事务及权限代理验证接待边界；模型只返回确定的候选答案，不调用收费服务。 */
@SpringBootTest(properties={"spring.ai.dashscope.api-key=offline-test-placeholder",
    "spring.datasource.url=jdbc:postgresql://127.0.0.1:15432/cloud_customer_service_test",
    "handoff.accounts.customer1001.password=only-test-customer-password",
    "handoff.accounts.support9001.password=only-test-support-password"})
@ActiveProfiles({"local","knowledge"}) @AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named="RUN_PGVECTOR_TESTS",matches="true")
class HumanHandoffPersistenceTest {
    @Autowired HumanHandoffService service;
    @Autowired HandoffChatService chat;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockitoBean RoutedCustomerService robot;
    static final Actor CUSTOMER=new Actor("tenant-yunshan",1001),OTHER=new Actor("tenant-yunshan",2002),
        AGENT=new Actor("tenant-yunshan",9001),AGENT2=new Actor("tenant-yunshan",9002);
    /** 每条线程显式建立已验证身份；不得把线程池继承上下文当成权限检查。 */
    static HandoffPrincipal principal(Actor actor){return new HandoffPrincipal("test-"+actor.accountId(),"unused",actor,
        List.of(new SimpleGrantedAuthority(actor.accountId()>=9000?"support:serve":"customer:chat")));}
    <T>T as(Actor actor,Supplier<T> action){var old=SecurityContextHolder.getContext();var context=SecurityContextHolder.createEmptyContext();var p=principal(actor);
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(p,p.getPassword(),p.getAuthorities()));SecurityContextHolder.setContext(context);
        try{return action.get();}finally{SecurityContextHolder.setContext(old);}}
    UUID create(){return as(CUSTOMER,()->service.createConversation(CUSTOMER).conversationId());}
    Receipt request(UUID id){return as(CUSTOMER,()->service.request(CUSTOMER,id));}
    Delivery say(UUID id,String text){return as(CUSTOMER,()->chat.answer(CUSTOMER,id,UUID.randomUUID(),text));}
    History history(UUID id){return as(CUSTOMER,()->service.history(CUSTOMER,id,0));}
    RoutedCustomerService.Response answer(String id,CustomerRouter.Route route){return new RoutedCustomerService.Response("test-result",id,
        new CustomerRouter.Decision(route,CustomerRouter.Source.RULE,"EXACT_SMALL_TALK"),new RoutingStatistics.Timing(0,0),
        "FIXED_REPLY","已验证的测试候选答案",null,null,null,null);}
    @BeforeEach void setup(){
        assertThat(jdbc.queryForObject("select current_database()",String.class)).isEqualTo("cloud_customer_service_test");clean();
        when(robot.answer(any(),any(),any())).thenAnswer(i->answer(((RoutingConversation)i.getArgument(1)).id(),CustomerRouter.Route.SMALL_TALK));
    }
    @AfterEach void clean(){jdbc.update("delete from ai.cs_message");jdbc.update("delete from ai.cs_conversation");SecurityContextHolder.clearContext();}

    @Test void requestAndRetryReturnOneDurableReceipt(){
        var id=create();var first=request(id);assertThat(first.mode()).isEqualTo(Mode.WAITING_HUMAN);assertThat(first.version()).isEqualTo(1);
        assertThat(request(id)).isEqualTo(first);assertThat(as(CUSTOMER,()->service.get(CUSTOMER,id))).isEqualTo(first);
        assertThat(history(id).messages()).hasSize(1);verifyNoInteractions(robot);
    }
    @Test void twoConcurrentRequestsCreateOneReceipt()throws Exception{
        var id=create();var barrier=new CyclicBarrier(2);var pool=Executors.newFixedThreadPool(2);
        try{Callable<Receipt> action=()->{barrier.await(5,TimeUnit.SECONDS);return request(id);};
            var a=pool.submit(action);var b=pool.submit(action);assertThat(a.get(10,TimeUnit.SECONDS)).isEqualTo(b.get(10,TimeUnit.SECONDS));
            assertThat(history(id).messages()).hasSize(1);
        }finally{pool.shutdownNow();}
    }
    @Test void twoAgentsCannotBothClaimSameConversation()throws Exception{
        var id=create();request(id);var barrier=new CyclicBarrier(2);var pool=Executors.newFixedThreadPool(2);
        try{var tasks=new ArrayList<Future<Object>>();for(var agent:List.of(AGENT,AGENT2))tasks.add(pool.submit(()->{
            barrier.await(5,TimeUnit.SECONDS);try{return as(agent,()->service.accept(agent,id));}catch(ResponseStatusException e){return e.getStatusCode().value();}}));
            var results=List.of(tasks.get(0).get(10,TimeUnit.SECONDS),tasks.get(1).get(10,TimeUnit.SECONDS));
            assertThat(results.stream().filter(Receipt.class::isInstance).count()).isEqualTo(1);assertThat(results).contains(409);
            assertThat(history(id).receipt().version()).isEqualTo(2);
        }finally{pool.shutdownNow();}
    }
    @Test void ownerAgentAloneCanCloseAndClosedCannotReopen(){
        var id=create();request(id);var accepted=as(AGENT,()->service.accept(AGENT,id));
        assertThat(as(AGENT,()->service.accept(AGENT,id))).isEqualTo(accepted);
        assertThatThrownBy(()->as(AGENT2,()->service.close(AGENT2,id))).isInstanceOf(ResponseStatusException.class);
        var closed=as(AGENT,()->service.close(AGENT,id));assertThat(closed.mode()).isEqualTo(Mode.CLOSED);
        assertThat(as(AGENT,()->service.close(AGENT,id))).isEqualTo(closed);assertThat(request(id)).isEqualTo(closed);
        assertThatThrownBy(()->say(id,"继续问")).isInstanceOf(ResponseStatusException.class);
        assertThat(history(id).messages()).hasSize(3);assertThat(as(AGENT,()->service.waiting(AGENT))).isEmpty();
    }
    @Test void tenantAndOwnerChecksPrecedeAnyMutation(){
        var id=create();var foreign=new Actor("other-tenant",1001);
        for(var actor:List.of(OTHER,foreign)){
            assertThatThrownBy(()->as(actor,()->service.request(actor,id))).isInstanceOf(ResponseStatusException.class);
            assertThatThrownBy(()->as(actor,()->service.history(actor,id,0))).isInstanceOf(ResponseStatusException.class);
        }
        assertThatThrownBy(()->as(OTHER,()->service.request(CUSTOMER,id))).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        assertThat(history(id).receipt().mode()).isEqualTo(Mode.BOT);assertThat(history(id).messages()).isEmpty();
    }
    @Test void realMethodProxyRejectsCustomerClaimEvenWithForgedActor(){
        var id=create();request(id);
        assertThatThrownBy(()->as(CUSTOMER,()->service.accept(CUSTOMER,id))).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        assertThatThrownBy(()->as(CUSTOMER,()->service.accept(AGENT,id))).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        assertThatThrownBy(()->service.waiting(AGENT)).isInstanceOf(org.springframework.security.core.AuthenticationException.class);
    }
    @Test void supportQueueAndHistoryAreTenantScoped(){
        var id=create();request(id);var foreign=new Actor("other-tenant",9001);
        assertThat(as(foreign,()->service.waiting(foreign))).isEmpty();
        assertThatThrownBy(()->as(foreign,()->service.accept(foreign,id))).isInstanceOf(ResponseStatusException.class);
        assertThat(as(AGENT,()->service.supportHistory(AGENT,id,0)).messages()).hasSize(1);
        as(AGENT,()->service.accept(AGENT,id));
        assertThatThrownBy(()->as(AGENT2,()->service.supportHistory(AGENT2,id,0))).isInstanceOf(ResponseStatusException.class);
    }
    @Test void stateConstraintRejectsPartialOrUnknownState(){
        var id=create();for(String invalid:List.of("HUMAN_ACTIVE","WAITING_HUMAN","CLOSED","UNKNOWN"))
            assertThatThrownBy(()->jdbc.update("update ai.cs_conversation set mode=? where id=?",invalid,id)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(history(id).receipt().mode()).isEqualTo(Mode.BOT);
    }
    @Test void failedSystemMessageRollsBackHandoffTogether(){
        var id=create();jdbc.execute("alter table ai.cs_message add constraint test_reject_system check(role <> 'SYSTEM')");
        try{assertThatThrownBy(()->request(id)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            assertThat(history(id).receipt().mode()).isEqualTo(Mode.BOT);assertThat(history(id).receipt().handoffId()).isNull();
        }finally{jdbc.execute("alter table ai.cs_message drop constraint test_reject_system");}
        assertThat(request(id).version()).isEqualTo(1);
    }
    @Test void explicitHumanCommandSavesUserAndReceiptWithoutRobot(){
        var id=create();var r=say(id,"我要转人工！");assertThat(r.deliveryStatus()).isEqualTo("HANDOFF_ACCEPTED");
        assertThat(r.receipt().handoffId()).isNotNull();assertThat(r.message()).isNull();
        assertThat(history(id).messages()).extracting(Message::role).containsExactly("USER","SYSTEM");verifyNoInteractions(robot);
    }
    @Test void waitingAndActiveSaveMessagesWithoutAnyClassifierOrModel(){
        var id=create();request(id);assertThat(say(id,"查订单").deliveryStatus()).isEqualTo("USER_SAVED");
        as(AGENT,()->service.accept(AGENT,id));assertThat(say(id,"需要补充说明").published()).isFalse();
        assertThat(history(id).messages()).extracting(Message::role).containsExactly("SYSTEM","USER","SYSTEM","USER");verifyNoInteractions(robot);
    }
    @Test void normalCandidatePublishedWithOriginalBusinessPayload(){
        var id=create();var r=say(id,"你好");assertThat(r.published()).isTrue();assertThat(r.message().payload().get("requestId").asText()).isEqualTo("test-result");
        assertThat(history(id).messages()).extracting(Message::role).containsExactly("USER","BOT");
    }
    @Test void semanticHumanRouteAlsoCreatesDatabaseReceipt(){
        var id=create();doReturn(answer(id.toString(),CustomerRouter.Route.HUMAN_SERVICE)).when(robot).answer(any(),any(),any());
        var r=say(id,"我需要真人帮忙处理一下");assertThat(r.deliveryStatus()).isEqualTo("HANDOFF_ACCEPTED");
        assertThat(r.published()).isFalse();assertThat(r.message()).isNull();assertThat(history(id).messages()).noneMatch(m->m.role().equals("BOT"));
    }
    /** 门闩证明模型运行中可以提交交接，且模型没有占用事务；不是靠 sleep 猜测竞态。 */
    @Test void handoffDuringGenerationCommitsAndLateCandidateNeverPublishes()throws Exception{
        var id=create();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var pool=Executors.newSingleThreadExecutor();
        doAnswer(i->{assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();entered.countDown();assertThat(release.await(10,TimeUnit.SECONDS)).isTrue();return answer(id.toString(),CustomerRouter.Route.SMALL_TALK);}).when(robot).answer(any(),any(),any());
        try{var job=pool.submit(()->say(id,"慢请求"));assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
            var accepted=request(id);assertThat(accepted.mode()).isEqualTo(Mode.WAITING_HUMAN);release.countDown();
            var late=job.get(10,TimeUnit.SECONDS);assertThat(late.deliveryStatus()).isEqualTo("STALE_DISCARDED");assertThat(late.message()).isNull();
            assertThat(history(id).messages()).extracting(Message::role).containsExactly("USER","SYSTEM");
        }finally{release.countDown();pool.shutdownNow();}
    }
    @Test void publicationBeforeHandoffRemainsAnEarlierFormalRecord(){
        var id=create();var first=say(id,"你好");var handoff=request(id);
        assertThat(first.receipt().version()).isLessThan(handoff.version());
        assertThat(history(id).messages()).extracting(Message::role).containsExactly("USER","BOT","SYSTEM");
    }
    @Test void clientRetryDoesNotRepublishAndDifferentTextConflicts(){
        var id=create();var messageId=UUID.randomUUID();as(CUSTOMER,()->chat.answer(CUSTOMER,id,messageId,"你好"));
        assertThat(as(CUSTOMER,()->chat.answer(CUSTOMER,id,messageId,"你好")).deliveryStatus()).isEqualTo("REPLAY");
        assertThatThrownBy(()->as(CUSTOMER,()->chat.answer(CUSTOMER,id,messageId,"不同正文"))).isInstanceOf(ResponseStatusException.class);
        verify(robot,times(1)).answer(any(),any(),any());assertThat(history(id).messages()).hasSize(2);
    }
    @Test void freshLeaseBlocksSecondGenerationButNeverExplicitHandoff(){
        var id=create();as(CUSTOMER,()->service.begin(CUSTOMER,id,UUID.randomUUID(),"未完成问题",false));
        assertThatThrownBy(()->say(id,"第二条")).isInstanceOf(ResponseStatusException.class);
        assertThat(say(id,"转人工").receipt().mode()).isEqualTo(Mode.WAITING_HUMAN);verifyNoInteractions(robot);
    }
    @Test void expiredLeaseAllowsRecoveryButOldGenerationCannotPublish(){
        var id=create();var old=as(CUSTOMER,()->service.begin(CUSTOMER,id,UUID.randomUUID(),"中断问题",false));
        jdbc.update("update ai.cs_conversation set generation_started_at=current_timestamp-interval '6 minutes' where id=?",id);
        assertThat(say(id,"重新提问").published()).isTrue();
        assertThat(as(CUSTOMER,()->service.finish(CUSTOMER,id,old,answer(id.toString(),CustomerRouter.Route.SMALL_TALK))).published()).isFalse();
    }
    @Test void resetKeepsFormalHistoryAndChangesOnlyModelWindow(){
        var id=create();say(id,"第一问");var reset=as(CUSTOMER,()->service.resetMemory(CUSTOMER,id));assertThat(reset.version()).isEqualTo(1);
        var next=as(CUSTOMER,()->service.begin(CUSTOMER,id,UUID.randomUUID(),"新话题",false));assertThat(next.history()).isEmpty();assertThat(history(id).messages()).hasSize(4);
        request(id);assertThatThrownBy(()->as(CUSTOMER,()->service.resetMemory(CUSTOMER,id))).isInstanceOf(ResponseStatusException.class);
    }
    @Test void fullHistoryPagesBeyondModelWindow(){
        var id=create();for(int i=0;i<53;i++)say(id,"你好 "+i);
        var first=history(id);assertThat(first.messages()).hasSize(100);assertThat(first.hasMore()).isTrue();
        var second=as(CUSTOMER,()->service.history(CUSTOMER,id,first.nextCursor()));assertThat(second.messages()).hasSize(6);assertThat(second.hasMore()).isFalse();
        var next=as(CUSTOMER,()->service.begin(CUSTOMER,id,UUID.randomUUID(),"后续",false));assertThat(next.history()).hasSize(20);
        assertThat(as(CUSTOMER,()->service.conversations(CUSTOMER))).extracting(Receipt::conversationId).contains(id);
    }
    @Test void loginReallyVerifiesPasswordAndLogsOut()throws Exception{
        var result=mvc.perform(post("/internal/handoff/login").with(csrf()).param("username","customer1001").param("password","only-test-customer-password")).andExpect(status().isNoContent()).andReturn();
        var session=(org.springframework.mock.web.MockHttpSession)result.getRequest().getSession(false);
        mvc.perform(get("/internal/handoff/session").session(session)).andExpect(status().isOk()).andExpect(jsonPath("accountId").value(1001)).andExpect(jsonPath("csrfToken").isNotEmpty());
        mvc.perform(post("/api/handoff/conversations").session(session).with(csrf())).andExpect(status().isCreated());
        mvc.perform(post("/internal/handoff/logout").session(session).with(csrf())).andExpect(status().isNoContent());
        mvc.perform(post("/internal/handoff/login").with(csrf()).param("username","customer1001").param("password","wrong")).andExpect(status().isUnauthorized());
        mvc.perform(post("/internal/handoff/login").with(csrf()).param("username","absent").param("password","only-test-customer-password")).andExpect(status().isUnauthorized());
    }
    @Test void httpPermissionCsrfAndLocalOriginProtectEndpoints()throws Exception{
        mvc.perform(get("/api/handoff/conversations")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/support/queue").with(user(principal(CUSTOMER)))).andExpect(status().isForbidden());
        mvc.perform(post("/api/handoff/conversations").with(user(principal(CUSTOMER)))).andExpect(status().isForbidden());
        mvc.perform(get("/internal/handoff/session").header("Origin","https://evil.example")).andExpect(status().isForbidden());
        mvc.perform(get("/internal/handoff/session").with(r->{r.setRemoteAddr("192.168.1.1");return r;})).andExpect(status().isForbidden());
        mvc.perform(get("/internal/handoff/session").with(r->{r.setServerName("evil.example");return r;})).andExpect(status().isForbidden());
        verifyNoInteractions(robot);
    }
    @Test void forgedBodyAndHeaderCannotChangeOwnerHistoryOrMode()throws Exception{
        var id=create();mvc.perform(post("/internal/routing/conversations/"+id+"/messages").with(user(principal(CUSTOMER))).with(csrf())
            .header("X-Demo-User-Id","2002").contentType("application/json").content("{\"message\":\"你好\",\"userId\":2002,\"history\":\"假历史\",\"mode\":\"HUMAN_ACTIVE\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("receipt.mode").value("BOT"));
        verify(robot).answer(eq(new com.example.cloudcustomerservice.aftersale.AfterSaleModel.Actor("tenant-yunshan",1001)),argThat(c->c.history().isEmpty()),eq("你好"));
        mvc.perform(get("/api/handoff/conversations/"+id+"/messages").with(user(principal(OTHER)))).andExpect(status().isNotFound());
        mvc.perform(post("/internal/routing/decide").with(user(principal(CUSTOMER))).with(csrf()).contentType("application/json").content(json.writeValueAsString(Map.of("message","x".repeat(2001))))).andExpect(status().isBadRequest());
    }
}
