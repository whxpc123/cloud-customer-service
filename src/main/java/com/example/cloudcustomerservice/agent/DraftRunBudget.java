package com.example.cloudcustomerservice.agent;

import java.time.Duration;
import java.util.concurrent.atomic.*;

/** 每轮独享的单调时钟预算；停止后模型和工具入口都拒绝继续工作。取消不等于供应商停止计费。 */
final class DraftRunBudget {
    private final long started = System.nanoTime();
    private final long deadline;
    private final AtomicBoolean stopped = new AtomicBoolean();
    final AtomicInteger modelCalls = new AtomicInteger();
    DraftRunBudget(Duration duration) { deadline = started + duration.toNanos(); }
    long remainingMillis() {
        long left = (deadline - System.nanoTime()) / 1_000_000;
        if (stopped.get() || left <= 0 || Thread.currentThread().isInterrupted()) throw new IllegalStateException("本轮已停止");
        return left;
    }
    long elapsedMillis() { return (System.nanoTime() - started) / 1_000_000; }
    void stop() { stopped.set(true); }
}
