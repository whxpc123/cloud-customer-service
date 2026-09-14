package com.example.cloudcustomerservice.tool;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 一轮请求内的工具调用轨迹，在 Java 工具真正执行时追加结果。
 * 结果快照供前端展示实际调用次数；同步访问避免共享列表在读写时发生竞争。
 */
public final class OrderToolTrace {
    public static final String CONTEXT_KEY = "orderToolTrace";
    private final String id = UUID.randomUUID().toString();
    private final List<OrderLookupResult> results = new ArrayList<>();
    /**
     * 返回本轮轨迹的关联 ID，用于配对工具请求与结果日志。
     */
    public String id() { return id; }
    /**
     * 追加一次已执行工具的结果；多次调用保留原始执行记录而不是覆盖。
     */
    public synchronized void add(OrderLookupResult result) { results.add(result); }
    /**
     * 返回当前结果的不可变列表副本，调用方无法修改内部轨迹。
     */
    public synchronized List<OrderLookupResult> results() { return List.copyOf(results); }
}
