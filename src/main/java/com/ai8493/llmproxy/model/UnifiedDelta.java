package com.ai8493.llmproxy.model;

import lombok.Builder;
import java.util.List;

@Builder
public record UnifiedDelta(
    String role,
    String content,
    List<UnifiedToolCall> toolCalls,
    String reasoningContent
) {}
