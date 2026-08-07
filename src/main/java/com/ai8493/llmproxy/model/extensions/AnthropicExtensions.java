package com.ai8493.llmproxy.model.extensions;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;

public record AnthropicExtensions(
    List<String> betaHeaders,
    Map<String, Integer> cacheBreakdown,
    String matchedStopSequence,
    JsonNode rawSystemArray,
    JsonNode cacheControlByBlock,
    Boolean disableParallelToolUse,
    String metadataUserId,
    JsonNode citations
) {
    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private List<String> betaHeaders;
        private Map<String, Integer> cacheBreakdown;
        private String matchedStopSequence;
        private JsonNode rawSystemArray;
        private JsonNode cacheControlByBlock;
        private Boolean disableParallelToolUse;
        private String metadataUserId;
        private JsonNode citations;

        public Builder betaHeaders(List<String> v) { this.betaHeaders = v; return this; }
        public Builder cacheBreakdown(Map<String, Integer> v) { this.cacheBreakdown = v; return this; }
        public Builder matchedStopSequence(String v) { this.matchedStopSequence = v; return this; }
        public Builder rawSystemArray(JsonNode v) { this.rawSystemArray = v; return this; }
        public Builder cacheControlByBlock(JsonNode v) { this.cacheControlByBlock = v; return this; }
        public Builder disableParallelToolUse(Boolean v) { this.disableParallelToolUse = v; return this; }
        public Builder metadataUserId(String v) { this.metadataUserId = v; return this; }
        public Builder citations(JsonNode v) { this.citations = v; return this; }

        public AnthropicExtensions build() {
            return new AnthropicExtensions(betaHeaders, cacheBreakdown, matchedStopSequence, rawSystemArray, cacheControlByBlock, disableParallelToolUse, metadataUserId, citations);
        }
    }
}
