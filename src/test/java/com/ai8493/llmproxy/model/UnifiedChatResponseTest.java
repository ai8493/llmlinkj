package com.ai8493.llmproxy.model;

import com.ai8493.llmproxy.model.extensions.AnthropicExtensions;
import com.ai8493.llmproxy.model.extensions.OpenAiExtensions;
import com.ai8493.llmproxy.model.extensions.GeminiExtensions;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class UnifiedChatResponseTest {

    @Test
    void shouldBuildWithAnthropicExtensions() {
        AnthropicExtensions anthropic = AnthropicExtensions.builder()
            .matchedStopSequence("<EOS>")
            .build();
        UnifiedChatResponse resp = UnifiedChatResponse.builder()
            .id("msg_123")
            .anthropic(anthropic)
            .build();
        assertThat(resp.anthropic()).isEqualTo(anthropic);
        assertThat(resp.anthropic().matchedStopSequence()).isEqualTo("<EOS>");
    }

    @Test
    void shouldDefaultExtensionsToNull() {
        UnifiedChatResponse resp = UnifiedChatResponse.builder()
            .id("msg_123")
            .build();
        assertThat(resp.anthropic()).isNull();
        assertThat(resp.openai()).isNull();
        assertThat(resp.gemini()).isNull();
    }

    @Test
    void shouldPreserveExistingFields() {
        UnifiedChatResponse resp = UnifiedChatResponse.builder()
            .id("msg_123")
            .model("claude-3-5-sonnet")
            .object("message")
            .created(1234567890L)
            .build();
        assertThat(resp.id()).isEqualTo("msg_123");
        assertThat(resp.model()).isEqualTo("claude-3-5-sonnet");
        assertThat(resp.created()).isEqualTo(1234567890L);
    }
}
