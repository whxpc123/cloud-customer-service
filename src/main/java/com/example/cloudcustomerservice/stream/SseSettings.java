package com.example.cloudcustomerservice.stream;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** 本地教学的资源边界；时间可由测试缩短，不代表经过生产压测的参数。 */
@ConfigurationProperties("app.sse")
public record SseSettings(@DefaultValue("2s") Duration pollInterval,
        @DefaultValue("15s") Duration heartbeatInterval, @DefaultValue("4m") Duration stateLifetime,
        @DefaultValue("30s") Duration modelIdleTimeout, @DefaultValue("2m") Duration modelDeadline,
        @DefaultValue("32768") int maxCharacters, @DefaultValue("64") int maxConnections,
        @DefaultValue("4") int maxConnectionsPerAccount) {
    public SseSettings {
        for (var d : new Duration[]{pollInterval, heartbeatInterval, stateLifetime, modelIdleTimeout, modelDeadline})
            if (d == null || d.isZero() || d.isNegative()) throw new IllegalArgumentException("SSE 时限必须为正数");
        if (maxCharacters < 1 || maxConnections < 1 || maxConnectionsPerAccount < 1)
            throw new IllegalArgumentException("SSE 容量必须为正数");
    }
}
