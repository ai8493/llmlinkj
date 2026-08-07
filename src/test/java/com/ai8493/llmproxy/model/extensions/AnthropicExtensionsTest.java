package com.ai8493.llmproxy.model.extensions;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class AnthropicExtensionsTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void shouldBuildWithAllFields() throws Exception {
        JsonNode systemArray = mapper.readTree("[{\"type\":\"text\",\"text\":\"hello\"}]");
        AnthropicExtensions ext = AnthropicExtensions.builder()
            .betaHeaders(List.of("prompt-caching-2024-07-31"))
            .cacheBreakdown(Map.of("5m", 100, "1h", 50))
            .matchedStopSequence("<EOS>")
            .rawSystemArray(systemArray)
            .build();
        assertThat(ext.betaHeaders()).containsExactly("prompt-caching-2024-07-31");
        assertThat(ext.cacheBreakdown()).containsEntry("5m", 100);
        assertThat(ext.matchedStopSequence()).isEqualTo("<EOS>");
        assertThat(ext.rawSystemArray()).isNotNull();
    }

    @Test
    void shouldBuildWithDefaults() {
        AnthropicExtensions ext = AnthropicExtensions.builder().build();
        assertThat(ext.betaHeaders()).isNull();
        assertThat(ext.cacheBreakdown()).isNull();
        assertThat(ext.matchedStopSequence()).isNull();
        assertThat(ext.rawSystemArray()).isNull();
    }

    @Test
    void shouldBuildWithNewAnthropicFields() throws Exception {
        JsonNode cacheControl = mapper.readTree("{\"0\":{\"type\":\"ephemeral\"}}");
        JsonNode citations = mapper.readTree("[{\"type\":\"citation\",\"text\":\"ref\"}]");
        AnthropicExtensions ext = AnthropicExtensions.builder()
            .cacheControlByBlock(cacheControl)
            .disableParallelToolUse(true)
            .metadataUserId("user-123")
            .citations(citations)
            .build();
        assertThat(ext.cacheControlByBlock()).isEqualTo(cacheControl);
        assertThat(ext.disableParallelToolUse()).isTrue();
        assertThat(ext.metadataUserId()).isEqualTo("user-123");
        assertThat(ext.citations()).isEqualTo(citations);
    }

    @Test
    void shouldDefaultNewAnthropicFieldsToNull() {
        AnthropicExtensions ext = AnthropicExtensions.builder().build();
        assertThat(ext.cacheControlByBlock()).isNull();
        assertThat(ext.disableParallelToolUse()).isNull();
        assertThat(ext.metadataUserId()).isNull();
        assertThat(ext.citations()).isNull();
    }
}
