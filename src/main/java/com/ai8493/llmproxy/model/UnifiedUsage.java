package com.ai8493.llmproxy.model;

import lombok.Builder;

@Builder
public record UnifiedUsage(
    int promptTokens,
    int completionTokens,
    int totalTokens,
    int cachedTokens,
    int reasoningTokens
) {}
