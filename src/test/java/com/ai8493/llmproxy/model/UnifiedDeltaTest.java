package com.ai8493.llmproxy.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class UnifiedDeltaTest {

    @Test
    void shouldBuildWithToolCallArgumentDeltas() {
        UnifiedDelta delta = UnifiedDelta.builder()
            .toolCallArgumentDeltas(List.of(new IndexedArgumentDelta(0, "{\"location\":\"Paris")))
            .build();
        assertThat(delta.toolCallArgumentDeltas().get(0).partialJson()).isEqualTo("{\"location\":\"Paris");
    }

    @Test
    void shouldPreserveExistingFields() {
        UnifiedDelta delta = UnifiedDelta.builder()
            .role("assistant")
            .content("response")
            .reasoningContent("thinking")
            .build();
        assertThat(delta.role()).isEqualTo("assistant");
        assertThat(delta.reasoningContent()).isEqualTo("thinking");
    }

    @Test
    void shouldHoldToolCallArgumentDeltas() {
        UnifiedDelta delta = UnifiedDelta.builder()
            .toolCallArgumentDeltas(List.of(
                new IndexedArgumentDelta(0, "{\"city\":"),
                new IndexedArgumentDelta(1, "{\"tz\":")
            ))
            .build();
        assertThat(delta.toolCallArgumentDeltas()).hasSize(2);
        assertThat(delta.toolCallArgumentDeltas().get(0).index()).isEqualTo(0);
        assertThat(delta.toolCallArgumentDeltas().get(0).partialJson()).isEqualTo("{\"city\":");
        assertThat(delta.toolCallArgumentDeltas().get(1).index()).isEqualTo(1);
    }

    @Test
    void shouldDefaultToolCallArgumentDeltasToNull() {
        UnifiedDelta delta = UnifiedDelta.builder().content("hi").build();
        assertThat(delta.toolCallArgumentDeltas()).isNull();
    }

    @Test
    void shouldBuildWithThinkingSignatureAndLogprobs() throws Exception {
        JsonNode logprobs = new ObjectMapper().readTree("{\"content\":[{\"token\":\"hi\"}]}");
        UnifiedDelta delta = UnifiedDelta.builder()
            .thinkingSignature("sig-abc")
            .logprobs(logprobs)
            .build();
        assertThat(delta.thinkingSignature()).isEqualTo("sig-abc");
        assertThat(delta.logprobs()).isEqualTo(logprobs);
    }

    @Test
    void shouldDefaultNewFieldsToNull() {
        UnifiedDelta delta = UnifiedDelta.builder().content("hi").build();
        assertThat(delta.thinkingSignature()).isNull();
        assertThat(delta.logprobs()).isNull();
    }
}
