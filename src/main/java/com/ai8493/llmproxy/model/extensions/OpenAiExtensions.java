package com.ai8493.llmproxy.model.extensions;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

public record OpenAiExtensions(
    Boolean logprobs,
    Integer topLogprobs,
    Integer n,
    Long seed,
    JsonNode responseFormat,
    JsonNode logitBias,
    JsonNode metadata,
    Boolean store,
    JsonNode audio,
    List<String> modalities,
    JsonNode prediction,
    JsonNode webSearchOptions,
    String previousResponseId,
    List<String> include
) {
    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private Boolean logprobs;
        private Integer topLogprobs;
        private Integer n;
        private Long seed;
        private JsonNode responseFormat;
        private JsonNode logitBias;
        private JsonNode metadata;
        private Boolean store;
        private JsonNode audio;
        private List<String> modalities;
        private JsonNode prediction;
        private JsonNode webSearchOptions;
        private String previousResponseId;
        private List<String> include;

        public Builder logprobs(Boolean v) { this.logprobs = v; return this; }
        public Builder topLogprobs(Integer v) { this.topLogprobs = v; return this; }
        public Builder n(Integer v) { this.n = v; return this; }
        public Builder seed(Long v) { this.seed = v; return this; }
        public Builder responseFormat(JsonNode v) { this.responseFormat = v; return this; }
        public Builder logitBias(JsonNode v) { this.logitBias = v; return this; }
        public Builder metadata(JsonNode v) { this.metadata = v; return this; }
        public Builder store(Boolean v) { this.store = v; return this; }
        public Builder audio(JsonNode v) { this.audio = v; return this; }
        public Builder modalities(List<String> v) { this.modalities = v; return this; }
        public Builder prediction(JsonNode v) { this.prediction = v; return this; }
        public Builder webSearchOptions(JsonNode v) { this.webSearchOptions = v; return this; }
        public Builder previousResponseId(String v) { this.previousResponseId = v; return this; }
        public Builder include(List<String> v) { this.include = v; return this; }

        public OpenAiExtensions build() {
            return new OpenAiExtensions(logprobs, topLogprobs, n, seed, responseFormat, logitBias,
                metadata, store, audio, modalities, prediction, webSearchOptions,
                previousResponseId, include);
        }
    }
}
