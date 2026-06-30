package com.ai8493.llmproxy.adapter.openai;

import com.openai.core.JsonValue;
import com.openai.models.chat.completions.*;
import com.ai8493.llmproxy.model.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class OpenAiStreamingResponseConverterTest {

    private final OpenAiStreamingResponseConverter converter =
        new OpenAiStreamingResponseConverter("gpt-4o");

    @Test
    void shouldConvertContentChunk() {
        var chunk = ChatCompletionChunk.builder()
            .id("chatcmpl-abc")
            .model("gpt-4o")
            .created(1715000000L)
            .choices(List.of(
                ChatCompletionChunk.Choice.builder()
                    .index(0L)
                    .finishReason(Optional.empty())
                    .delta(ChatCompletionChunk.Choice.Delta.builder()
                        .content("你好")
                        .build())
                    .build()
            ))
            .build();

        UnifiedChatResponse resp = converter.convertChunk(chunk);

        assertThat(resp.object()).isEqualTo("chat.completion.chunk");
        assertThat(resp.choices().get(0).delta().content()).isEqualTo("你好");
    }

    @Test
    void shouldConvertFinishReasonChunk() {
        var chunk = ChatCompletionChunk.builder()
            .id("chatcmpl-abc")
            .model("gpt-4o")
            .created(1715000000L)
            .choices(List.of(
                ChatCompletionChunk.Choice.builder()
                    .index(0L)
                    .finishReason(ChatCompletionChunk.Choice.FinishReason.STOP)
                    .delta(ChatCompletionChunk.Choice.Delta.builder().build())
                    .build()
            ))
            .build();

        UnifiedChatResponse resp = converter.convertChunk(chunk);

        assertThat(resp.choices().get(0).finishReason()).isEqualTo("stop");
    }

    @Test
    void shouldAccumulateStreamingToolCalls() {
        // 第一个 chunk: tool call 开始（id + function name）
        var chunk1 = ChatCompletionChunk.builder()
            .id("chatcmpl-abc")
            .model("gpt-4o")
            .created(1715000000L)
            .choices(List.of(
                ChatCompletionChunk.Choice.builder()
                    .index(0L)
                    .finishReason(Optional.empty())
                    .delta(ChatCompletionChunk.Choice.Delta.builder()
                        .toolCalls(List.of(
                            ChatCompletionChunk.Choice.Delta.ToolCall.builder()
                                .index(0L)
                                .id("call_xyz")
                                .function(ChatCompletionChunk.Choice.Delta.ToolCall.Function.builder()
                                    .name("get_weather")
                                    .build())
                                .build()
                        ))
                        .build())
                    .build()
            ))
            .build();

        // 第二个 chunk: tool call arguments 继续
        var chunk2 = ChatCompletionChunk.builder()
            .id("chatcmpl-abc")
            .model("gpt-4o")
            .created(1715000000L)
            .choices(List.of(
                ChatCompletionChunk.Choice.builder()
                    .index(0L)
                    .finishReason(Optional.empty())
                    .delta(ChatCompletionChunk.Choice.Delta.builder()
                        .toolCalls(List.of(
                            ChatCompletionChunk.Choice.Delta.ToolCall.builder()
                                .index(0L)
                                .function(ChatCompletionChunk.Choice.Delta.ToolCall.Function.builder()
                                    .arguments("{\"city\":\"北京\"}")
                                    .build())
                                .build()
                        ))
                        .build())
                    .build()
            ))
            .build();

        // 第三个 chunk: finish_reason 触发工具调用组装
        var chunk3 = ChatCompletionChunk.builder()
            .id("chatcmpl-abc")
            .model("gpt-4o")
            .created(1715000000L)
            .choices(List.of(
                ChatCompletionChunk.Choice.builder()
                    .index(0L)
                    .finishReason(ChatCompletionChunk.Choice.FinishReason.TOOL_CALLS)
                    .delta(ChatCompletionChunk.Choice.Delta.builder().build())
                    .build()
            ))
            .build();

        // 前两个 chunk 不产生 tool_calls 输出（仅累积）
        converter.convertChunk(chunk1);
        converter.convertChunk(chunk2);
        // 第三个 chunk 的 finish_reason 触发组装
        UnifiedChatResponse resp3 = converter.convertChunk(chunk3);

        assertThat(resp3.choices().get(0).finishReason()).isEqualTo("tool_calls");
        assertThat(resp3.choices().get(0).delta().toolCalls()).hasSize(1);
        UnifiedToolCall tc = resp3.choices().get(0).delta().toolCalls().get(0);
        assertThat(tc.id()).isEqualTo("call_xyz");
        assertThat(tc.function().name()).isEqualTo("get_weather");
        assertThat(tc.function().arguments().toString()).contains("\"city\"");
    }

    @Test
    void shouldExtractReasoningContentFromAdditionalProperties() {
        var chunk = ChatCompletionChunk.builder()
            .id("chatcmpl-rc-1")
            .model("deepseek-v4-flash")
            .created(1715000000L)
            .choices(List.of(
                ChatCompletionChunk.Choice.builder()
                    .index(0L)
                    .finishReason(Optional.empty())
                    .delta(ChatCompletionChunk.Choice.Delta.builder()
                        .content(Optional.of("最终答案"))
                        .putAdditionalProperty("reasoning_content", JsonValue.from("我在思考中..."))
                        .build())
                    .build()
            ))
            .build();

        UnifiedChatResponse resp = converter.convertChunk(chunk);

        assertThat(resp.choices()).hasSize(1);
        UnifiedDelta delta = resp.choices().get(0).delta();
        assertThat(delta.reasoningContent()).isEqualTo("我在思考中...");
        assertThat(delta.content()).isEqualTo("最终答案");
    }

    @Test
    void shouldHandleMissingReasoningContent() {
        // 不设置 _additionalProperties 时，reasoningContent 应为 null
        var chunk = ChatCompletionChunk.builder()
            .id("chatcmpl-rc-null")
            .model("deepseek-v4-flash")
            .created(1715000000L)
            .choices(List.of(
                ChatCompletionChunk.Choice.builder()
                    .index(0L)
                    .finishReason(Optional.empty())
                    .delta(ChatCompletionChunk.Choice.Delta.builder()
                        .content(Optional.of("普通内容"))
                        .build())
                    .build()
            ))
            .build();

        UnifiedChatResponse resp = converter.convertChunk(chunk);

        assertThat(resp.choices().get(0).delta().reasoningContent()).isNull();
    }

    @Test
    void shouldExtractReasoningContentOnly() {
        // 仅 reasoning chunk（无 content）
        var chunk = ChatCompletionChunk.builder()
            .id("chatcmpl-rc-only")
            .model("deepseek-v4-flash")
            .created(1715000000L)
            .choices(List.of(
                ChatCompletionChunk.Choice.builder()
                    .index(0L)
                    .finishReason(Optional.empty())
                    .delta(ChatCompletionChunk.Choice.Delta.builder()
                        .content(Optional.empty())
                        .putAdditionalProperty("reasoning_content", JsonValue.from("纯推理内容"))
                        .build())
                    .build()
            ))
            .build();

        UnifiedChatResponse resp = converter.convertChunk(chunk);

        assertThat(resp.choices().get(0).delta().reasoningContent()).isEqualTo("纯推理内容");
        assertThat(resp.choices().get(0).delta().content()).isNull();
    }

    @Test
    void shouldNotAffectExistingBehaviorWhenNoAdditionalProperties() {
        // 不设置 _additionalProperties 时，原有行为不变
        var chunk = ChatCompletionChunk.builder()
            .id("chatcmpl-no-raw")
            .model("gpt-4o")
            .created(1715000000L)
            .choices(List.of(
                ChatCompletionChunk.Choice.builder()
                    .index(0L)
                    .finishReason(Optional.empty())
                    .delta(ChatCompletionChunk.Choice.Delta.builder()
                        .content("原始内容")
                        .build())
                    .build()
            ))
            .build();

        UnifiedChatResponse resp = converter.convertChunk(chunk);

        assertThat(resp.choices().get(0).delta().content()).isEqualTo("原始内容");
        assertThat(resp.choices().get(0).delta().reasoningContent()).isNull();
    }
}
