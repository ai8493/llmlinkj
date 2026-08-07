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
    PoolConfig pool,
    ReasoningConfig reasoning
) {
    public record PoolConfig(
        int maxIdleConnections,
        Duration keepAliveDuration
    ) {}

    // 后端 reasoning/thinking 配置,允许按后端能力映射 effort + 兜底默认值
    // effortMode: passthrough(默认)/low_high/openrouter/deepseek,控制 IR reasoningEffort -> 后端 effort 的映射规则
    // effortDefault: 客户端未传 effort 时的默认值(low/medium/high/xhigh/none/minimal)
    // thinkingDefaultType: 客户端未传 thinking 时的默认 type(enabled/disabled/adaptive)
    // thinkingDefaultBudget: thinkingDefaultType=enabled 时的默认 budget_tokens
    public record ReasoningConfig(
        String effortMode,
        String effortDefault,
        String thinkingDefaultType,
        Integer thinkingDefaultBudget
    ) {}
}
