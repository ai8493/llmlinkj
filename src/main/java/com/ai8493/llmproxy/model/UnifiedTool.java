package com.ai8493.llmproxy.model;

import com.fasterxml.jackson.databind.JsonNode;

public record UnifiedTool(
    String type,
    UnifiedFunctionDefinition function,
    JsonNode rawTool
) {

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private String type;
        private UnifiedFunctionDefinition function;
        private JsonNode rawTool;

        public Builder type(String type) { this.type = type; return this; }
        public Builder function(UnifiedFunctionDefinition function) { this.function = function; return this; }
        public Builder rawTool(JsonNode rawTool) { this.rawTool = rawTool; return this; }

        public UnifiedTool build() {
            return new UnifiedTool(type, function, rawTool);
        }
    }
}
