package com.ai8493.llmproxy.model;

public record UnifiedToolCall(
    Integer index,
    String id,
    String type,
    UnifiedFunctionCall function
) {

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private Integer index;
        private String id;
        private String type;
        private UnifiedFunctionCall function;

        public Builder index(Integer index) { this.index = index; return this; }
        public Builder id(String id) { this.id = id; return this; }
        public Builder type(String type) { this.type = type; return this; }
        public Builder function(UnifiedFunctionCall function) { this.function = function; return this; }

        public UnifiedToolCall build() {
            return new UnifiedToolCall(index, id, type, function);
        }
    }
}
