package com.example.cloudcustomerservice.acceptance;

import com.example.cloudcustomerservice.handoff.*;
import com.example.cloudcustomerservice.security.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.*;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import static com.example.cloudcustomerservice.handoff.HandoffModel.*;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

/** 真正提交事务的数据库切片；不创建 AI Bean、不需要模型 Key，也不把模拟身份当成 HTTP 登录验收。 */
@JdbcTest(properties={"spring.flyway.enabled=true","spring.sql.init.mode=never"})
@AutoConfigureTestDatabase(replace=AutoConfigureTestDatabase.Replace.NONE)
@Import({AcceptanceDatabase.class,HumanHandoffService.class,HandoffAcceptanceTest.Security.class})
@ActiveProfiles({"local","knowledge"})
@Transactional(propagation=Propagation.NOT_SUPPORTED)
@EnabledIfEnvironmentVariable(named="RUN_ACCEPTANCE_TESTS",matches="true")
class HandoffAcceptanceTest {
    @TestConfiguration @EnableMethodSecurity
    static class Security { @Bean ObjectMapper json(){return new ObjectMapper().findAndRegisterModules();} }
    @Autowired HumanHandoffService service;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    final Actor owner=new Actor("acceptance-"+UUID.randomUUID(),1001);
    Actor agent(long id){return new Actor(owner.tenantId(),id);}
    /** 业务 Actor 必须与自定义 principal 一致，普通 WithMockUser 不代表本项目真实身份。 */
    static <T>T as(Actor actor,Supplier<T> work){
        var previous=SecurityContextHolder.getContext();var context=SecurityContextHolder.createEmptyContext();
        var p=new HandoffPrincipal("fixture-"+actor.accountId(),"unused",actor,List.of(new SimpleGrantedAuthority(actor.accountId()>=9000?"support:serve":"customer:chat")));
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(p,"unused",p.getAuthorities()));
        SecurityContextHolder.setContext(context);try{return work.get();}finally{SecurityContextHolder.setContext(previous);}
    }
    UUID create(){return as(owner,()->service.createConversation(owner).conversationId());}
    Receipt request(UUID id){return as(owner,()->service.request(owner,id));}
    @BeforeEach void isolated(){assertThat(jdbc.queryForObject("select current_database()",String.class)).isEqualTo("stage1_acceptance");}
    @AfterEach void clearIdentity(){SecurityContextHolder.clearContext();}

    /** 两次调用各自提交后，第三次读取仍是同一受理号与版本。 */
    @Test void repeatedRequestCommitsOneReceipt(){
        var id=create();var first=request(id);assertThat(request(id)).isEqualTo(first);
        assertThat(as(owner,()->service.get(owner,id))).isEqualTo(first);
        assertThat(first.mode()).isEqualTo(Mode.WAITING_HUMAN);assertThat(first.version()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from ai.cs_message where conversation_id=?",Long.class,id)).isEqualTo(1);
    }
    /** 拥有有效身份也不能读取或修改别的租户/客户；伪造 Actor 则在查询前拒绝。 */
    @Test void otherUsersTenantsAndForgedActorCannotMutate(){
        var id=create();for(var other:List.of(new Actor(owner.tenantId(),2002),new Actor("different-tenant",1001)))
            assertThatThrownBy(()->as(other,()->service.request(other,id))).isInstanceOfSatisfying(ResponseStatusException.class,e->assertThat(e.getStatusCode().value()).isEqualTo(404));
        assertThatThrownBy(()->as(agent(2002),()->service.get(owner,id))).isInstanceOf(AccessDeniedException.class);
        assertThat(as(owner,()->service.get(owner,id)).mode()).isEqualTo(Mode.BOT);
    }
    /** 真实 Spring 方法代理保护客服队列，禁止只给服务直接 new 后测试。 */
    @Test void customerCannotReadSupportQueue(){assertThatThrownBy(()->as(owner,()->service.waiting(owner))).isInstanceOf(AccessDeniedException.class);}
    /** 已结束的受理单不能被重复申请重开；相同客服重复领取/结束仍幂等。 */
    @Test void acceptedAndClosedAreDurableAndCannotReopen(){
        var id=create();request(id);var a=agent(9001);var accepted=as(a,()->service.accept(a,id));
        assertThat(as(a,()->service.accept(a,id))).isEqualTo(accepted);
        var closed=as(a,()->service.close(a,id));assertThat(request(id)).isEqualTo(closed);
        assertThat(as(a,()->service.close(a,id))).isEqualTo(closed);assertThat(closed.mode()).isEqualTo(Mode.CLOSED);
    }
    /** 先拿真实行锁，确认两个请求都在数据库等待，再提交释放；不靠 sleep 猜是否并发。 */
    @Test void concurrentRequestsWaitForRealRowLockAndReuseReceipt()throws Exception{
        var id=create();var results=whileRowLocked(id,()->request(id),()->request(id));
        assertThat(results.get(0)).isEqualTo(results.get(1));
        assertThat(jdbc.queryForObject("select count(*) from ai.cs_message where conversation_id=?",Long.class,id)).isEqualTo(1);
    }
    /** 两个真实事务抢同一受理单，一次领取成功，一次 409，不只是两个顺序调用。 */
    @Test void concurrentAgentsWaitThenOnlyOneCanAccept()throws Exception{
        var id=create();request(id);
        var results=whileRowLocked(id,()->claim(agent(9001),id),()->claim(agent(9002),id));
        assertThat(results.stream().filter(Receipt.class::isInstance).count()).isEqualTo(1);assertThat(results).contains(409);
        assertThat(as(owner,()->service.get(owner,id)).version()).isEqualTo(2);
    }
    Object claim(Actor agent,UUID id){try{return as(agent,()->service.accept(agent,id));}catch(ResponseStatusException e){return e.getStatusCode().value();}}
    /** pg_blocking_pids 是同步证据；超时仅限制失败等待时间，不能取代锁断言。 */
    List<Object> whileRowLocked(UUID id,Supplier<Object> first,Supplier<Object> second)throws Exception{
        var pool=Executors.newFixedThreadPool(2);
        try(var connection=dataSource.getConnection()){
            connection.setAutoCommit(false);
            try(var statement=connection.prepareStatement("select id from ai.cs_conversation where id=? for update")){
                statement.setObject(1,id);statement.executeQuery().close();
            }
            var a=pool.submit(first::get);var b=pool.submit(second::get);
            try{
                await().atMost(Duration.ofSeconds(3)).pollInterval(Duration.ofMillis(20)).untilAsserted(()->
                    assertThat(jdbc.queryForObject("select count(*) from pg_stat_activity where datname=current_database() and cardinality(pg_blocking_pids(pid))>0",Integer.class)).isGreaterThanOrEqualTo(2));
            }finally{connection.commit();}
            return List.of(a.get(6,TimeUnit.SECONDS),b.get(6,TimeUnit.SECONDS));
        }finally{pool.shutdownNow();}
    }
    /** 导出非敏感运行事实供报告引用；不输出 JDBC URL、数据库或账户口令。 */
    @Test void recordActualDatabaseAndMigrationVersions()throws Exception{
        var metadata=Map.of("image",AcceptanceDatabase.IMAGE,"postgres",jdbc.queryForObject("select version()",String.class),
            "migrations",jdbc.queryForList("select version,script,checksum,success from flyway_schema_history where version is not null order by installed_rank"));
        Files.writeString(Path.of("target/acceptance-database.json"),new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(metadata));
        assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where not success",Long.class)).isZero();
    }
}
