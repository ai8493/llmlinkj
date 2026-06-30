package com.ai8493.llmproxy.adapter.openai;

import com.openai.core.JsonValue;
import com.openai.models.chat.completions.*;
import com.openai.models.completions.CompletionUsage;
import com.ai8493.llmproxy.model.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class OpenAiResponseConverterTest {

    private final OpenAiResponseConverter converter = new OpenAiResponseConverter();

    @Test
    void shouldConvertPlainTextResponse() {
        var sdkResp = ChatCompletion.builder()
            .id("chatcmpl-abc")
            .model("gpt-4o")
            .created(1715000000L)
            .choices(List.of(
                ChatCompletion.Choice.builder()
                    .index(0L)
                    .finishReason(ChatCompletion.Choice.FinishReason.STOP)
                    .logprobs(Optional.empty())
                    .message(ChatCompletionMessage.builder()
                        .content("你好！")
                        .refusal(Optional.empty())
                        .build())
                    .build()
            ))
            .usage(CompletionUsage.builder()
                .promptTokens(10L)
                .completionTokens(5L)
                .totalTokens(15L)
                .build())
            .build();

        UnifiedChatResponse resp = converter.convert(sdkResp);

        assertThat(resp.id()).isEqualTo("chatcmpl-abc");
        assertThat(resp.model()).isEqualTo("gpt-4o");
        assertThat(resp.object()).isEqualTo("chat.completion");
        assertThat(resp.created()).isEqualTo(1715000000L);
        assertThat(resp.choices()).hasSize(1);
        UnifiedChoice choice = resp.choices().get(0);
        assertThat(choice.index()).isEqualTo(0);
        assertThat(choice.finishReason()).isEqualTo("stop");
        assertThat(choice.message().content()).isEqualTo("你好！");
        assertThat(choice.message().role()).isEqualTo(UnifiedMessage.Role.ASSISTANT);
        assertThat(resp.usage().promptTokens()).isEqualTo(10);
        assertThat(resp.usage().completionTokens()).isEqualTo(5);
        assertThat(resp.usage().totalTokens()).isEqualTo(15);
    }

    @Test
    void shouldConvertToolCallResponse() {
        var sdkResp = ChatCompletion.builder()
            .id("chatcmpl-def")
            .model("gpt-4o")
            .created(1715000000L)
            .choices(List.of(
                ChatCompletion.Choice.builder()
                    .index(0L)
                    .finishReason(ChatCompletion.Choice.FinishReason.TOOL_CALLS)
                    .logprobs(Optional.empty())
                    .message(ChatCompletionMessage.builder()
                        .content(Optional.empty())
                        .refusal(Optional.empty())
                        .toolCalls(List.of(
                            ChatCompletionMessageToolCall.ofFunction(
                                ChatCompletionMessageFunctionToolCall.builder()
                                    .id("call_xyz")
                                    .function(ChatCompletionMessageFunctionToolCall.Function.builder()
                                        .name("get_weather")
                                        .arguments("{\"city\":\"北京\"}")
                                        .build())
                                    .build()
                            )
                        ))
                        .build())
                    .build()
            ))
            .build();

        UnifiedChatResponse resp = converter.convert(sdkResp);

        var choice = resp.choices().get(0);
        assertThat(choice.finishReason()).isEqualTo("tool_calls");
        assertThat(choice.message().toolCalls()).hasSize(1);
        UnifiedToolCall tc = choice.message().toolCalls().get(0);
        assertThat(tc.id()).isEqualTo("call_xyz");
        assertThat(tc.function().name()).isEqualTo("get_weather");
    }

    @Test
    void shouldExtractReasoningContentFromAdditionalProperties() {
        // 通过 SDK 的 _additionalProperties() 携带 reasoning_content
        var sdkResp = ChatCompletion.builder()
            .id("chatcmpl-reasoning-1")
            .model("deepseek-v4-flash")
            .created(1715000000L)
            .choices(List.of(
                ChatCompletion.Choice.builder()
                    .index(0L)
                    .finishReason(ChatCompletion.Choice.FinishReason.STOP)
                    .logprobs(Optional.empty())
                    .message(ChatCompletionMessage.builder()
                        .content("最终答案")
                        .refusal(Optional.empty())
                        .putAdditionalProperty("reasoning_content", JsonValue.from("我先分析一下问题..."))
                        .build())
                    .build()
            ))
            .usage(CompletionUsage.builder()
                .promptTokens(10L)
                .completionTokens(5L)
                .totalTokens(15L)
                .build())
            .build();

        UnifiedChatResponse resp = converter.convert(sdkResp);

        assertThat(resp.choices()).hasSize(1);
        UnifiedMessage msg = resp.choices().get(0).message();
        assertThat(msg.reasoningContent()).isEqualTo("我先分析一下问题...");
        assertThat(msg.content()).isEqualTo("最终答案");
    }

    @Test
    void shouldHandleMissingReasoningContent() {
        // 不设置 _additionalProperties 时，reasoningContent 应为 null
        var sdkResp = ChatCompletion.builder()
            .id("chatcmpl-null-rc")
            .model("gpt-4o")
            .created(1715000000L)
            .choices(List.of(
                ChatCompletion.Choice.builder()
                    .index(0L)
                    .finishReason(ChatCompletion.Choice.FinishReason.STOP)
                    .logprobs(Optional.empty())
                    .message(ChatCompletionMessage.builder()
                        .content("无推理内容")
                        .refusal(Optional.empty())
                        .build())
                    .build()
            ))
            .build();

        UnifiedChatResponse resp = converter.convert(sdkResp);

        assertThat(resp.choices().get(0).message().reasoningContent()).isNull();
    }

    @Test
    void shouldExtractReasoningContentFromMultipleChoices() {
        var sdkResp = ChatCompletion.builder()
            .id("chatcmpl-multi")
            .model("deepseek-v4-flash")
            .created(1715000000L)
            .choices(List.of(
                ChatCompletion.Choice.builder()
                    .index(0L)
                    .finishReason(ChatCompletion.Choice.FinishReason.STOP)
                    .logprobs(Optional.empty())
                    .message(ChatCompletionMessage.builder()
                        .content("答案A")
                        .refusal(Optional.empty())
                        .putAdditionalProperty("reasoning_content", JsonValue.from("推理A"))
                        .build())
                    .build(),
                ChatCompletion.Choice.builder()
                    .index(1L)
                    .finishReason(ChatCompletion.Choice.FinishReason.STOP)
                    .logprobs(Optional.empty())
                    .message(ChatCompletionMessage.builder()
                        .content("答案B")
                        .refusal(Optional.empty())
                        .putAdditionalProperty("reasoning_content", JsonValue.from("推理B"))
                        .build())
                    .build()
            ))
            .build();

        UnifiedChatResponse resp = converter.convert(sdkResp);

        assertThat(resp.choices()).hasSize(2);
        assertThat(resp.choices().get(0).message().reasoningContent()).isEqualTo("推理A");
        assertThat(resp.choices().get(1).message().reasoningContent()).isEqualTo("推理B");
    }
}
