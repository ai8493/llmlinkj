package com.ai8493.llmproxy.model;

import lombok.Builder;

@Builder
public record UnifiedTool(
    String type,
    UnifiedFunctionDefinition function
) {}
