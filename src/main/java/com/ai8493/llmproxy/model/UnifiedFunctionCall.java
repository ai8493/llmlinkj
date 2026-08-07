package com.ai8493.llmproxy.model;

import com.fasterxml.jackson.databind.JsonNode;

public record UnifiedFunctionCall(
    String name,
    JsonNode arguments
) {

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private String name;
        private JsonNode arguments;

        public Builder name(String name) { this.name = name; return this; }
        public Builder arguments(JsonNode arguments) { this.arguments = arguments; return this; }

        public UnifiedFunctionCall build() {
            return new UnifiedFunctionCall(name, arguments);
        }
    }
}
