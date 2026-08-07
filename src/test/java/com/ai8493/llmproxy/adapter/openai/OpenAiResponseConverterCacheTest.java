package com.ai8493.llmproxy.adapter.openai;

import com.ai8493.llmproxy.model.UnifiedChatResponse;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionMessage;
import com.openai.models.completions.CompletionUsage;
import com.openai.models.completions.CompletionUsage.PromptTokensDetails;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OpenAiResponseConverter 计费恒等式与 content_filter 透传测试。
 * 计费恒等式: IR.promptTokens + IR.cachedTokens + IR.cacheCreationTokens == 原 promptTokens
 */
class OpenAiResponseConverterCacheTest {

    private final OpenAiResponseConverter converter = new OpenAiResponseConverter();

    @Test
    void shouldDeductCachedTokensFromPromptTokens() {
        var sdkResp = ChatCompletion.builder()
            .id("chatcmpl-1")
            .model("gpt-4")
            .created(1700000000L)
            .choices(List.of())
            .usage(CompletionUsage.builder()
                .promptTokens(100L)
                .completionTokens(50L)
                .totalTokens(150L)
                .promptTokensDetails(PromptTokensDetails.builder()
                    .cachedTokens(30L)
                    .build())
                .build())
            .build();

        UnifiedChatResponse ir = converter.convert(sdkResp);

        // 计费恒等式: 70 + 30 + 0 == 100(原 promptTokens)
        assertThat(ir.usage().promptTokens()).isEqualTo(70);
        assertThat(ir.usage().cachedTokens()).isEqualTo(30);
        assertThat(ir.usage().cacheCreationTokens()).isEqualTo(0);
        assertThat(ir.usage().completionTokens()).isEqualTo(50);
        assertThat(ir.usage().totalTokens()).isEqualTo(150);
    }

    @Test
    void shouldKeepPromptTokensWhenNoCache() {
        var sdkResp = ChatCompletion.builder()
            .id("chatcmpl-2")
            .model("gpt-4")
            .created(1700000000L)
            .choices(List.of())
            .usage(CompletionUsage.builder()
                .promptTokens(100L)
                .completionTokens(50L)
                .totalTokens(150L)
                .build())
            .build();

        UnifiedChatResponse ir = converter.convert(sdkResp);

        assertThat(ir.usage().promptTokens()).isEqualTo(100);
        assertThat(ir.usage().cachedTokens()).isEqualTo(0);
    }

    @Test
    void shouldPassThroughContentFilterFinishReason() {
        var sdkResp = ChatCompletion.builder()
            .id("chatcmpl-3")
            .model("gpt-4")
            .created(1700000000L)
            .choices(List.of(
                ChatCompletion.Choice.builder()
                    .index(0L)
                    .finishReason(ChatCompletion.Choice.FinishReason.CONTENT_FILTER)
                    .logprobs(Optional.empty())
                    .message(ChatCompletionMessage.builder()
                        .content("filtered")
                        .refusal(Optional.empty())
                        .build())
                    .build()))
            .build();

        UnifiedChatResponse ir = converter.convert(sdkResp);

        assertThat(ir.choices().get(0).finishReason()).isEqualTo("content_filter");
    }

    @Test
    void shouldClampPromptTokensToZeroWhenCachedExceedsRaw() {
        // 边界场景:cached(80) > rawPromptTokens(50) → IR.promptTokens clamp 到 0
        var sdkResp = ChatCompletion.builder()
            .id("chatcmpl-clamp")
            .model("gpt-4")
            .created(1700000000L)
            .choices(List.of())
            .usage(CompletionUsage.builder()
                .promptTokens(50L)
                .completionTokens(20L)
                .totalTokens(70L)
                .promptTokensDetails(PromptTokensDetails.builder()
                    .cachedTokens(80L)
                    .build())
                .build())
            .build();

        UnifiedChatResponse ir = converter.convert(sdkResp);

        // Math.max(0, 50-80) = 0,不出现负值
        assertThat(ir.usage().promptTokens()).isEqualTo(0);
        assertThat(ir.usage().cachedTokens()).isEqualTo(80);
        assertThat(ir.usage().cacheCreationTokens()).isEqualTo(0);
    }
}
