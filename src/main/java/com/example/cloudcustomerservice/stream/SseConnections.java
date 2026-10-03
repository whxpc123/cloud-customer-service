package com.example.cloudcustomerservice.stream;

import com.example.cloudcustomerservice.handoff.HandoffModel.Actor;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;

/** 同一进程内的连接上限；按已认证租户/账户计数，取消、出错和正常结束都释放。不宣称多实例全局限流。 */
@Component @Profile("local & knowledge")
public class SseConnections {
    private final SseSettings settings;
    private final Map<Actor,Integer> counts = new HashMap<>();
    private int total;
    public SseConnections(SseSettings settings) { this.settings = settings; }
    public <T> Flux<T> limit(Actor actor, Flux<T> source) {
        // 仅在框架订阅时占用；没有自行 subscribe，也不会把一次模型调用消费两遍。
        return Flux.using(() -> acquire(actor), ignored -> source, Lease::close);
    }
    private synchronized Lease acquire(Actor actor) {
        if (total >= settings.maxConnections() || counts.getOrDefault(actor,0) >= settings.maxConnectionsPerAccount())
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"流连接过多，请关闭其他页面后重试");
        total++; counts.merge(actor,1,Integer::sum); return new Lease(actor);
    }
    /** 测试及本机诊断只读计数，不暴露其他账户身份。 */
    public synchronized int active() { return total; }
    private final class Lease implements AutoCloseable {
        private final Actor actor; private boolean closed;
        Lease(Actor actor) { this.actor = actor; }
        @Override public void close() {
            synchronized (SseConnections.this) {
                if (closed) return; closed = true; total--;
                counts.computeIfPresent(actor,(key,count)->count==1?null:count-1);
            }
        }
    }
}
