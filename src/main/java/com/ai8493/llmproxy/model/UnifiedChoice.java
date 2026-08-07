package com.ai8493.llmproxy.model;

import com.fasterxml.jackson.databind.JsonNode;

public record UnifiedChoice(
    int index,
    UnifiedMessage message,
    UnifiedDelta delta,
    String finishReason,
    JsonNode logprobs
) {

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private int index;
        private UnifiedMessage message;
        private UnifiedDelta delta;
        private String finishReason;
        private JsonNode logprobs;

        public Builder index(int index) { this.index = index; return this; }
        public Builder message(UnifiedMessage message) { this.message = message; return this; }
        public Builder delta(UnifiedDelta delta) { this.delta = delta; return this; }
        public Builder finishReason(String finishReason) { this.finishReason = finishReason; return this; }
        public Builder logprobs(JsonNode logprobs) { this.logprobs = logprobs; return this; }

        public UnifiedChoice build() {
            return new UnifiedChoice(index, message, delta, finishReason, logprobs);
        }
    }
}
