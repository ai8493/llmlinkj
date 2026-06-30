package com.ai8493.llmproxy.model;

import lombok.Builder;

@Builder
public record UnifiedToolCall(
    String id,
    String type,
    UnifiedFunctionCall function
) {}
