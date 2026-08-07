package com.ai8493.llmproxy.model;

public record UnifiedUsage(
    int promptTokens,
    int completionTokens,
    int totalTokens,
    int cachedTokens,
    int reasoningTokens,
    int cacheCreationTokens
) {

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private int promptTokens;
        private int completionTokens;
        private int totalTokens;
        private int cachedTokens;
        private int reasoningTokens;
        private int cacheCreationTokens;

        public Builder promptTokens(int promptTokens) { this.promptTokens = promptTokens; return this; }
        public Builder completionTokens(int completionTokens) { this.completionTokens = completionTokens; return this; }
        public Builder totalTokens(int totalTokens) { this.totalTokens = totalTokens; return this; }
        public Builder cachedTokens(int cachedTokens) { this.cachedTokens = cachedTokens; return this; }
        public Builder reasoningTokens(int reasoningTokens) { this.reasoningTokens = reasoningTokens; return this; }
        public Builder cacheCreationTokens(int cacheCreationTokens) { this.cacheCreationTokens = cacheCreationTokens; return this; }

        public UnifiedUsage build() {
            return new UnifiedUsage(promptTokens, completionTokens, totalTokens,
                cachedTokens, reasoningTokens, cacheCreationTokens);
        }
    }
}
