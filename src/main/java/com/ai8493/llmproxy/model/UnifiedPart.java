package com.ai8493.llmproxy.model;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Builder;

@Builder
public record UnifiedPart(
    String type,
    String text,
    JsonNode imageData,
    UnifiedToolCall functionCall,
    JsonNode functionResponse
) {}
