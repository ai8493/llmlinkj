package com.ai8493.llmproxy.adapter.openai;

import com.openai.core.JsonValue;
import com.openai.models.chat.completions.*;
import com.openai.models.completions.CompletionUsage;
import com.openai.models.completions.CompletionUsage.PromptTokensDetails;
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
    void shouldStreamToolCallsAndClearOnFinishReason() {
        // 第一个 chunk: tool call 开始（id + function name）→ 输出 delta.toolCalls(arguments=null)
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

        UnifiedChatResponse resp1 = converter.convertChunk(chunk1);

        // 真流式:第一个 chunk 直接输出 id+name(arguments 为 null),无需等待 finishReason
        assertThat(resp1.choices().get(0).delta().toolCalls()).hasSize(1);
        UnifiedToolCall tc1 = resp1.choices().get(0).delta().toolCalls().get(0);
        assertThat(tc1.id()).isEqualTo("call_xyz");
        assertThat(tc1.function().name()).isEqualTo("get_weather");
        assertThat(tc1.function().arguments()).isNull();
        assertThat(resp1.choices().get(0).delta().toolCallArgumentDeltas()).isNull();

        // 第二个 chunk: tool call arguments 增量 → 输出 delta.toolCallArgumentDeltas,无 toolCalls
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

        UnifiedChatResponse resp2 = converter.convertChunk(chunk2);

        // 真流式:arguments 增量直接放到 toolCallArgumentDeltas,不再累积到 finishReason
        assertThat(resp2.choices().get(0).delta().toolCallArgumentDeltas().get(0).partialJson()).isEqualTo("{\"city\":\"北京\"}");
        assertThat(resp2.choices().get(0).delta().toolCalls()).isNull();

        // 第三个 chunk: finish_reason → 清空累积器,delta 无 toolCalls 无 argumentDelta
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

        UnifiedChatResponse resp3 = converter.convertChunk(chunk3);

        assertThat(resp3.choices().get(0).finishReason()).isEqualTo("tool_calls");
        assertThat(resp3.choices().get(0).delta().toolCalls()).isNull();
        assertThat(resp3.choices().get(0).delta().toolCallArgumentDeltas()).isNull();
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

    @Test
    void shouldDeductCachedTokensFromPromptTokensInStream() {
        // 流式版计费恒等式: IR.promptTokens + IR.cachedTokens + IR.cacheCreationTokens == 原 promptTokens
        var chunk = ChatCompletionChunk.builder()
            .id("chatcmpl-stream-1")
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

        UnifiedChatResponse ir = converter.convertChunk(chunk);

        // 100 - 30 = 70；cachedTokens=30；cacheCreation=0（OpenAI 不区分）
        assertThat(ir.usage().promptTokens()).isEqualTo(70);
        assertThat(ir.usage().cachedTokens()).isEqualTo(30);
        assertThat(ir.usage().cacheCreationTokens()).isEqualTo(0);
        assertThat(ir.usage().completionTokens()).isEqualTo(50);
    }

    @Test
    void shouldKeepPromptTokensWhenNoCacheInStream() {
        // 无 promptTokensDetails.cachedTokens 时，promptTokens 保持原值
        var chunk = ChatCompletionChunk.builder()
            .id("chatcmpl-stream-2")
            .model("gpt-4")
            .created(1700000000L)
            .choices(List.of())
            .usage(CompletionUsage.builder()
                .promptTokens(100L)
                .completionTokens(50L)
                .totalTokens(150L)
                .build())
            .build();

        UnifiedChatResponse ir = converter.convertChunk(chunk);

        assertThat(ir.usage().promptTokens()).isEqualTo(100);
        assertThat(ir.usage().cachedTokens()).isEqualTo(0);
    }

    @Test
    void shouldClampPromptTokensToZeroWhenCachedExceedsRawInStream() {
        // 边界场景:cached(80) > rawPromptTokens(50) → IR.promptTokens clamp 到 0
        var chunk = ChatCompletionChunk.builder()
            .id("chatcmpl-stream-clamp")
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

        UnifiedChatResponse ir = converter.convertChunk(chunk);

        // Math.max(0, 50-80) = 0,不出现负值
        assertThat(ir.usage().promptTokens()).isEqualTo(0);
        assertThat(ir.usage().cachedTokens()).isEqualTo(80);
        assertThat(ir.usage().cacheCreationTokens()).isEqualTo(0);
    }

    @Test
    void shouldStreamToolCallArgumentDeltaWithoutAccumulating() {
        // 第一个 chunk:id + name(无 arguments)
        var chunk1 = ChatCompletionChunk.builder()
            .id("chatcmpl-tool-1")
            .model("gpt-4")
            .created(1700000000L)
            .choices(List.of(
                ChatCompletionChunk.Choice.builder()
                    .index(0L)
                    .finishReason(Optional.empty())
                    .delta(ChatCompletionChunk.Choice.Delta.builder()
                        .toolCalls(List.of(
                            ChatCompletionChunk.Choice.Delta.ToolCall.builder()
                                .index(0L)
                                .id("call_abc")
                                .function(ChatCompletionChunk.Choice.Delta.ToolCall.Function.builder()
                                    .name("get_weather")
                                    .build())
                                .build()))
                        .build())
                    .build()))
            .build();

        UnifiedChatResponse ir1 = converter.convertChunk(chunk1);

        // 验证:delta.toolCalls 含 id+name(arguments 为 null),无 toolCallArgumentDeltas
        assertThat(ir1.choices()).hasSize(1);
        assertThat(ir1.choices().get(0).delta().toolCalls()).hasSize(1);
        assertThat(ir1.choices().get(0).delta().toolCalls().get(0).id()).isEqualTo("call_abc");
        assertThat(ir1.choices().get(0).delta().toolCalls().get(0).function().name()).isEqualTo("get_weather");
        assertThat(ir1.choices().get(0).delta().toolCalls().get(0).function().arguments()).isNull();
        assertThat(ir1.choices().get(0).delta().toolCallArgumentDeltas()).isNull();

        // 第二个 chunk:arguments 增量
        var chunk2 = ChatCompletionChunk.builder()
            .id("chatcmpl-tool-1")
            .model("gpt-4")
            .created(1700000000L)
            .choices(List.of(
                ChatCompletionChunk.Choice.builder()
                    .index(0L)
                    .finishReason(Optional.empty())
                    .delta(ChatCompletionChunk.Choice.Delta.builder()
                        .toolCalls(List.of(
                            ChatCompletionChunk.Choice.Delta.ToolCall.builder()
                                .index(0L)
                                .function(ChatCompletionChunk.Choice.Delta.ToolCall.Function.builder()
                                    .arguments("{\"location\":")
                                    .build())
                                .build()))
                        .build())
                    .build()))
            .build();

        UnifiedChatResponse ir2 = converter.convertChunk(chunk2);

        // 验证:delta.toolCallArgumentDeltas 含增量字符串,无 toolCalls
        assertThat(ir2.choices().get(0).delta().toolCallArgumentDeltas().get(0).partialJson()).isEqualTo("{\"location\":");
        assertThat(ir2.choices().get(0).delta().toolCalls()).isNull();

        // 第三个 chunk:arguments 增量
        var chunk3 = ChatCompletionChunk.builder()
            .id("chatcmpl-tool-1")
            .model("gpt-4")
            .created(1700000000L)
            .choices(List.of(
                ChatCompletionChunk.Choice.builder()
                    .index(0L)
                    .finishReason(Optional.empty())
                    .delta(ChatCompletionChunk.Choice.Delta.builder()
                        .toolCalls(List.of(
                            ChatCompletionChunk.Choice.Delta.ToolCall.builder()
                                .index(0L)
                                .function(ChatCompletionChunk.Choice.Delta.ToolCall.Function.builder()
                                    .arguments("\"Beijing\"}")
                                    .build())
                                .build()))
                        .build())
                    .build()))
            .build();

        UnifiedChatResponse ir3 = converter.convertChunk(chunk3);

        assertThat(ir3.choices().get(0).delta().toolCallArgumentDeltas().get(0).partialJson()).isEqualTo("\"Beijing\"}");
    }

    // ===== P1-4: <think> 标签流式拆分 =====

    @Test
    void shouldStreamSplitSingleChunkThinkBlock() {
        // 整个 <think>...</think>answer 在一个 chunk 中
        var chunk = ChatCompletionChunk.builder()
            .id("chatcmpl-think")
            .model("MiniMax-abab6.5")
            .created(1715000000L)
            .choices(List.of(
                ChatCompletionChunk.Choice.builder()
                    .index(0L)
                    .finishReason(Optional.empty())
                    .delta(ChatCompletionChunk.Choice.Delta.builder()
                        .content("<think>reasoning</think>answer")
                        .build())
                    .build()
            ))
            .build();

        UnifiedChatResponse resp = converter.convertChunk(chunk);

        assertThat(resp.choices().get(0).delta().reasoningContent()).isEqualTo("reasoning");
        assertThat(resp.choices().get(0).delta().content()).isEqualTo("answer");
    }

    @Test
    void shouldStreamSplitThinkBlockAcrossChunks() {
        // <think> 标签跨 chunk,reasoning 内容跨 chunk,</think> 闭合
        var c1 = chunkWithContent("<thi");
        var c2 = chunkWithContent("nk>reasoning");
        var c3 = chunkWithContent("</think>answer");

        // c1: 不够决定,无输出
        UnifiedChatResponse r1 = converter.convertChunk(c1);
        assertThat(r1.choices().get(0).delta().content()).isNull();
        assertThat(r1.choices().get(0).delta().reasoningContent()).isNull();
        // c2: 决定 REASONING,但还没 </think>,无输出
        UnifiedChatResponse r2 = converter.convertChunk(c2);
        assertThat(r2.choices().get(0).delta().content()).isNull();
        assertThat(r2.choices().get(0).delta().reasoningContent()).isNull();
        // c3: </think> 闭合,输出 reasoning + answer
        UnifiedChatResponse r3 = converter.convertChunk(c3);
        assertThat(r3.choices().get(0).delta().reasoningContent()).isEqualTo("reasoning");
        assertThat(r3.choices().get(0).delta().content()).isEqualTo("answer");
    }

    @Test
    void shouldStreamNotSplitWhenReasoningContentAlreadyPresent() {
        // 后端原生 reasoning_content,不拆 content
        var chunk = ChatCompletionChunk.builder()
            .id("chatcmpl-rc")
            .model("deepseek-v4")
            .created(1715000000L)
            .choices(List.of(
                ChatCompletionChunk.Choice.builder()
                    .index(0L)
                    .finishReason(Optional.empty())
                    .delta(ChatCompletionChunk.Choice.Delta.builder()
                        .content("<think>should not split</think>answer")
                        .putAdditionalProperty("reasoning_content", JsonValue.from("原生 reasoning"))
                        .build())
                    .build()
            ))
            .build();

        UnifiedChatResponse resp = converter.convertChunk(chunk);

        assertThat(resp.choices().get(0).delta().reasoningContent()).isEqualTo("原生 reasoning");
        assertThat(resp.choices().get(0).delta().content()).isEqualTo("<think>should not split</think>answer");
    }

    @Test
    void shouldStreamTextAfterThinkBlockAsContent() {
        // think 块结束后,后续 chunk 直接作为 content
        converter.convertChunk(chunkWithContent("<think>r</think>"));
        UnifiedChatResponse r = converter.convertChunk(chunkWithContent("actual answer"));
        assertThat(r.choices().get(0).delta().content()).isEqualTo("actual answer");
        assertThat(r.choices().get(0).delta().reasoningContent()).isNull();
    }

    @Test
    void shouldStreamFlushUnclosedReasoningAtEnd() {
        // 流结束时 <think> 未闭合,flush 把 buffer 中 reasoning 输出
        converter.convertChunk(chunkWithContent("<think>partial"));
        UnifiedChatResponse flushed = converter.flush("chatcmpl-flush", 1715000000L);
        assertThat(flushed).isNotNull();
        assertThat(flushed.choices().get(0).delta().reasoningContent()).isEqualTo("partial");
    }

    private ChatCompletionChunk chunkWithContent(String content) {
        return ChatCompletionChunk.builder()
            .id("chatcmpl-think")
            .model("MiniMax-abab6.5")
            .created(1715000000L)
            .choices(List.of(
                ChatCompletionChunk.Choice.builder()
                    .index(0L)
                    .finishReason(Optional.empty())
                    .delta(ChatCompletionChunk.Choice.Delta.builder()
                        .content(content)
                        .build())
                    .build()
            ))
            .build();
    }

    // ===== P2-10: 流式截断分类 =====

    @Test
    void shouldMarkStreamCompletedWhenFinishReasonReceived() {
        // 收到 finishReason 后,isStreamCompleted 应为 true
        var chunk = ChatCompletionChunk.builder()
            .id("chatcmpl-stop")
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

        converter.convertChunk(chunk);

        assertThat(converter.isStreamCompleted()).isTrue();
    }

    @Test
    void shouldNotMarkStreamCompletedWithoutFinishReason() {
        // 只有 content,无 finishReason -> isStreamCompleted=false
        var chunk = ChatCompletionChunk.builder()
            .id("chatcmpl-content")
            .model("gpt-4o")
            .created(1715000000L)
            .choices(List.of(
                ChatCompletionChunk.Choice.builder()
                    .index(0L)
                    .finishReason(Optional.empty())
                    .delta(ChatCompletionChunk.Choice.Delta.builder()
                        .content("部分内容")
                        .build())
                    .build()
            ))
            .build();

        converter.convertChunk(chunk);

        assertThat(converter.isStreamCompleted()).isFalse();
        assertThat(converter.hasSubstantiveOutput()).isTrue();
    }

    @Test
    void shouldTrackSubstantiveOutputFromContent() {
        var chunk = ChatCompletionChunk.builder()
            .id("chatcmpl-c")
            .model("gpt-4o")
            .created(1715000000L)
            .choices(List.of(
                ChatCompletionChunk.Choice.builder()
                    .index(0L)
                    .finishReason(Optional.empty())
                    .delta(ChatCompletionChunk.Choice.Delta.builder()
                        .content("text")
                        .build())
                    .build()
            ))
            .build();

        converter.convertChunk(chunk);

        assertThat(converter.hasSubstantiveOutput()).isTrue();
    }

    @Test
    void shouldTrackSubstantiveOutputFromReasoningContent() {
        var chunk = ChatCompletionChunk.builder()
            .id("chatcmpl-r")
            .model("deepseek-v4")
            .created(1715000000L)
            .choices(List.of(
                ChatCompletionChunk.Choice.builder()
                    .index(0L)
                    .finishReason(Optional.empty())
                    .delta(ChatCompletionChunk.Choice.Delta.builder()
                        .putAdditionalProperty("reasoning_content", JsonValue.from("推理中"))
                        .build())
                    .build()
            ))
            .build();

        converter.convertChunk(chunk);

        assertThat(converter.hasSubstantiveOutput()).isTrue();
    }

    @Test
    void shouldNotFlagSubstantiveOutputForEmptyChunk() {
        // 无 content/reasoning/toolCalls 的空 chunk,不应标记为有实质性输出
        var chunk = ChatCompletionChunk.builder()
            .id("chatcmpl-empty")
            .model("gpt-4o")
            .created(1715000000L)
            .choices(List.of(
                ChatCompletionChunk.Choice.builder()
                    .index(0L)
                    .finishReason(Optional.empty())
                    .delta(ChatCompletionChunk.Choice.Delta.builder().build())
                    .build()
            ))
            .build();

        converter.convertChunk(chunk);

        assertThat(converter.hasSubstantiveOutput()).isFalse();
        assertThat(converter.isStreamCompleted()).isFalse();
    }

    @Test
    void shouldSynthesizeIncompleteChunkWithLengthFinishReason() {
        // 合成的兜底 chunk 应带 finish_reason=length
        UnifiedChatResponse synth = converter.synthesizeIncompleteChunk(null, 0L);

        assertThat(synth.choices()).hasSize(1);
        assertThat(synth.choices().get(0).finishReason()).isEqualTo("length");
        assertThat(synth.object()).isEqualTo("chat.completion.chunk");
    }

    // ===== P3-15: 流式空白防护 =====

    @Test
    void shouldPassThroughNormalArgumentDelta() {
        // 正常 arguments 增量应正常输出
        var startChunk = ChatCompletionChunk.builder()
            .id("chatcmpl-ws1")
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
                                .id("call_1")
                                .function(ChatCompletionChunk.Choice.Delta.ToolCall.Function.builder()
                                    .name("get_weather")
                                    .build())
                                .build()
                        ))
                        .build())
                    .build()
            ))
            .build();
        converter.convertChunk(startChunk);

        var argsChunk = ChatCompletionChunk.builder()
            .id("chatcmpl-ws1")
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
                                    .arguments("{\"city\":\"NYC\"}")
                                    .build())
                                .build()
                        ))
                        .build())
                    .build()
            ))
            .build();
        UnifiedChatResponse resp = converter.convertChunk(argsChunk);

        assertThat(resp.choices().get(0).delta().toolCallArgumentDeltas()).hasSize(1);
        assertThat(resp.choices().get(0).delta().toolCallArgumentDeltas().get(0).partialJson())
            .isEqualTo("{\"city\":\"NYC\"}");
    }

    @Test
    void shouldAbortToolCallWhenExcessiveWhitespace() {
        // P3-15: 连续空白 >= 500 字符时中止 tool_call 参数转发
        var startChunk = ChatCompletionChunk.builder()
            .id("chatcmpl-ws2")
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
                                .id("call_ws")
                                .function(ChatCompletionChunk.Choice.Delta.ToolCall.Function.builder()
                                    .name("slow_tool")
                                    .build())
                                .build()
                        ))
                        .build())
                    .build()
            ))
            .build();
        converter.convertChunk(startChunk);

        // 发送 600 字符的纯空白 arguments
        String whitespace = " ".repeat(600);
        var wsChunk = ChatCompletionChunk.builder()
            .id("chatcmpl-ws2")
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
                                    .arguments(whitespace)
                                    .build())
                                .build()
                        ))
                        .build())
                    .build()
            ))
            .build();
        UnifiedChatResponse resp = converter.convertChunk(wsChunk);

        // 空白 >= 500 -> 该 toolCall 被中止,argumentDelta 不输出
        assertThat(resp.choices().get(0).delta().toolCallArgumentDeltas())
            .as("超额空白应被丢弃").isNullOrEmpty();
    }

    @Test
    void shouldNotAbortWhenWhitespaceBelowThreshold() {
        // 499 字符空白未达阈值,正常输出
        var startChunk = ChatCompletionChunk.builder()
            .id("chatcmpl-ws3")
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
                                .id("call_ok")
                                .function(ChatCompletionChunk.Choice.Delta.ToolCall.Function.builder()
                                    .name("ok_tool")
                                    .build())
                                .build()
                        ))
                        .build())
                    .build()
            ))
            .build();
        converter.convertChunk(startChunk);

        String whitespace = " ".repeat(499);
        var wsChunk = ChatCompletionChunk.builder()
            .id("chatcmpl-ws3")
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
                                    .arguments(whitespace)
                                    .build())
                                .build()
                        ))
                        .build())
                    .build()
            ))
            .build();
        UnifiedChatResponse resp = converter.convertChunk(wsChunk);

        // 未达阈值 -> 正常输出
        assertThat(resp.choices().get(0).delta().toolCallArgumentDeltas()).hasSize(1);
    }

    @Test
    void shouldKeepAbortedAcrossSubsequentDeltas() {
        // 一旦中止,后续 arguments 增量也被丢弃
        var startChunk = ChatCompletionChunk.builder()
            .id("chatcmpl-ws4")
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
                                .id("call_keep")
                                .function(ChatCompletionChunk.Choice.Delta.ToolCall.Function.builder()
                                    .name("keep_tool")
                                    .build())
                                .build()
                        ))
                        .build())
                    .build()
            ))
            .build();
        converter.convertChunk(startChunk);

        // 触发中止
        String ws = " ".repeat(500);
        var wsChunk = ChatCompletionChunk.builder()
            .id("chatcmpl-ws4")
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
                                    .arguments(ws)
                                    .build())
                                .build()
                        ))
                        .build())
                    .build()
            ))
            .build();
        converter.convertChunk(wsChunk);

        // 后续正常 arguments 也应被丢弃(已中止)
        var afterChunk = ChatCompletionChunk.builder()
            .id("chatcmpl-ws4")
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
                                    .arguments("{\"real\":\"args\"}")
                                    .build())
                                .build()
                        ))
                        .build())
                    .build()
            ))
            .build();
        UnifiedChatResponse after = converter.convertChunk(afterChunk);

        assertThat(after.choices().get(0).delta().toolCallArgumentDeltas())
            .as("中止后后续 delta 也应被丢弃").isNullOrEmpty();
    }
}
