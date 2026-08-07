package com.ai8493.llmproxy.model;

import com.ai8493.llmproxy.model.extensions.ThinkingConfig;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

public record UnifiedGenerationConfig(
    Double temperature,
    Double topP,
    Integer maxOutputTokens,
    List<String> stopSequences,
    String reasoningEffort,
    String user,
    Boolean parallelToolCalls,
    JsonNode streamOptions,
    Integer topK,
    ThinkingConfig thinkingConfig,
    String serviceTier,
    Double presencePenalty,
    Double frequencyPenalty,
    Long seed,
    Integer maxCompletionTokens,
    String mediaResolution
) {

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private Double temperature;
        private Double topP;
        private Integer maxOutputTokens;
        private List<String> stopSequences;
        private String reasoningEffort;
        private String user;
        private Boolean parallelToolCalls;
        private JsonNode streamOptions;
        private Integer topK;
        private ThinkingConfig thinkingConfig;
        private String serviceTier;
        private Double presencePenalty;
        private Double frequencyPenalty;
        private Long seed;
        private Integer maxCompletionTokens;
        private String mediaResolution;

        public Builder temperature(Double temperature) { this.temperature = temperature; return this; }
        public Builder topP(Double topP) { this.topP = topP; return this; }
        public Builder maxOutputTokens(Integer maxOutputTokens) { this.maxOutputTokens = maxOutputTokens; return this; }
        public Builder stopSequences(List<String> stopSequences) { this.stopSequences = stopSequences; return this; }
        public Builder reasoningEffort(String reasoningEffort) { this.reasoningEffort = reasoningEffort; return this; }
        public Builder user(String user) { this.user = user; return this; }
        public Builder parallelToolCalls(Boolean parallelToolCalls) { this.parallelToolCalls = parallelToolCalls; return this; }
        public Builder streamOptions(JsonNode streamOptions) { this.streamOptions = streamOptions; return this; }
        public Builder topK(Integer topK) { this.topK = topK; return this; }
        public Builder thinkingConfig(ThinkingConfig thinkingConfig) { this.thinkingConfig = thinkingConfig; return this; }
        public Builder serviceTier(String serviceTier) { this.serviceTier = serviceTier; return this; }
        public Builder presencePenalty(Double presencePenalty) { this.presencePenalty = presencePenalty; return this; }
        public Builder frequencyPenalty(Double frequencyPenalty) { this.frequencyPenalty = frequencyPenalty; return this; }
        public Builder seed(Long seed) { this.seed = seed; return this; }
        public Builder maxCompletionTokens(Integer maxCompletionTokens) { this.maxCompletionTokens = maxCompletionTokens; return this; }
        public Builder mediaResolution(String mediaResolution) { this.mediaResolution = mediaResolution; return this; }

        public UnifiedGenerationConfig build() {
            return new UnifiedGenerationConfig(temperature, topP, maxOutputTokens, stopSequences,
                reasoningEffort, user, parallelToolCalls, streamOptions,
                topK, thinkingConfig, serviceTier,
                presencePenalty, frequencyPenalty, seed, maxCompletionTokens, mediaResolution);
        }
    }
}
