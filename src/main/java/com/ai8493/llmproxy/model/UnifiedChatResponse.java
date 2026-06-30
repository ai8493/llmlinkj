package com.ai8493.llmproxy.model;

import lombok.Builder;
import java.util.List;

@Builder
public record UnifiedChatResponse(
    String id,
    String model,
    String object,
    long created,
    List<UnifiedChoice> choices,
    UnifiedUsage usage,
    String systemFingerprint
) {}
