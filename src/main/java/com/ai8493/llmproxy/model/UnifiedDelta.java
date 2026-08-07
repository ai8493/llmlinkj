package com.ai8493.llmproxy.model;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

public record UnifiedDelta(
    String role,
    String content,
    List<UnifiedToolCall> toolCalls,
    String reasoningContent,
    List<IndexedArgumentDelta> toolCallArgumentDeltas,
    String thinkingSignature,
    JsonNode logprobs
) {

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private String role;
        private String content;
        private List<UnifiedToolCall> toolCalls;
        private String reasoningContent;
        private List<IndexedArgumentDelta> toolCallArgumentDeltas;
        private String thinkingSignature;
        private JsonNode logprobs;

        public Builder role(String role) { this.role = role; return this; }
        public Builder content(String content) { this.content = content; return this; }
        public Builder toolCalls(List<UnifiedToolCall> toolCalls) { this.toolCalls = toolCalls; return this; }
        public Builder reasoningContent(String reasoningContent) { this.reasoningContent = reasoningContent; return this; }
        public Builder toolCallArgumentDeltas(List<IndexedArgumentDelta> toolCallArgumentDeltas) { this.toolCallArgumentDeltas = toolCallArgumentDeltas; return this; }
        public Builder thinkingSignature(String thinkingSignature) { this.thinkingSignature = thinkingSignature; return this; }
        public Builder logprobs(JsonNode logprobs) { this.logprobs = logprobs; return this; }

        public UnifiedDelta build() {
            return new UnifiedDelta(role, content, toolCalls, reasoningContent, toolCallArgumentDeltas,
                thinkingSignature, logprobs);
        }
    }
}
