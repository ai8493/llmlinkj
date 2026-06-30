package com.ai8493.llmproxy.model;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Builder;

@Builder
public record UnifiedChoice(
    int index,
    UnifiedMessage message,
    UnifiedDelta delta,
    String finishReason,
    JsonNode logprobs
) {}
