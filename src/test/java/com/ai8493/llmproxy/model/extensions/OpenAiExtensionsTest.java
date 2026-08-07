package com.ai8493.llmproxy.model.extensions;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

class OpenAiExtensionsTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void shouldBuildWithAllFields() throws Exception {
        JsonNode responseFormat = mapper.readTree("{\"type\":\"json_object\"}");
        OpenAiExtensions ext = OpenAiExtensions.builder()
            .logprobs(true)
            .topLogprobs(5)
            .n(3)
            .seed(42L)
            .responseFormat(responseFormat)
            .build();
        assertThat(ext.logprobs()).isTrue();
        assertThat(ext.topLogprobs()).isEqualTo(5);
        assertThat(ext.n()).isEqualTo(3);
        assertThat(ext.seed()).isEqualTo(42L);
        assertThat(ext.responseFormat()).isNotNull();
    }

    @Test
    void shouldBuildWithDefaults() {
        OpenAiExtensions ext = OpenAiExtensions.builder().build();
        assertThat(ext.logprobs()).isNull();
        assertThat(ext.topLogprobs()).isNull();
        assertThat(ext.n()).isNull();
        assertThat(ext.seed()).isNull();
        assertThat(ext.responseFormat()).isNull();
    }

    @Test
    void shouldBuildWithNewOpenAiFields() throws Exception {
        JsonNode logitBias = mapper.readTree("{\"123\":-5}");
        JsonNode metadata = mapper.readTree("{\"user_id\":\"u1\"}");
        JsonNode audio = mapper.readTree("{\"voice\":\"alloy\",\"format\":\"wav\"}");
        JsonNode prediction = mapper.readTree("{\"type\":\"content\",\"content\":\"prefix\"}");
        JsonNode webSearchOptions = mapper.readTree("{\"search_context_size\":\"medium\"}");
        OpenAiExtensions ext = OpenAiExtensions.builder()
            .logitBias(logitBias)
            .metadata(metadata)
            .store(true)
            .audio(audio)
            .modalities(List.of("text", "audio"))
            .prediction(prediction)
            .webSearchOptions(webSearchOptions)
            .build();
        assertThat(ext.logitBias()).isEqualTo(logitBias);
        assertThat(ext.metadata()).isEqualTo(metadata);
        assertThat(ext.store()).isTrue();
        assertThat(ext.audio()).isEqualTo(audio);
        assertThat(ext.modalities()).containsExactly("text", "audio");
        assertThat(ext.prediction()).isEqualTo(prediction);
        assertThat(ext.webSearchOptions()).isEqualTo(webSearchOptions);
    }

    @Test
    void shouldDefaultNewOpenAiFieldsToNull() {
        OpenAiExtensions ext = OpenAiExtensions.builder().build();
        assertThat(ext.logitBias()).isNull();
        assertThat(ext.metadata()).isNull();
        assertThat(ext.store()).isNull();
        assertThat(ext.audio()).isNull();
        assertThat(ext.modalities()).isNull();
        assertThat(ext.prediction()).isNull();
        assertThat(ext.webSearchOptions()).isNull();
    }

    @Test
    void shouldBuildWithPreviousResponseIdAndInclude() {
        OpenAiExtensions ext = OpenAiExtensions.builder()
            .previousResponseId("resp_abc123")
            .include(List.of("file_search_call.results", "message.output_text.logprobs"))
            .build();

        assertThat(ext.previousResponseId()).isEqualTo("resp_abc123");
        assertThat(ext.include()).containsExactly(
            "file_search_call.results", "message.output_text.logprobs");
    }

    @Test
    void shouldBuildWithNullDefaults() {
        OpenAiExtensions ext = OpenAiExtensions.builder().build();

        assertThat(ext.previousResponseId()).isNull();
        assertThat(ext.include()).isNull();
    }
}
