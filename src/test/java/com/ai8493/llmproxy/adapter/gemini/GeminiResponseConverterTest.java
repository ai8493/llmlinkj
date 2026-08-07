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
        assertThat(result.choices().get(0).finishReason()).isEqualTo("STOP");
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
        assertThat(result.choices().get(0).finishReason()).isEqualTo("MAX_TOKENS");
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
        assertThat(result.choices().get(0).finishReason()).isEqualTo("SAFETY");
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
        assertThat(result.choices().get(0).finishReason()).isEqualTo("RECITATION");
    }

    @Test
    void shouldExtractThoughtPartToReasoningContent() {
        var resp = GenerateContentResponse.builder()
            .candidates(List.of(Candidate.builder()
                .index(0)
                .content(Content.builder()
                    .role("model")
                    .parts(List.of(
                        Part.builder().thought(true).text("思考中").build(),
                        Part.builder().text("你好").build()
                    ))
                    .build())
                .finishReason(new FinishReason(FinishReason.Known.STOP))
                .build()))
            .usageMetadata(GenerateContentResponseUsageMetadata.builder()
                .promptTokenCount(10)
                .candidatesTokenCount(5)
                .totalTokenCount(15)
                .build())
            .build();

        var result = converter.toUnifiedResponse(resp);
        var msg = result.choices().get(0).message();
        assertThat(msg.reasoningContent()).isEqualTo("思考中");
        assertThat(msg.content()).isEqualTo("你好");
    }

    @Test
    void shouldExtractInlineDataPartToImagePart() {
        byte[] imgBytes = java.util.Base64.getDecoder().decode("iVBORw0KGgo=");
        var resp = GenerateContentResponse.builder()
            .candidates(List.of(Candidate.builder()
                .index(0)
                .content(Content.builder()
                    .role("model")
                    .parts(List.of(
                        Part.builder().text("图片如下").build(),
                        Part.builder().inlineData(Blob.builder()
                            .mimeType("image/png")
                            .data(imgBytes)
                            .build()).build()
                    ))
                    .build())
                .finishReason(new FinishReason(FinishReason.Known.STOP))
                .build()))
            .build();

        var result = converter.toUnifiedResponse(resp);
        var msg = result.choices().get(0).message();
        assertThat(msg.content()).isEqualTo("图片如下");
        assertThat(msg.parts()).isNotNull().hasSize(1);
        assertThat(msg.parts().get(0)).isInstanceOf(UnifiedPart.ImagePart.class);
        var img = (UnifiedPart.ImagePart) msg.parts().get(0);
        assertThat(img.imageData().path("url").asText("")).startsWith("data:image/png;base64,");
    }

    @Test
    void shouldExtractSafetyRatingsToGeminiExtensions() {
        var resp = GenerateContentResponse.builder()
            .candidates(List.of(Candidate.builder()
                .index(0)
                .content(Content.builder()
                    .role("model")
                    .parts(List.of(Part.builder().text("hi").build()))
                    .build())
                .safetyRatings(List.of(
                    SafetyRating.builder()
                        .category("HARM_CATEGORY_HARASSMENT")
                        .build()
                ))
                .finishReason(new FinishReason(FinishReason.Known.STOP))
                .build()))
            .promptFeedback(GenerateContentResponsePromptFeedback.builder()
                .blockReason("OTHER")
                .build())
            .build();

        var result = converter.toUnifiedResponse(resp);
        assertThat(result.gemini()).isNotNull();
        assertThat(result.gemini().safetySettings()).isNotNull();
        assertThat(result.gemini().safetySettings().isArray()).isTrue();
        assertThat(result.gemini().promptFeedback()).isNotNull();
        assertThat(result.gemini().promptFeedback().has("blockReason")).isTrue();
    }

    @Test
    void shouldExtractThoughtsTokenCountToReasoningTokens() {
        var resp = GenerateContentResponse.builder()
            .candidates(List.of(Candidate.builder()
                .index(0)
                .content(Content.builder()
                    .role("model")
                    .parts(List.of(Part.builder().text("hi").build()))
                    .build())
                .finishReason(new FinishReason(FinishReason.Known.STOP))
                .build()))
            .usageMetadata(GenerateContentResponseUsageMetadata.builder()
                .promptTokenCount(10)
                .candidatesTokenCount(5)
                .totalTokenCount(20)
                .thoughtsTokenCount(5)
                .build())
            .build();

        var result = converter.toUnifiedResponse(resp);
        assertThat(result.usage().reasoningTokens()).isEqualTo(5);
    }

    @Test
    void shouldStoreFinishReasonOriginalValue() {
        var resp = GenerateContentResponse.builder()
            .candidates(List.of(Candidate.builder()
                .index(0)
                .content(Content.builder()
                    .role("model")
                    .parts(List.of(Part.builder().text("hi").build()))
                    .build())
                .finishReason(new FinishReason(FinishReason.Known.MAX_TOKENS))
                .build()))
            .build();

        var result = converter.toUnifiedResponse(resp);
        // 存原值 "MAX_TOKENS",而非归一化 "length"
        assertThat(result.choices().get(0).finishReason()).isEqualTo("MAX_TOKENS");
    }
}
