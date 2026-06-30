package com.ai8493.llmproxy.model;

import lombok.Builder;
import java.util.List;

@Builder
public record UnifiedChatRequest(
    String model,
    List<UnifiedMessage> messages,
    UnifiedGenerationConfig config,
    List<UnifiedTool> tools,
    UnifiedToolChoice toolChoice,
    boolean stream
) {}
