package com.ai8493.llmproxy.model;

import lombok.Builder;
import java.util.List;

@Builder
public record UnifiedMessage(
    Role role,
    String content,
    List<UnifiedPart> parts,
    List<UnifiedToolCall> toolCalls,
    String toolCallId,
    String name,
    String reasoningContent
) {
    public enum Role { SYSTEM, USER, ASSISTANT, TOOL }
}
