package com.example.cloudcustomerservice.tool;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** 每次 HTTP 请求单独创建；只记录实际执行结果，不从模型自然语言推测是否调用工具。 */
public final class OrderToolTrace {
    public static final String CONTEXT_KEY = "orderToolTrace";
    private final String id = UUID.randomUUID().toString();
    private final List<OrderLookupResult> results = new ArrayList<>();
    public String id() { return id; }
    public synchronized void add(OrderLookupResult result) { results.add(result); }
    public synchronized List<OrderLookupResult> results() { return List.copyOf(results); }
}
