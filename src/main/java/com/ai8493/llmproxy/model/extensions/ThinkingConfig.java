package com.ai8493.llmproxy.model.extensions;

public record ThinkingConfig(
    String type,
    Integer budgetTokens
) {
    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private String type;
        private Integer budgetTokens;

        public Builder type(String type) { this.type = type; return this; }
        public Builder budgetTokens(Integer budgetTokens) { this.budgetTokens = budgetTokens; return this; }

        public ThinkingConfig build() {
            return new ThinkingConfig(type, budgetTokens);
        }
    }
}
