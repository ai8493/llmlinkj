package com.ai8493.llmproxy.config;

import java.time.Duration;

public record BackendConfig(
    String protocol,
    String apiKey,
    String baseUrl,
    String defaultModel,
    Integer defaultMaxTokens,
    Duration connectTimeout,
    Duration readTimeout,
    Duration writeTimeout,
    PoolConfig pool
) {
    public record PoolConfig(
        int maxIdleConnections,
        Duration keepAliveDuration
    ) {}
}
