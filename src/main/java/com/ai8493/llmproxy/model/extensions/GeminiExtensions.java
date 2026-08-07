package com.ai8493.llmproxy.model.extensions;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;

public record GeminiExtensions(
    String responseMimeType,
    JsonNode responseSchema,
    Integer candidateCount,
    JsonNode safetySettings,
    JsonNode tools,
    Map<String, String> labels,
    JsonNode promptFeedback,
    JsonNode groundingMetadata,
    JsonNode citationMetadata
) {
    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private String responseMimeType;
        private JsonNode responseSchema;
        private Integer candidateCount;
        private JsonNode safetySettings;
        private JsonNode tools;
        private Map<String, String> labels;
        private JsonNode promptFeedback;
        private JsonNode groundingMetadata;
        private JsonNode citationMetadata;

        public Builder responseMimeType(String v) { this.responseMimeType = v; return this; }
        public Builder responseSchema(JsonNode v) { this.responseSchema = v; return this; }
        public Builder candidateCount(Integer v) { this.candidateCount = v; return this; }
        public Builder safetySettings(JsonNode v) { this.safetySettings = v; return this; }
        public Builder tools(JsonNode v) { this.tools = v; return this; }
        public Builder labels(Map<String, String> v) { this.labels = v; return this; }
        public Builder promptFeedback(JsonNode v) { this.promptFeedback = v; return this; }
        public Builder groundingMetadata(JsonNode v) { this.groundingMetadata = v; return this; }
        public Builder citationMetadata(JsonNode v) { this.citationMetadata = v; return this; }

        public GeminiExtensions build() {
            return new GeminiExtensions(responseMimeType, responseSchema, candidateCount, safetySettings, tools, labels, promptFeedback, groundingMetadata, citationMetadata);
        }
    }
}
