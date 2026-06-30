package com.ai8493.llmproxy.adapter.gemini;

import com.google.genai.types.*;
import com.ai8493.llmproxy.model.*;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GeminiProtocolAdapterThoughtTest {

    private final GeminiProtocolAdapter adapter = new GeminiProtocolAdapter();

    @Test
    void shouldExtractThoughtPartAsReasoningContent() {
        var thoughtPart = Part.builder().thought(true).text("这是模型的思考过程").build();
        var textPart = Part.builder().text("这是回复文本").build();
        var content = Content.builder()
            .role("model")
            .parts(List.of(thoughtPart, textPart))
            .build();

        var result = adapter.toUnifiedRequest(
            GenerateContentParameters.builder()
                .model("deepseek-v4-flash")
                .contents(List.of(content))
                .build(),
            null, null, null);

        var messages = result.messages();
        assertThat(messages).hasSize(1);
        var msg = messages.get(0);
        assertThat(msg.role()).isEqualTo(UnifiedMessage.Role.ASSISTANT);
        assertThat(msg.reasoningContent()).isEqualTo("这是模型的思考过程");
        assertThat(msg.content()).isEqualTo("这是回复文本");
    }
}
