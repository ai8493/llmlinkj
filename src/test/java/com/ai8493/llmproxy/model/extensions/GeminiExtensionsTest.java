package com.ai8493.llmproxy.model.extensions;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

class GeminiExtensionsTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void shouldBuildWithAllFields() throws Exception {
        JsonNode responseSchema = mapper.readTree("{\"type\":\"object\"}");
        JsonNode safetySettings = mapper.readTree("[{\"category\":\"HARM_CATEGORY\"}]");
        JsonNode tools = mapper.readTree("{\"functionDeclarations\":[]}");
        GeminiExtensions ext = GeminiExtensions.builder()
            .responseMimeType("application/json")
            .responseSchema(responseSchema)
            .candidateCount(3)
            .safetySettings(safetySettings)
            .tools(tools)
            .build();
        assertThat(ext.responseMimeType()).isEqualTo("application/json");
        assertThat(ext.responseSchema()).isNotNull();
        assertThat(ext.candidateCount()).isEqualTo(3);
        assertThat(ext.safetySettings()).isNotNull();
        assertThat(ext.tools()).isNotNull();
    }

    @Test
    void shouldBuildWithDefaults() {
        GeminiExtensions ext = GeminiExtensions.builder().build();
        assertThat(ext.responseMimeType()).isNull();
        assertThat(ext.responseSchema()).isNull();
        assertThat(ext.candidateCount()).isNull();
        assertThat(ext.safetySettings()).isNull();
        assertThat(ext.tools()).isNull();
    }

    @Test
    void shouldBuildWithNewGeminiFields() throws Exception {
        JsonNode promptFeedback = mapper.readTree("{\"blockReason\":\"SAFETY\"}");
        JsonNode groundingMetadata = mapper.readTree("{\"webSearchQueries\":[\"weather\"]}");
        JsonNode citationMetadata = mapper.readTree("[{\"startIndex\":0,\"endIndex\":10}]");
        GeminiExtensions ext = GeminiExtensions.builder()
            .labels(Map.of("env", "prod", "team", "ml"))
            .promptFeedback(promptFeedback)
            .groundingMetadata(groundingMetadata)
            .citationMetadata(citationMetadata)
            .build();
        assertThat(ext.labels()).containsEntry("env", "prod").containsEntry("team", "ml");
        assertThat(ext.promptFeedback()).isEqualTo(promptFeedback);
        assertThat(ext.groundingMetadata()).isEqualTo(groundingMetadata);
        assertThat(ext.citationMetadata()).isEqualTo(citationMetadata);
    }

    @Test
    void shouldDefaultNewGeminiFieldsToNull() {
        GeminiExtensions ext = GeminiExtensions.builder().build();
        assertThat(ext.labels()).isNull();
        assertThat(ext.promptFeedback()).isNull();
        assertThat(ext.groundingMetadata()).isNull();
        assertThat(ext.citationMetadata()).isNull();
    }
}
