package com.ai8493.llmproxy.model;

import com.ai8493.llmproxy.model.extensions.AnthropicExtensions;
import com.ai8493.llmproxy.model.extensions.OpenAiExtensions;
import com.ai8493.llmproxy.model.extensions.GeminiExtensions;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class UnifiedChatRequestTest {

    @Test
    void shouldBuildWithAnthropicExtensions() {
        AnthropicExtensions anthropic = AnthropicExtensions.builder()
            .betaHeaders(List.of("prompt-caching-2024-07-31"))
            .build();
        UnifiedChatRequest req = UnifiedChatRequest.builder()
            .model("claude-3-5-sonnet")
            .anthropic(anthropic)
            .build();
        assertThat(req.anthropic()).isEqualTo(anthropic);
        assertThat(req.anthropic().betaHeaders()).containsExactly("prompt-caching-2024-07-31");
    }

    @Test
    void shouldBuildWithOpenAiExtensions() {
        OpenAiExtensions openai = OpenAiExtensions.builder()
            .logprobs(true)
            .seed(42L)
            .build();
        UnifiedChatRequest req = UnifiedChatRequest.builder()
            .model("gpt-4")
            .openai(openai)
            .build();
        assertThat(req.openai()).isEqualTo(openai);
        assertThat(req.openai().logprobs()).isTrue();
    }

    @Test
    void shouldBuildWithGeminiExtensions() {
        GeminiExtensions gemini = GeminiExtensions.builder()
            .responseMimeType("application/json")
            .build();
        UnifiedChatRequest req = UnifiedChatRequest.builder()
            .model("gemini-pro")
            .gemini(gemini)
            .build();
        assertThat(req.gemini()).isEqualTo(gemini);
        assertThat(req.gemini().responseMimeType()).isEqualTo("application/json");
    }

    @Test
    void shouldDefaultExtensionsToNull() {
        UnifiedChatRequest req = UnifiedChatRequest.builder()
            .model("test")
            .build();
        assertThat(req.anthropic()).isNull();
        assertThat(req.openai()).isNull();
        assertThat(req.gemini()).isNull();
    }

    @Test
    void shouldPreserveExistingFields() {
        UnifiedChatRequest req = UnifiedChatRequest.builder()
            .model("test")
            .stream(true)
            .build();
        assertThat(req.model()).isEqualTo("test");
        assertThat(req.stream()).isTrue();
    }
}
