package com.ai8493.llmproxy.model;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Builder;
import java.util.List;

@Builder
public record UnifiedGenerationConfig(
    Double temperature,
    Double topP,
    Integer maxOutputTokens,
    List<String> stopSequences,
    String reasoningEffort,
    String user,
    Boolean parallelToolCalls,
    JsonNode streamOptions
) {}
