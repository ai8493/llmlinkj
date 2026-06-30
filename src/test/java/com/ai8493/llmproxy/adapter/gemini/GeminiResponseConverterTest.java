package com.ai8493.llmproxy.adapter.gemini;

import com.google.genai.types.*;
import com.ai8493.llmproxy.converter.FunctionCallMapper;
import com.ai8493.llmproxy.model.*;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GeminiResponseConverterTest {

    private final FunctionCallMapper functionCallMapper = new FunctionCallMapper();
    private final GeminiResponseConverter converter = new GeminiResponseConverter(functionCallMapper);

    @Test
    void shouldExtractTextFromCandidates() {
        var resp = GenerateContentResponse.builder()
            .modelVersion("gemini-pro")
            .candidates(List.of(Candidate.builder()
                .index(0)
                .content(Content.builder()
                    .role("model")
                    .parts(List.of(Part.builder().text("Hello world").build()))
                    .build())
                .finishReason(new FinishReason(FinishReason.Known.STOP))
                .build()))
            .build();

        var result = converter.toUnifiedResponse(resp);

        assertThat(result.choices()).hasSize(1);
        assertThat(result.choices().get(0).message().content()).isEqualTo("Hello world");
        assertThat(result.choices().get(0).finishReason()).isEqualTo("stop");
    }

    @Test
    void shouldMapMaxTokensFinishReason() {
        var resp = GenerateContentResponse.builder()
            .modelVersion("gemini-pro")
            .candidates(List.of(Candidate.builder()
                .index(0)
                .content(Content.builder()
                    .role("model")
                    .parts(List.of(Part.builder().text("text").build()))
                    .build())
                .finishReason(new FinishReason(FinishReason.Known.MAX_TOKENS))
                .build()))
            .build();

        var result = converter.toUnifiedResponse(resp);

        assertThat(result.choices()).hasSize(1);
        assertThat(result.choices().get(0).finishReason()).isEqualTo("length");
    }

    @Test
    void shouldHandleEmptyCandidatesAsContentFilter() {
        var resp = GenerateContentResponse.builder()
            .modelVersion("gemini-pro")
            .candidates(List.of())
            .build();

        var result = converter.toUnifiedResponse(resp);

        assertThat(result.choices()).hasSize(1);
        assertThat(result.choices().get(0).finishReason()).isEqualTo("content_filter");
        assertThat(result.choices().get(0).message().content()).isEqualTo("");
    }

    @Test
    void shouldMapSafetyToContentFilter() {
        var resp = GenerateContentResponse.builder()
            .modelVersion("gemini-pro")
            .candidates(List.of(Candidate.builder()
                .index(0)
                .content(Content.builder()
                    .role("model")
                    .parts(List.of(Part.builder().text("harmful").build()))
                    .build())
                .finishReason(new FinishReason(FinishReason.Known.SAFETY))
                .build()))
            .build();

        var result = converter.toUnifiedResponse(resp);

        assertThat(result.choices()).hasSize(1);
        assertThat(result.choices().get(0).finishReason()).isEqualTo("content_filter");
    }

    @Test
    void shouldMapRecitationToContentFilter() {
        var resp = GenerateContentResponse.builder()
            .modelVersion("gemini-pro")
            .candidates(List.of(Candidate.builder()
                .index(0)
                .content(Content.builder()
                    .role("model")
                    .parts(List.of(Part.builder().text("cited").build()))
                    .build())
                .finishReason(new FinishReason(FinishReason.Known.RECITATION))
                .build()))
            .build();

        var result = converter.toUnifiedResponse(resp);

        assertThat(result.choices()).hasSize(1);
        assertThat(result.choices().get(0).finishReason()).isEqualTo("content_filter");
    }
}
