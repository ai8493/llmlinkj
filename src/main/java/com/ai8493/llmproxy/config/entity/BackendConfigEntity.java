package com.ai8493.llmproxy.config.entity;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

@Table("backend_config")
public record BackendConfigEntity(
    @Id String name,
    String protocol,
    String apiKey,
    String baseUrl,
    String defaultModel,
    Integer defaultMaxTokens,
    long connectTimeout,
    long readTimeout,
    long writeTimeout,
    int maxIdleConnections,
    long keepAliveDuration,
    String reasoningEffortMode,
    String reasoningEffortDefault,
    String thinkingDefaultType,
    Integer thinkingDefaultBudget,
    String updatedAt
) {}
