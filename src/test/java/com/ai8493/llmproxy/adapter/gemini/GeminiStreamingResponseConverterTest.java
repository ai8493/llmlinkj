package com.ai8493.llmproxy.adapter.gemini;

import com.google.genai.types.*;
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
        assertThat(result.choices().get(0).finishReason()).isEqualTo("STOP");
    }

    @Test
    void shouldExtractThoughtDeltaToReasoningContent() {
        var converter = new GeminiStreamingResponseConverter("gemini-pro");
        var chunk = GenerateContentResponse.builder()
            .candidates(List.of(Candidate.builder()
                .index(0)
                .content(Content.builder()
                    .role("model")
                    .parts(List.of(Part.builder().thought(true).text("思考增量").build()))
                    .build())
                .build()))
            .build();

        var result = converter.toUnifiedStreamChunk(chunk);
        var delta = result.choices().get(0).delta();
        assertThat(delta.reasoningContent()).isEqualTo("思考增量");
    }

    @Test
    void shouldExtractFunctionCallDeltaToToolCalls() {
        var converter = new GeminiStreamingResponseConverter("gemini-pro");
        var chunk = GenerateContentResponse.builder()
            .candidates(List.of(Candidate.builder()
                .index(0)
                .content(Content.builder()
                    .role("model")
                    .parts(List.of(Part.builder().functionCall(FunctionCall.builder()
                        .name("get_weather")
                        .args(java.util.Map.of("city", "NYC"))
                        .id("call_123")
                        .build()).build()))
                    .build())
                .build()))
            .build();

        var result = converter.toUnifiedStreamChunk(chunk);
        var delta = result.choices().get(0).delta();
        assertThat(delta.toolCalls()).isNotNull().hasSize(1);
        assertThat(delta.toolCalls().get(0).function().name()).isEqualTo("get_weather");
        assertThat(delta.toolCalls().get(0).id()).isEqualTo("call_123");
    }

    @Test
    void shouldConcatenateMultipleTextParts() {
        var converter = new GeminiStreamingResponseConverter("gemini-pro");
        var chunk = GenerateContentResponse.builder()
            .candidates(List.of(Candidate.builder()
                .index(0)
                .content(Content.builder()
                    .role("model")
                    .parts(List.of(
                        Part.builder().text("Hello ").build(),
                        Part.builder().text("World").build()
                    ))
                    .build())
                .build()))
            .build();

        var result = converter.toUnifiedStreamChunk(chunk);
        var delta = result.choices().get(0).delta();
        assertThat(delta.content()).isEqualTo("Hello World");
    }

    @Test
    void shouldExtractUsageFromStreamChunk() {
        var converter = new GeminiStreamingResponseConverter("gemini-pro");
        var chunk = GenerateContentResponse.builder()
            .candidates(List.of(Candidate.builder()
                .index(0)
                .content(Content.builder()
                    .role("model")
                    .parts(List.of())
                    .build())
                .finishReason(new FinishReason(FinishReason.Known.STOP))
                .build()))
            .usageMetadata(GenerateContentResponseUsageMetadata.builder()
                .promptTokenCount(10)
                .candidatesTokenCount(5)
                .totalTokenCount(15)
                .thoughtsTokenCount(3)
                .build())
            .build();

        var result = converter.toUnifiedStreamChunk(chunk);
        assertThat(result.usage()).isNotNull();
        assertThat(result.usage().promptTokens()).isEqualTo(10);
        assertThat(result.usage().completionTokens()).isEqualTo(5);
        assertThat(result.usage().totalTokens()).isEqualTo(15);
        assertThat(result.usage().reasoningTokens()).isEqualTo(3);
    }

    @Test
    void shouldStoreFinishReasonOriginalValueInStream() {
        var converter = new GeminiStreamingResponseConverter("gemini-pro");
        var chunk = GenerateContentResponse.builder()
            .candidates(List.of(Candidate.builder()
                .index(0)
                .content(Content.builder()
                    .role("model")
                    .parts(List.of())
                    .build())
                .finishReason(new FinishReason(FinishReason.Known.SAFETY))
                .build()))
            .build();

        var result = converter.toUnifiedStreamChunk(chunk);
        assertThat(result.choices().get(0).finishReason()).isEqualTo("SAFETY");
    }
}
