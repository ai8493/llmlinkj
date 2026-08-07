package com.ai8493.llmproxy.model;

import com.fasterxml.jackson.databind.JsonNode;

public record UnifiedFunctionDefinition(
    String name,
    String description,
    JsonNode parameters
) {

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private String name;
        private String description;
        private JsonNode parameters;

        public Builder name(String name) { this.name = name; return this; }
        public Builder description(String description) { this.description = description; return this; }
        public Builder parameters(JsonNode parameters) { this.parameters = parameters; return this; }

        public UnifiedFunctionDefinition build() {
            return new UnifiedFunctionDefinition(name, description, parameters);
        }
    }
}
