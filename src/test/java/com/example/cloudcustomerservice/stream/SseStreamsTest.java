package com.example.cloudcustomerservice.stream;

import com.example.cloudcustomerservice.handoff.HandoffModel.*;
import com.example.cloudcustomerservice.security.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.*;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import reactor.core.publisher.*;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;
import static org.assertj.core.api.Assertions.*;

/** 用虚拟时钟验证真正流的生命周期，不用付费模型或固定 sleep 代替取消、超时测试。 */
class SseStreamsTest {
    static final UUID ID=UUID.randomUUID();
    static SseSettings settings(){return new SseSettings(Duration.ofSeconds(2),Duration.ofSeconds(15),Duration.ofMinutes(4),Duration.ofSeconds(30),Duration.ofMinutes(2),100,3,2);}
    static Receipt receipt(Mode mode,long version){return new Receipt(ID,null,mode,version,null,null,null,null,mode.name());}
    @Test void stateSendsInitialThenOnlyChangedVersionsAndCloses(){
        var reads=new AtomicInteger();
        StepVerifier.withVirtualTime(()->SseStreams.states(receipt(Mode.BOT,0),()->switch(reads.incrementAndGet()){
            case 1->receipt(Mode.BOT,0);case 2->receipt(Mode.WAITING_HUMAN,1);default->receipt(Mode.CLOSED,2);
        },Schedulers.immediate(),settings()))
            .assertNext(e->{assertThat(e.event()).isEqualTo("conversation.state");assertThat(e.id()).isEqualTo("0");assertThat(e.retry()).isEqualTo(Duration.ofSeconds(3));})
            .thenAwait(Duration.ofSeconds(4)).expectNextMatches(e->e.id().equals("1"))
            .thenAwait(Duration.ofSeconds(2)).expectNextMatches(e->e.id().equals("2")).verifyComplete();
        assertThat(reads).hasValue(3);
    }
    @Test void unchangedStateProducesCommentHeartbeatWithoutBusinessDuplicate(){
        var reads=new AtomicInteger();
        StepVerifier.withVirtualTime(()->SseStreams.states(receipt(Mode.BOT,0),()->{reads.incrementAndGet();return receipt(Mode.BOT,0);},Schedulers.immediate(),settings()).take(2))
            .expectNextMatches(e->e.data() instanceof Receipt).thenAwait(Duration.ofSeconds(15))
            .expectNextMatches(e->"ping".equals(e.comment())&&e.data()==null&&e.event()==null).verifyComplete();
        assertThat(reads).hasValue(7);
    }
    @Test void closedInitialNeverStartsFurtherQuery(){
        StepVerifier.create(SseStreams.states(receipt(Mode.CLOSED,3),()->{throw new AssertionError("should not query");},Schedulers.immediate(),settings()))
            .expectNextCount(1).verifyComplete();
    }
    @Test void failedReadSendsGenericFailureThenEnds(){
        StepVerifier.withVirtualTime(()->SseStreams.states(receipt(Mode.BOT,0),()->{throw new IllegalStateException("secret db password");},Schedulers.immediate(),settings()))
            .expectNextCount(1).thenAwait(Duration.ofSeconds(2)).assertNext(e->{assertThat(e.event()).isEqualTo("stream.failure");assertThat(e.data().toString()).doesNotContain("secret");}).verifyComplete();
    }
    @Test void connectionLifetimeEndsEvenIfStateNeverChanges(){
        var s=new SseSettings(Duration.ofSeconds(2),Duration.ofSeconds(15),Duration.ofSeconds(5),Duration.ofSeconds(30),Duration.ofMinutes(2),100,3,2);
        StepVerifier.withVirtualTime(()->SseStreams.states(receipt(Mode.BOT,0),()->receipt(Mode.BOT,0),Schedulers.immediate(),s))
            .expectNextCount(1).thenAwait(Duration.ofSeconds(5)).verifyComplete();
    }
    @Test void cancelStopsInFlightQuerySubscriptionAndReleasesLimit(){
        var connections=new SseConnections(settings());var actor=new Actor("tenant",1);var cancelled=new AtomicBoolean();
        StepVerifier.create(connections.limit(actor,Flux.never().doOnCancel(()->cancelled.set(true))))
            .then(()->assertThat(connections.active()).isEqualTo(1)).thenCancel().verify();
        assertThat(cancelled).isTrue();assertThat(connections.active()).isZero();
    }
    @Test void perAccountAndGlobalLimitsRejectWithoutExecutingSources(){
        var c=new SseConnections(settings());var a=new Actor("tenant",1);var b=new Actor("tenant",2);var calls=new AtomicInteger();
        var one=c.limit(a,Flux.never()).subscribe();var two=c.limit(a,Flux.never()).subscribe();
        try{
            StepVerifier.create(c.limit(a,Flux.defer(()->{calls.incrementAndGet();return Flux.empty();}))).expectError(org.springframework.web.server.ResponseStatusException.class).verify();
            var three=c.limit(b,Flux.never()).subscribe();try{StepVerifier.create(c.limit(new Actor("tenant",3),Flux.empty())).expectError(org.springframework.web.server.ResponseStatusException.class).verify();}finally{three.dispose();}
            assertThat(calls).hasValue(0);
        }finally{one.dispose();two.dispose();}assertThat(c.active()).isZero();
    }
    @Test void realFragmentsAreDeliveredBeforeCompletionAndWhitespaceSurvives(){
        var sink=Sinks.many().unicast().<String>onBackpressureBuffer();var subscribed=new AtomicInteger();
        var events=SseStreams.answer(()->{subscribed.incrementAndGet();return sink.asFlux();},settings());
        StepVerifier.create(events).expectNextMatches(e->e.event().equals("turn.started")&&e.data().sequence()==1)
            .then(()->sink.tryEmitNext("你好")).expectNextMatches(e->e.event().equals("answer.delta")&&e.data().text().equals("你好"))
            .then(()->sink.tryEmitNext(" \n")).expectNextMatches(e->e.data().text().equals(" \n"))
            .then(()->sink.tryEmitComplete()).expectNextMatches(e->e.event().equals("turn.completed")&&e.data().sequence()==4).verifyComplete();
        assertThat(subscribed).hasValue(1);
    }
    @Test void upstreamFailureHasOneFailedTerminalNoCompletionAndNoDetails(){
        var events=SseStreams.answer(()->Flux.concat(Flux.just("片段"),Flux.error(new IllegalStateException("secret key"))),settings()).collectList().block();
        assertThat(events).extracting(e->e.event()).containsExactly("turn.started","answer.delta","turn.failed");
        assertThat(events.get(2).data().text()).doesNotContain("secret");assertThat(events.get(2).data().sequence()).isEqualTo(3);
        assertThat(events).allMatch(e->e.data().turnId().equals(events.get(0).data().turnId()));
    }
    @Test void idleTimeoutFailsInsteadOfCompleting(){
        StepVerifier.withVirtualTime(()->SseStreams.answer(Flux::never,settings()))
            .expectNextCount(1).thenAwait(Duration.ofSeconds(30)).expectNextMatches(e->e.event().equals("turn.failed")).verifyComplete();
    }
    @Test void totalDeadlineFailsEvenWhenFragmentsKeepArriving(){
        var s=new SseSettings(Duration.ofSeconds(2),Duration.ofSeconds(15),Duration.ofMinutes(4),Duration.ofSeconds(30),Duration.ofSeconds(3),100,3,2);
        StepVerifier.withVirtualTime(()->SseStreams.answer(()->Flux.interval(Duration.ofSeconds(1)).map(i->"x"),s))
            .expectNextCount(1).thenAwait(Duration.ofSeconds(3)).expectNextCount(2).expectNextMatches(e->e.event().equals("turn.failed")).verifyComplete();
    }
    @Test void outputLimitCancelsAndFailsWithoutForwardingOversizedChunk(){
        var cancelled=new AtomicBoolean();
        StepVerifier.create(SseStreams.answer(()->Flux.just("x".repeat(101)).doOnCancel(()->cancelled.set(true)),settings()))
            .expectNextCount(1).expectNextMatches(e->e.event().equals("turn.failed")).verifyComplete();assertThat(cancelled).isTrue();
    }
    @Test void modelCancelPropagatesWithoutSynthesizingSuccessfulTerminal(){
        var cancelled=new AtomicBoolean();StepVerifier.create(SseStreams.answer(()->Flux.concat(Flux.just("first"),Flux.<String>never()).doOnCancel(()->cancelled.set(true)),settings()))
            .expectNextCount(2).thenCancel().verify();assertThat(cancelled).isTrue();
    }
    @Test void loginContextCrossesWorkerBoundaryAndIsAlwaysRestored(){
        var actor=new Actor("tenant",1);var principal=new HandoffPrincipal("test","unused",actor,List.of(new SimpleGrantedAuthority("customer:chat")));
        var context=SecurityContextHolder.createEmptyContext();context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal,"unused",principal.getAuthorities()));
        var session=new MockHttpSession();session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,context);
        var identity=new StreamIdentity(actor,session);SecurityContextHolder.clearContext();
        assertThat(identity.read(HandoffIdentity::actor)).isEqualTo(actor);assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThatThrownBy(()->identity.read(()->{throw new IllegalStateException();})).isInstanceOf(IllegalStateException.class);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();session.invalidate();
        assertThatThrownBy(()->identity.read(()->"never")).isInstanceOf(IllegalStateException.class);
    }
    @Test void changedPrincipalOrRevokedRoleCannotKeepReading(){
        var actor=new Actor("tenant",1);var p=new HandoffPrincipal("test","unused",actor,List.of());
        var context=SecurityContextHolder.createEmptyContext();context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(p,"unused",List.of()));
        var session=new MockHttpSession();session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,context);
        assertThatThrownBy(()->new StreamIdentity(actor,session).read(()->"secret")).isInstanceOf(IllegalStateException.class);
    }
}
