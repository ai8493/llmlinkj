package com.ai8493.llmproxy.model;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

public record UnifiedMessage(
    Role role,
    String content,
    List<UnifiedPart> parts,
    List<UnifiedToolCall> toolCalls,
    String toolCallId,
    String name,
    String reasoningContent,
    String thinkingSignature,
    List<UnifiedPart> systemBlocks,
    String refusal,
    JsonNode audio,
    JsonNode annotations
) {
    public enum Role { SYSTEM, USER, ASSISTANT, TOOL }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private Role role;
        private String content;
        private List<UnifiedPart> parts;
        private List<UnifiedToolCall> toolCalls;
        private String toolCallId;
        private String name;
        private String reasoningContent;
        private String thinkingSignature;
        private List<UnifiedPart> systemBlocks;
        private String refusal;
        private JsonNode audio;
        private JsonNode annotations;

        public Builder role(Role role) { this.role = role; return this; }
        public Builder content(String content) { this.content = content; return this; }
        public Builder parts(List<UnifiedPart> parts) { this.parts = parts; return this; }
        public Builder toolCalls(List<UnifiedToolCall> toolCalls) { this.toolCalls = toolCalls; return this; }
        public Builder toolCallId(String toolCallId) { this.toolCallId = toolCallId; return this; }
        public Builder name(String name) { this.name = name; return this; }
        public Builder reasoningContent(String reasoningContent) { this.reasoningContent = reasoningContent; return this; }
        public Builder thinkingSignature(String thinkingSignature) { this.thinkingSignature = thinkingSignature; return this; }
        public Builder systemBlocks(List<UnifiedPart> systemBlocks) { this.systemBlocks = systemBlocks; return this; }
        public Builder refusal(String refusal) { this.refusal = refusal; return this; }
        public Builder audio(JsonNode audio) { this.audio = audio; return this; }
        public Builder annotations(JsonNode annotations) { this.annotations = annotations; return this; }

        public UnifiedMessage build() {
            return new UnifiedMessage(role, content, parts, toolCalls,
                toolCallId, name, reasoningContent, thinkingSignature, systemBlocks,
                refusal, audio, annotations);
        }
    }
}
