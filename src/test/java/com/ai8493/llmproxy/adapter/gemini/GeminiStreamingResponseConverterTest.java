package com.ai8493.llmproxy.adapter.gemini;

import com.google.genai.types.*;
import com.ai8493.llmproxy.model.*;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GeminiStreamingResponseConverterTest {

    @Test
    void shouldMapStreamChunkDelta() {
        var converter = new GeminiStreamingResponseConverter("gemini-pro");
        var chunk = GenerateContentResponse.builder()
            .candidates(List.of(Candidate.builder()
                .index(0)
                .content(Content.builder()
                    .role("model")
                    .parts(List.of(Part.builder().text("Hello").build()))
                    .build())
                .build()))
            .build();

        var result = converter.toUnifiedStreamChunk(chunk);

        assertThat(result.object()).isEqualTo("chat.completion.chunk");
        assertThat(result.choices()).hasSize(1);
        var choice = result.choices().get(0);
        assertThat(choice.delta()).isNotNull();
        assertThat(choice.delta().content()).isEqualTo("Hello");
        assertThat(choice.finishReason()).isNull();
    }

    @Test
    void shouldPreserveModelName() {
        var converter = new GeminiStreamingResponseConverter("gemini-2.0-flash-exp");
        var chunk = GenerateContentResponse.builder()
            .candidates(List.of(Candidate.builder()
                .index(0)
                .content(Content.builder()
                    .role("model")
                    .parts(List.of(Part.builder().text("Hi").build()))
                    .build())
                .build()))
            .build();

        var result = converter.toUnifiedStreamChunk(chunk);

        assertThat(result.model()).isEqualTo("gemini-2.0-flash-exp");
    }

    @Test
    void shouldMapStopFinishReason() {
        var converter = new GeminiStreamingResponseConverter("gemini-pro");
        var chunk = GenerateContentResponse.builder()
            .candidates(List.of(Candidate.builder()
                .index(0)
                .content(Content.builder()
                    .role("model")
                    .parts(List.of(Part.builder().text("done").build()))
                    .build())
                .finishReason(new FinishReason(FinishReason.Known.STOP))
                .build()))
            .build();

        var result = converter.toUnifiedStreamChunk(chunk);

        assertThat(result.choices()).hasSize(1);
        assertThat(result.choices().get(0).finishReason()).isEqualTo("stop");
    }
}
