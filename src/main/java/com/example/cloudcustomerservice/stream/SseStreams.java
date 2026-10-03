package com.example.cloudcustomerservice.stream;

import com.example.cloudcustomerservice.handoff.HandoffModel.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.*;
import reactor.core.scheduler.Scheduler;

/** 协议组装与业务读取分离，便于用虚拟时钟验证心跳、去重、失败和取消。 */
public final class SseStreams {
    private SseStreams() { }
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(SseStreams.class);
    public record AnswerEvent(UUID turnId, long sequence, String text) { }
    private record Part(String type, String text) { }

    /** 每次短查询完成后再计时；不使用 interval 叠加阻塞 JDBC 查询。重连读快照，不回放历史事件。 */
    public static Flux<ServerSentEvent<Object>> states(Receipt initial, Supplier<Receipt> read,
            Scheduler scheduler, SseSettings settings) {
        var next = Mono.delay(settings.pollInterval())
                .then(Mono.fromCallable(read::get).subscribeOn(scheduler));
        Flux<ServerSentEvent<Object>> states = Flux.concat(Mono.just(initial), next.repeat())
                .distinctUntilChanged(Receipt::version)
                .map(r -> ServerSentEvent.<Object>builder(r).event("conversation.state")
                        .id(Long.toString(r.version())).retry(Duration.ofSeconds(3)).build());
        Flux<ServerSentEvent<Object>> heartbeat = Flux.interval(settings.heartbeatInterval())
                .onBackpressureDrop().map(i -> ServerSentEvent.<Object>builder().comment("ping").build());
        return Flux.merge(1, states, heartbeat)
                .takeUntil(e -> e.data() instanceof Receipt r && r.mode() == Mode.CLOSED)
                .take(settings.stateLifetime())
                .doOnError(e -> log.warn("[SSE FAILURE] kind=state errorType={}",e.getClass().getSimpleName()))
                .onErrorResume(e -> Flux.just(ServerSentEvent.<Object>builder(Map.of(
                        "code","STATE_UNAVAILABLE","message","状态同步已停止，请检查登录并重新连接。"))
                        .event("stream.failure").build()));
    }

    /** 四种模型事件；空格换行保留。正常 EOF 才 completed，超时/限额/异常统一 failed，不泄露异常正文。 */
    public static Flux<ServerSentEvent<AnswerEvent>> answer(Supplier<Flux<String>> generate, SseSettings settings) {
        return Flux.defer(() -> {
            UUID turnId = UUID.randomUUID(); var size = new AtomicInteger();
            Flux<Part> generated = Flux.defer(generate).filter(text -> !text.isEmpty())
                    .timeout(settings.modelIdleTimeout())
                    .takeUntilOther(Mono.delay(settings.modelDeadline()).flatMap(i -> Mono.error(new TimeoutException("deadline"))))
                    .map(text -> {
                        if (text.length() > settings.maxCharacters() - size.get()) throw new IllegalStateException("output limit");
                        size.addAndGet(text.length()); return new Part("answer.delta",text);
                    });
            return Flux.concat(Flux.just(new Part("turn.started","已进入模型生成流程。")), generated,
                    Flux.just(new Part("turn.completed","本次模型文本输出结束，未执行任何业务，也未保存正式消息。")))
                    .doOnError(e -> log.warn("[SSE FAILURE] kind=model turnId={} errorType={}",turnId,e.getClass().getSimpleName()))
                    .onErrorResume(e -> Flux.just(new Part("turn.failed","生成未完成，请重新发起请求。")))
                    .index().map(item -> {
                        long sequence = item.getT1()+1; Part part = item.getT2();
                        return ServerSentEvent.builder(new AnswerEvent(turnId,sequence,part.text()))
                                .event(part.type()).id(turnId+":"+sequence).build();
                    });
        });
    }
}
