package com.ai8493.llmproxy.model;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Builder;

@Builder
public record UnifiedFunctionCall(
    String name,
    JsonNode arguments
) {}
