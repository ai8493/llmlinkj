package com.ai8493.llmproxy.adapter.anthropic;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ai8493.llmproxy.model.*;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AnthropicConverterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ==================== AnthropicRequestConverter ====================

    private final AnthropicRequestConverter requestConverter = new AnthropicRequestConverter();

    @Test
    void request_基础文本转换() {
        var req = new UnifiedChatRequest(
            "claude-sonnet-4-20250514",
            List.of(new UnifiedMessage(UnifiedMessage.Role.USER, "Hello", null, null, null, null, null)),
            new UnifiedGenerationConfig(null, null, 100, null, null, null, null, null),
            null, null, false);

        var result = requestConverter.convert(req);

        assertThat(result.model().asString()).isEqualTo("claude-sonnet-4-20250514");
        assertThat(result.maxTokens()).isEqualTo(100);
        assertThat(result.messages()).hasSize(1);
        assertThat(result.messages().get(0).role().asString()).isEqualTo("user");
        assertThat(result.messages().get(0).content().asString()).isEqualTo("Hello");
    }

    @Test
    void request_SYSTEM消息独立为顶层system字段() {
        var req = new UnifiedChatRequest(
            "claude-sonnet-4-20250514",
            List.of(
                new UnifiedMessage(UnifiedMessage.Role.SYSTEM, "You are a helpful assistant.", null, null, null, null, null),
                new UnifiedMessage(UnifiedMessage.Role.USER, "Hi", null, null, null, null, null)
            ),
            new UnifiedGenerationConfig(null, null, 100, null, null, null, null, null),
            null, null, false);

        var result = requestConverter.convert(req);

        assertThat(result.system()).isPresent();
        assertThat(result.system().get().asString()).isEqualTo("You are a helpful assistant.");

        assertThat(result.messages()).hasSize(1);
        assertThat(result.messages().get(0).role().asString()).isEqualTo("user");
    }

    @Test
    void request_ASSISTANT消息含reasoningContent() {
        // reasoningContent → ThinkingBlockParam（signature="" 绕过 API 必填校验）
        var req = new UnifiedChatRequest(
            "claude-sonnet-4-20250514",
            List.of(new UnifiedMessage(UnifiedMessage.Role.ASSISTANT, "Here is the answer.",
                null, null, null, null, "Let me think step by step...")),
            new UnifiedGenerationConfig(null, null, 100, null, null, null, null, null),
            null, null, false);

        var params = requestConverter.convert(req);
        assertThat(params).isNotNull();
        assertThat(params.messages()).hasSize(1);
    }

    @Test
    void request_ASSISTANT消息含toolCalls() throws Exception {
        var args = MAPPER.readTree("{\"location\": \"NYC\"}");
        var req = new UnifiedChatRequest(
            "claude-sonnet-4-20250514",
            List.of(new UnifiedMessage(UnifiedMessage.Role.ASSISTANT, null, null,
                List.of(new UnifiedToolCall("tc1", "function",
                    new UnifiedFunctionCall("get_weather", args))),
                null, null, null)),
            new UnifiedGenerationConfig(null, null, 100, null, null, null, null, null),
            null, null, false);

        var result = requestConverter.convert(req);

        assertThat(result.messages()).hasSize(1);
        assertThat(result.messages().get(0).role().asString()).isEqualTo("assistant");

        var blocks = result.messages().get(0).content().asBlockParams();
        assertThat(blocks).hasSize(1);
        assertThat(blocks.get(0).isToolUse()).isTrue();
        assertThat(blocks.get(0).asToolUse().id()).isEqualTo("tc1");
        assertThat(blocks.get(0).asToolUse().name()).isEqualTo("get_weather");
    }

    @Test
    void request_TOOL消息() {
        var req = new UnifiedChatRequest(
            "claude-sonnet-4-20250514",
            List.of(new UnifiedMessage(UnifiedMessage.Role.TOOL, "Temperature: 72F",
                null, null, "tc1", null, null)),
            new UnifiedGenerationConfig(null, null, 100, null, null, null, null, null),
            null, null, false);

        var result = requestConverter.convert(req);

        assertThat(result.messages()).hasSize(1);
        assertThat(result.messages().get(0).role().asString()).isEqualTo("user");

        var blocks = result.messages().get(0).content().asBlockParams();
        assertThat(blocks).hasSize(1);
        assertThat(blocks.get(0).isToolResult()).isTrue();
        assertThat(blocks.get(0).asToolResult().toolUseId()).isEqualTo("tc1");
    }

    @Test
    void request_ToolChoice映射() {
        var hiMsg = List.of(new UnifiedMessage(UnifiedMessage.Role.USER, "hi", null, null, null, null, null));
        var defaultConfig = new UnifiedGenerationConfig(null, null, 100, null, null, null, null, null);

        // None
        var reqNone = new UnifiedChatRequest(
            "claude-sonnet-4-20250514", hiMsg, defaultConfig, null, new UnifiedToolChoice.None(), false);
        var resultNone = requestConverter.convert(reqNone);
        assertThat(resultNone.toolChoice()).isPresent();
        assertThat(resultNone.toolChoice().get().isNone()).isTrue();

        // Auto
        var reqAuto = new UnifiedChatRequest(
            "claude-sonnet-4-20250514", hiMsg, defaultConfig, null, new UnifiedToolChoice.Auto(), false);
        var resultAuto = requestConverter.convert(reqAuto);
        assertThat(resultAuto.toolChoice()).isPresent();
        assertThat(resultAuto.toolChoice().get().isAuto()).isTrue();

        // Required
        var reqTool = new UnifiedChatRequest(
            "claude-sonnet-4-20250514", hiMsg, defaultConfig, null, new UnifiedToolChoice.Required("get_weather"), false);
        var resultTool = requestConverter.convert(reqTool);
        assertThat(resultTool.toolChoice()).isPresent();
        assertThat(resultTool.toolChoice().get().isTool()).isTrue();
        assertThat(resultTool.toolChoice().get().asTool().name()).isEqualTo("get_weather");
    }

    @Test
    void request_生成参数() {
        var hiMsg = List.of(new UnifiedMessage(UnifiedMessage.Role.USER, "hi", null, null, null, null, null));
        var req = new UnifiedChatRequest(
            "claude-sonnet-4-20250514", hiMsg,
            new UnifiedGenerationConfig(0.7, 0.9, 200, List.of("\n\n", "stop"), null, null, null, null),
            null, null, false);

        var result = requestConverter.convert(req);

        assertThat(result.maxTokens()).isEqualTo(200);
        assertThat(result.temperature()).hasValue(0.7);
        assertThat(result.topP()).hasValue(0.9);
        assertThat(result.stopSequences()).hasValue(List.of("\n\n", "stop"));
    }

    @Test
    void request_空消息跳过() {
        var req = new UnifiedChatRequest(
            "claude-sonnet-4-20250514",
            List.of(
                new UnifiedMessage(UnifiedMessage.Role.USER, "", null, null, null, null, null),
                new UnifiedMessage(UnifiedMessage.Role.USER, "valid", null, null, null, null, null)
            ),
            new UnifiedGenerationConfig(null, null, 100, null, null, null, null, null),
            null, null, false);

        var result = requestConverter.convert(req);

        assertThat(result.messages()).hasSize(1);
        assertThat(result.messages().get(0).content().asString()).isEqualTo("valid");
    }

    // ==================== AnthropicResponseConverter ====================

    private final AnthropicResponseConverter responseConverter = new AnthropicResponseConverter();

    @Test
    void response_TextBlock转assistantContent() {
        var sdkMsg = fullMessage("msg_1", List.of(
            ContentBlock.ofText(textBlock("Hello world"))
        ), StopReason.END_TURN, usage(10, 20));

        var result = responseConverter.convert(sdkMsg);

        assertThat(result.id()).isEqualTo("msg_1");
        assertThat(result.model()).isEqualTo("claude-sonnet-4-20250514");
        assertThat(result.choices()).hasSize(1);
        assertThat(result.choices().get(0).message().content()).isEqualTo("Hello world");
        assertThat(result.choices().get(0).finishReason()).isEqualTo("stop");
    }

    @Test
    void response_ThinkingBlock转reasoningContent() {
        var sdkMsg = fullMessage("msg_2", List.of(
            ContentBlock.ofThinking(ThinkingBlock.builder()
                .thinking("step by step...")
                .signature("sig123")
                .build()),
            ContentBlock.ofText(textBlock("Final answer"))
        ), StopReason.END_TURN, usage(10, 20));

        var result = responseConverter.convert(sdkMsg);

        assertThat(result.choices().get(0).message().reasoningContent()).isEqualTo("step by step...");
        assertThat(result.choices().get(0).message().content()).isEqualTo("Final answer");
    }

    @Test
    void response_ToolUseBlock转toolCalls() throws Exception {
        var argsStr = "{\"location\": \"NYC\"}";
        var sdkMsg = fullMessage("msg_3", List.of(
            ContentBlock.ofText(textBlock("I'll check the weather.")),
            ContentBlock.ofToolUse(ToolUseBlock.builder()
                .id("tu_1")
                .name("get_weather")
                .input(JsonValue.from(argsStr))
                .caller(DirectCaller.builder().build())
                .build())
        ), StopReason.TOOL_USE, usage(10, 20));

        var result = responseConverter.convert(sdkMsg);

        assertThat(result.choices()).hasSize(1);
        var toolCalls = result.choices().get(0).message().toolCalls();
        assertThat(toolCalls).isNotNull();
        assertThat(toolCalls).hasSize(1);
        assertThat(toolCalls.get(0).id()).isEqualTo("tu_1");
        assertThat(toolCalls.get(0).function().name()).isEqualTo("get_weather");
        assertThat(toolCalls.get(0).function().arguments()).isNotNull();
        assertThat(toolCalls.get(0).function().arguments().get("location").asText()).isEqualTo("NYC");
    }

    @Test
    void response_StopReason映射() {
        var textContent = List.of(ContentBlock.ofText(textBlock("ok")));

        // END_TURN → stop
        var msgEndTurn = fullMessage("msg_4", textContent, StopReason.END_TURN, usage(10, 20));
        assertThat(responseConverter.convert(msgEndTurn).choices().get(0).finishReason()).isEqualTo("stop");

        // MAX_TOKENS → length
        var msgMaxTokens = fullMessage("msg_5", textContent, StopReason.MAX_TOKENS, usage(10, 20));
        assertThat(responseConverter.convert(msgMaxTokens).choices().get(0).finishReason()).isEqualTo("length");
    }

    @Test
    void response_Usage() {
        var sdkMsg = fullMessage("msg_7", List.of(
            ContentBlock.ofText(textBlock("hi"))
        ), StopReason.END_TURN, usage(50, 30, 5L));

        var result = responseConverter.convert(sdkMsg);

        assertThat(result.usage()).isNotNull();
        assertThat(result.usage().promptTokens()).isEqualTo(50);
        assertThat(result.usage().completionTokens()).isEqualTo(30);
        assertThat(result.usage().totalTokens()).isEqualTo(80);
        assertThat(result.usage().cachedTokens()).isEqualTo(5);
    }

    // ==================== AnthropicStreamingResponseConverter ====================

    @Test
    void streaming_messageStart记录id和model() {
        var converter = new AnthropicStreamingResponseConverter();
        var msg = fullMessage("msg_stream_1", List.of(), StopReason.END_TURN, usage(0, 0));

        var event = RawMessageStreamEvent.ofMessageStart(
            RawMessageStartEvent.builder().message(msg).build());
        var result = converter.convertEvent(event);

        assertThat(result.choices()).isEmpty();
        assertThat(result.id()).isEqualTo("msg_stream_1");
        assertThat(result.model()).isEqualTo("claude-sonnet-4-20250514");
    }

    @Test
    void streaming_textDelta流式输出() {
        var converter = new AnthropicStreamingResponseConverter();
        setupMessageStart(converter, "msg_1");

        converter.convertEvent(RawMessageStreamEvent.ofContentBlockStart(
            RawContentBlockStartEvent.builder()
                .index(0)
                .contentBlock(textBlock(""))
                .build()));

        var deltaResult = converter.convertEvent(RawMessageStreamEvent.ofContentBlockDelta(
            RawContentBlockDeltaEvent.builder()
                .index(0)
                .textDelta("Hello")
                .build()));

        assertThat(deltaResult.choices()).hasSize(1);
        assertThat(deltaResult.choices().get(0).delta().content()).isEqualTo("Hello");
    }

    @Test
    void streaming_thinkingDelta流式输出() {
        var converter = new AnthropicStreamingResponseConverter();
        setupMessageStart(converter, "msg_1");

        converter.convertEvent(RawMessageStreamEvent.ofContentBlockStart(
            RawContentBlockStartEvent.builder()
                .index(0)
                .contentBlock(thinkingBlock("", "sig"))
                .build()));

        var deltaResult = converter.convertEvent(RawMessageStreamEvent.ofContentBlockDelta(
            RawContentBlockDeltaEvent.builder()
                .index(0)
                .thinkingDelta("step by step...")
                .build()));

        assertThat(deltaResult.choices()).hasSize(1);
        assertThat(deltaResult.choices().get(0).delta().reasoningContent()).isEqualTo("step by step...");
    }

    @Test
    void streaming_toolUse完整流程() throws Exception {
        var converter = new AnthropicStreamingResponseConverter();
        setupMessageStart(converter, "msg_tool");

        var addedResult = converter.convertEvent(RawMessageStreamEvent.ofContentBlockStart(
            RawContentBlockStartEvent.builder()
                .index(0)
                .contentBlock(ToolUseBlock.builder()
                    .id("tu_1")
                    .name("get_weather")
                    .input(JsonValue.fromJsonNode(MAPPER.readTree("{}")))
                    .caller(DirectCaller.builder().build())
                    .build())
                .build()));
        // content_block_start(tool_use) 应发射 output_item.added(function_call)
        assertThat(addedResult.choices()).isNotEmpty();
        assertThat(addedResult.choices().get(0).delta().toolCalls()).isNotEmpty();
        assertThat(addedResult.choices().get(0).delta().toolCalls().get(0).id()).isEqualTo("tu_1");

        var deltaResult1 = converter.convertEvent(RawMessageStreamEvent.ofContentBlockDelta(
            RawContentBlockDeltaEvent.builder()
                .index(0)
                .inputJsonDelta("{\"loc")
                .build()));
        // inputJson delta 仅缓冲，不输出（在 messageStop 一次性发射完整 tool_calls）
        assertThat(deltaResult1.choices()).isEmpty();

        converter.convertEvent(RawMessageStreamEvent.ofContentBlockDelta(
            RawContentBlockDeltaEvent.builder()
                .index(0)
                .inputJsonDelta("ation\": \"NYC\"}")
                .build()));

        converter.convertEvent(RawMessageStreamEvent.ofContentBlockStop(
            RawContentBlockStopEvent.builder().index(0).build()));

        converter.convertEvent(RawMessageStreamEvent.ofMessageDelta(
            RawMessageDeltaEvent.builder()
                .delta(RawMessageDeltaEvent.Delta.builder()
                    .stopReason(StopReason.END_TURN)
                    .stopSequence("")
                    .stopDetails(RefusalStopDetails.builder()
                        .category(RefusalStopDetails.Category.CYBER)
                        .explanation("")
                        .build())
                    .build())
                .usage(MessageDeltaUsage.builder()
                    .inputTokens(10L)
                    .outputTokens(20)
                    .cacheCreationInputTokens(0L)
                    .cacheReadInputTokens(0L)
                    .serverToolUse(serverToolUsage())
                    .build())
                .build()));

        var finalResult = converter.convertEvent(RawMessageStreamEvent.ofMessageStop(
            RawMessageStopEvent.builder().build()));

        assertThat(finalResult.choices()).hasSize(1);
        var delta = finalResult.choices().get(0).delta();
        // 文本/推理已在增量 deltas 中输出，messageStop 不重复
        assertThat(delta.content()).isNull();
        assertThat(delta.reasoningContent()).isNull();
        // tool_calls 在 messageStop 一次性发射完整参数
        assertThat(delta.toolCalls()).isNotNull();
        assertThat(delta.toolCalls()).hasSize(1);
        assertThat(delta.toolCalls().get(0).id()).isEqualTo("tu_1");
        assertThat(delta.toolCalls().get(0).function().name()).isEqualTo("get_weather");
        assertThat(delta.toolCalls().get(0).function().arguments().get("location").asText()).isEqualTo("NYC");
        assertThat(finalResult.usage()).isNotNull();
        assertThat(finalResult.usage().promptTokens()).isEqualTo(10);
        assertThat(finalResult.usage().completionTokens()).isEqualTo(20);
    }

    @Test
    void streaming_messageStop组装最终块() throws Exception {
        var converter = new AnthropicStreamingResponseConverter();
        setupMessageStart(converter, "msg_final");

        converter.convertEvent(RawMessageStreamEvent.ofContentBlockStart(
            RawContentBlockStartEvent.builder()
                .index(0)
                .contentBlock(textBlock(""))
                .build()));

        converter.convertEvent(RawMessageStreamEvent.ofContentBlockDelta(
            RawContentBlockDeltaEvent.builder()
                .index(0).textDelta("Hello").build()));
        converter.convertEvent(RawMessageStreamEvent.ofContentBlockDelta(
            RawContentBlockDeltaEvent.builder()
                .index(0).textDelta(" world").build()));

        converter.convertEvent(RawMessageStreamEvent.ofContentBlockStart(
            RawContentBlockStartEvent.builder()
                .index(1)
                .contentBlock(thinkingBlock("", "sig"))
                .build()));

        converter.convertEvent(RawMessageStreamEvent.ofContentBlockDelta(
            RawContentBlockDeltaEvent.builder()
                .index(1).thinkingDelta("thinking...").build()));

        converter.convertEvent(RawMessageStreamEvent.ofContentBlockStart(
            RawContentBlockStartEvent.builder()
                .index(2)
                .contentBlock(ToolUseBlock.builder()
                    .id("tu_2")
                    .name("get_weather")
                    .input(JsonValue.fromJsonNode(MAPPER.readTree("{}")))
                    .caller(DirectCaller.builder().build())
                    .build())
                .build()));

        converter.convertEvent(RawMessageStreamEvent.ofContentBlockDelta(
            RawContentBlockDeltaEvent.builder()
                .index(2).inputJsonDelta("{\"temp\": \"72\"}")
                .build()));

        converter.convertEvent(RawMessageStreamEvent.ofContentBlockStop(
            RawContentBlockStopEvent.builder().index(0).build()));

        converter.convertEvent(RawMessageStreamEvent.ofMessageDelta(
            RawMessageDeltaEvent.builder()
                .delta(RawMessageDeltaEvent.Delta.builder()
                    .stopReason(StopReason.END_TURN)
                    .stopSequence("")
                    .stopDetails(RefusalStopDetails.builder()
                        .category(RefusalStopDetails.Category.CYBER)
                        .explanation("")
                        .build())
                    .build())
                .usage(MessageDeltaUsage.builder()
                    .inputTokens(15L)
                    .outputTokens(42)
                    .cacheReadInputTokens(3L)
                    .cacheCreationInputTokens(0L)
                    .serverToolUse(serverToolUsage())
                    .build())
                .build()));

        var finalResult = converter.convertEvent(RawMessageStreamEvent.ofMessageStop(
            RawMessageStopEvent.builder().build()));

        assertThat(finalResult.choices()).hasSize(1);
        var delta = finalResult.choices().get(0).delta();
        // 文本/推理已在增量 deltas 中流式输出，messageStop 不重复
        assertThat(delta.content()).isNull();
        assertThat(delta.reasoningContent()).isNull();
        // tool_calls 在 messageStop 一次性发射
        assertThat(delta.toolCalls()).hasSize(1);
        assertThat(delta.toolCalls().get(0).id()).isEqualTo("tu_2");

        assertThat(finalResult.usage()).isNotNull();
        assertThat(finalResult.usage().promptTokens()).isEqualTo(15);
        assertThat(finalResult.usage().completionTokens()).isEqualTo(42);
        assertThat(finalResult.usage().cachedTokens()).isEqualTo(3);
    }

    @Test
    void streaming_toolUse无inputJsonDelta且初始input为空_应发送空参数toolCall() throws Exception {
        var converter = new AnthropicStreamingResponseConverter();
        setupMessageStart(converter, "msg_no_delta");

        converter.convertEvent(RawMessageStreamEvent.ofContentBlockStart(
            RawContentBlockStartEvent.builder()
                .index(0)
                .contentBlock(ToolUseBlock.builder()
                    .id("tu_empty")
                    .name("apply_patch")
                    .input(JsonValue.fromJsonNode(MAPPER.readTree("{}")))
                    .caller(DirectCaller.builder().build())
                    .build())
                .build()));

        // 注意：不发送任何 inputJson delta 事件 — 模拟 MiniMax 无输入的场景

        converter.convertEvent(RawMessageStreamEvent.ofContentBlockStop(
            RawContentBlockStopEvent.builder().index(0).build()));

        converter.convertEvent(RawMessageStreamEvent.ofMessageDelta(
            RawMessageDeltaEvent.builder()
                .delta(RawMessageDeltaEvent.Delta.builder()
                    .stopReason(StopReason.END_TURN)
                    .stopSequence("")
                    .stopDetails(RefusalStopDetails.builder()
                        .category(RefusalStopDetails.Category.CYBER)
                        .explanation("")
                        .build())
                    .build())
                .usage(MessageDeltaUsage.builder()
                    .inputTokens(10L)
                    .outputTokens(20)
                    .cacheCreationInputTokens(0L)
                    .cacheReadInputTokens(0L)
                    .serverToolUse(serverToolUsage())
                    .build())
                .build()));

        var finalResult = converter.convertEvent(RawMessageStreamEvent.ofMessageStop(
            RawMessageStopEvent.builder().build()));

        // messageStop 仍应产出 tool_calls，args 为空 JSON 对象兜底
        assertThat(finalResult.choices()).hasSize(1);
        var delta = finalResult.choices().get(0).delta();
        assertThat(delta.toolCalls()).isNotNull();
        assertThat(delta.toolCalls()).hasSize(1);
        assertThat(delta.toolCalls().get(0).id()).isEqualTo("tu_empty");
        assertThat(delta.toolCalls().get(0).function().name()).isEqualTo("apply_patch");
        // args 为空但不为 null — 下游不再跳过
        assertThat(delta.toolCalls().get(0).function().arguments()).isNotNull();
    }

    @Test
    void streaming_toolUse无inputJsonDelta但初始input有值_应回退使用初始input() throws Exception {
        var converter = new AnthropicStreamingResponseConverter();
        setupMessageStart(converter, "msg_initial_input");

        var initialArgs = MAPPER.readTree("{\"input\":\"*** Begin Patch\\n+hello\\n*** End Patch\"}");

        converter.convertEvent(RawMessageStreamEvent.ofContentBlockStart(
            RawContentBlockStartEvent.builder()
                .index(0)
                .contentBlock(ToolUseBlock.builder()
                    .id("tu_init")
                    .name("apply_patch")
                    .input(JsonValue.fromJsonNode(initialArgs))
                    .caller(DirectCaller.builder().build())
                    .build())
                .build()));

        // 无 inputJson delta

        converter.convertEvent(RawMessageStreamEvent.ofContentBlockStop(
            RawContentBlockStopEvent.builder().index(0).build()));

        converter.convertEvent(RawMessageStreamEvent.ofMessageDelta(
            RawMessageDeltaEvent.builder()
                .delta(RawMessageDeltaEvent.Delta.builder()
                    .stopReason(StopReason.END_TURN)
                    .stopSequence("")
                    .stopDetails(RefusalStopDetails.builder()
                        .category(RefusalStopDetails.Category.CYBER)
                        .explanation("")
                        .build())
                    .build())
                .usage(MessageDeltaUsage.builder()
                    .inputTokens(10L)
                    .outputTokens(20)
                    .cacheCreationInputTokens(0L)
                    .cacheReadInputTokens(0L)
                    .serverToolUse(serverToolUsage())
                    .build())
                .build()));

        var finalResult = converter.convertEvent(RawMessageStreamEvent.ofMessageStop(
            RawMessageStopEvent.builder().build()));

        assertThat(finalResult.choices()).hasSize(1);
        var delta = finalResult.choices().get(0).delta();
        assertThat(delta.toolCalls()).hasSize(1);
        assertThat(delta.toolCalls().get(0).id()).isEqualTo("tu_init");
        assertThat(delta.toolCalls().get(0).function().name()).isEqualTo("apply_patch");
        assertThat(delta.toolCalls().get(0).function().arguments()).isNotNull();
        // 回退成功：参数来自初始 input
        assertThat(delta.toolCalls().get(0).function().arguments().get("input").asText())
            .isEqualTo("*** Begin Patch\n+hello\n*** End Patch");
    }

    // ===== 辅助方法 =====

    /** 构建包含所有必填字段的完整 Message */
    private static Message fullMessage(String id, List<ContentBlock> content, StopReason stopReason, Usage usage) {
        return Message.builder()
            .id(id)
            .model("claude-sonnet-4-20250514")
            .content(content)
            .stopReason(stopReason)
            .stopSequence("")
            .stopDetails(RefusalStopDetails.builder()
                .category(RefusalStopDetails.Category.CYBER)
                .explanation("")
                .build())
            .usage(usage)
            .build();
    }

    private static TextBlock textBlock(String text) {
        return TextBlock.builder().text(text).citations(List.of()).build();
    }

    private static ThinkingBlock thinkingBlock(String text, String sig) {
        return ThinkingBlock.builder().thinking(text).signature(sig).build();
    }

    private static Usage usage(long input, long output) {
        return Usage.builder()
            .inputTokens(input)
            .outputTokens(output)
            .cacheCreation(cacheCreation())
            .cacheCreationInputTokens(0L)
            .cacheReadInputTokens(0L)
            .inferenceGeo("")
            .serverToolUse(serverToolUsage())
            .serviceTier(Usage.ServiceTier.STANDARD)
            .build();
    }

    private static Usage usage(long input, long output, long cached) {
        return Usage.builder()
            .inputTokens(input)
            .outputTokens(output)
            .cacheReadInputTokens(cached)
            .cacheCreation(cacheCreation())
            .cacheCreationInputTokens(0L)
            .inferenceGeo("")
            .serverToolUse(serverToolUsage())
            .serviceTier(Usage.ServiceTier.STANDARD)
            .build();
    }

    private static CacheCreation cacheCreation() {
        return CacheCreation.builder()
            .ephemeral1hInputTokens(0)
            .ephemeral5mInputTokens(0)
            .build();
    }

    private static ServerToolUsage serverToolUsage() {
        return ServerToolUsage.builder()
            .webFetchRequests(0)
            .webSearchRequests(0)
            .build();
    }

    private static void setupMessageStart(AnthropicStreamingResponseConverter converter, String msgId) {
        var msg = fullMessage(msgId, List.of(), StopReason.END_TURN, usage(0, 0));
        converter.convertEvent(RawMessageStreamEvent.ofMessageStart(
            RawMessageStartEvent.builder().message(msg).build()));
    }

    // ===== 回归测试：真实 duplicate tool_call id 场景 =====

    @Test
    void request_重复toolCallId自动去重() {
        // 模拟真实场景（源于 MiniMax anthropic 端点上曾报的错）：
        // "duplicate tool_call id: call_function_md5tlih0m78d_1"
        // 多轮对话中同一 call_id 在 history 中重复出现
        var tc1 = new UnifiedToolCall("call_function_dup", "function",
            new UnifiedFunctionCall("shell_command",
                MAPPER.createObjectNode().put("command", "ls")));
        var tc2 = new UnifiedToolCall("call_function_dup", "function",
            new UnifiedFunctionCall("shell_command",
                MAPPER.createObjectNode().put("command", "pwd")));

        var msg1 = new UnifiedMessage(UnifiedMessage.Role.ASSISTANT, "Running ls...",
            null, List.of(tc1), null, null, null);
        var msg2 = new UnifiedMessage(UnifiedMessage.Role.TOOL, "file1 file2",
            null, null, "call_function_dup", "shell_command", null);
        var msg3 = new UnifiedMessage(UnifiedMessage.Role.ASSISTANT, "Running pwd...",
            null, List.of(tc2), null, null, null);
        var msg4 = new UnifiedMessage(UnifiedMessage.Role.TOOL, "/home/user",
            null, null, "call_function_dup", "shell_command", null);

        var req = new UnifiedChatRequest("claude-sonnet-4-20250514",
            List.of(msg1, msg2, msg3, msg4),
            new UnifiedGenerationConfig(null, null, 100, null, null, null, null, null),
            null, null, false);

        // 不应抛出异常 — converter 内部 uniquify 了重复 ID
        var params = requestConverter.convert(req);
        assertThat(params).isNotNull();
        assertThat(params.messages()).hasSize(4);
    }

    // ===== 真实报文回归测试 =====

    @Test
    void realFixture_全链路转换字段级验证() throws IOException {
        // 从日志提取的真实 Responses API 请求报文（69441 字节，触发过 duplicate tool_call id 错误）
        byte[] rawRequest = Files.readAllBytes(
            Path.of("src/test/resources/fixtures/real-duplicate-toolcall-request.json"));

        // 1) Responses Protocol Adapter → IR
        var protocolAdapter = new com.ai8493.llmproxy.adapter.openai.ResponsesProtocolAdapter();
        UnifiedChatRequest ir = protocolAdapter.toUnifiedRequest(rawRequest, null);

        // ====== IR 层验证 ======
        assertThat(ir.model()).isEqualTo("gpt-5.5");
        assertThat(ir.stream()).isTrue();
        // 4 条 input 消息 + 1 条 SYSTEM（来自 instructions）
        assertThat(ir.messages()).hasSize(5);
        assertThat(ir.messages().get(0).role()).isEqualTo(UnifiedMessage.Role.SYSTEM);
        assertThat(ir.messages().get(0).content()).isNotEmpty();
        // tools：custom 型 + namespace 型被过滤，保留 function 型
        assertThat(ir.tools()).isNotNull();
        assertThat(ir.tools().size()).isGreaterThanOrEqualTo(9);
        // 每个 tool 的 function 定义完整
        assertThat(ir.tools().get(0).function().name()).isEqualTo("shell_command");
        assertThat(ir.tools().get(0).function().parameters()).isNotNull();
        // generation config 校验
        assertThat(ir.config()).isNotNull();
        assertThat(ir.config().temperature()).isNull(); // 请求未设 temperature
        assertThat(ir.config().maxOutputTokens()).isNull(); // 请求未设 max_output_tokens
        assertThat(ir.toolChoice()).isNotNull(); // tool_choice=auto

        // ====== IR → Anthropic SDK 验证 ======
        var params = requestConverter.convert(ir);
        assertThat(params).isNotNull();
        assertThat(params.model().toString()).isEqualTo("gpt-5.5");
        // system 独立字段
        assertThat(params.system().isPresent()).isTrue();
        assertThat(params.system().get().isString()).isTrue();
        assertThat(params.system().get().asString()).isNotEmpty();
        // messages：非 SYSTEM 的 5 条 + TOOL 消息合并后
        assertThat(params.messages().size()).isGreaterThanOrEqualTo(3);
        // maxTokens 默认 4096
        assertThat(params.maxTokens()).isEqualTo(4096L);
        // tools 全部转换
        assertThat(params.tools().isPresent()).isTrue();
        assertThat(params.tools().get().size()).isGreaterThanOrEqualTo(9);
    }

    @Test
    void realFixture_加重复callId模拟多轮去重验证() throws IOException {
        // 加载真实报文
        byte[] rawRequest = Files.readAllBytes(
            Path.of("src/test/resources/fixtures/real-duplicate-toolcall-request.json"));
        var protocolAdapter = new com.ai8493.llmproxy.adapter.openai.ResponsesProtocolAdapter();
        UnifiedChatRequest ir = protocolAdapter.toUnifiedRequest(rawRequest, null);

        // 模拟多轮场景：在消息列表头部插入两条含相同 call_id 的 assistant+tool 消息
        var dupId = "call_function_md5tlih0m78d_1"; // 日志中真实出现的重复 ID
        var args = MAPPER.createObjectNode().put("command", "ls -la");
        var toolCall = new UnifiedToolCall(dupId, "function",
            new UnifiedFunctionCall("shell_command", args));
        var assistantMsg = new UnifiedMessage(UnifiedMessage.Role.ASSISTANT, "Running ls...",
            null, List.of(toolCall), null, null, null);
        var toolMsg = new UnifiedMessage(UnifiedMessage.Role.TOOL, "file1 file2 dir3",
            null, null, dupId, "shell_command", null);
        // 再插入一个同样 ID 的 function_call（模拟重复）
        var toolCall2 = new UnifiedToolCall(dupId, "function", // 相同 ID！
            new UnifiedFunctionCall("shell_command",
                MAPPER.createObjectNode().put("command", "pwd")));
        var assistantMsg2 = new UnifiedMessage(UnifiedMessage.Role.ASSISTANT, "Running pwd...",
            null, List.of(toolCall2), null, null, null);
        var toolMsg2 = new UnifiedMessage(UnifiedMessage.Role.TOOL, "/home/user",
            null, null, dupId, "shell_command", null);

        // 在原始 SYSTEM 消息之后插入历史对话
        var messages = new java.util.ArrayList<>(ir.messages());
        messages.add(1, assistantMsg);
        messages.add(2, toolMsg);
        messages.add(3, assistantMsg2);
        messages.add(4, toolMsg2);

        var modifiedIr = new UnifiedChatRequest(ir.model(), messages,
            ir.config(), ir.tools(), ir.toolChoice(), ir.stream());

        // 转换不应抛异常
        var params = requestConverter.convert(modifiedIr);
        assertThat(params).isNotNull();
        // 消息数：原始6条 + 新增4条 = 10，TOOL合并且去重后应该是 8
        assertThat(params.messages().size()).isEqualTo(8);
    }

    @Test
    void realFixture_Gemini孤儿toolResult转文本() throws IOException {
        // 加载从日志提取的真实 Gemini 请求报文
        // 该报文的最后一个 user content 包含 4 个 functionResponse
        // （call_function_zddfun7riife_1~4），但对应 functionCall 不在本次请求中，
        // 导致 Anthropic API 返回 400: "tool result's tool id not found (2013)"
        byte[] rawRequest = Files.readAllBytes(
            Path.of("src/test/resources/fixtures/error-tool-result-not-found.json"));

        // 1) Gemini Protocol Adapter → IR
        var protocolAdapter = new com.ai8493.llmproxy.adapter.gemini.GeminiProtocolAdapter();
        UnifiedChatRequest ir = protocolAdapter.toUnifiedRequest(rawRequest, null);
        // Gemini 请求体中可能不含 model 字段（model 来自 URL path），手动补上避免 NPE
        ir = new UnifiedChatRequest("MiniMax-M2.7", ir.messages(), ir.config(),
            ir.tools(), ir.toolChoice(), ir.stream());

        // ====== IR 层验证 ======
        assertThat(ir.messages()).isNotEmpty();
        // 找到含 zddfun7riife 的 TOOL 消息
        var orphanTools = ir.messages().stream()
            .filter(m -> m.role() == UnifiedMessage.Role.TOOL
                && m.toolCallId() != null
                && m.toolCallId().contains("zddfun7riife"))
            .toList();
        assertThat(orphanTools).as("孤儿 TOOL 消息应存在").isNotEmpty();
        assertThat(orphanTools).hasSize(4);

        // ====== IR → Anthropic SDK：不应抛异常，孤儿转为文本 ======
        var params = requestConverter.convert(ir);
        assertThat(params).isNotNull();

        // 验证转换后的消息中至少有一条 user 消息包含孤儿工具结果转换的文本
        // （格式为 "[tool_result: write_file] {error: ...}"）
        boolean foundOrphanAsText = params.messages().stream()
            .filter(m -> "user".equals(m.role().asString()))
            .anyMatch(m -> {
                String content = m.content().toString();
                return content.contains("tool_result")
                    && content.contains("write_file")
                    && content.contains("zddfun7riife");
            });
        assertThat(foundOrphanAsText)
            .as("孤儿 tool_result 应转换为文本消息")
            .isTrue();
    }

    @Test
    void realFixture_空响应报文全链路验证() throws IOException {
        // 从日志提取的 1.2MB 真实 Responses 请求（导致 MiniMax 返回空响应）
        byte[] rawRequest = Files.readAllBytes(
            Path.of("src/test/resources/fixtures/current-empty-response.json"));

        // 1) Responses Protocol Adapter → IR
        var protocolAdapter = new com.ai8493.llmproxy.adapter.openai.ResponsesProtocolAdapter();
        UnifiedChatRequest ir = protocolAdapter.toUnifiedRequest(rawRequest, null);

        assertThat(ir.model()).isEqualTo("gpt-5.5");
        assertThat(ir.stream()).isTrue();
        assertThat(ir.messages()).isNotEmpty();
        assertThat(ir.tools()).isNotNull().isNotEmpty();

        // 2) IR → Anthropic SDK：验证转换不抛异常、不丢数据
        var params = requestConverter.convert(ir);
        assertThat(params).isNotNull();
        assertThat(params.model().asString()).isEqualTo("gpt-5.5");
        assertThat(params.maxTokens()).isEqualTo(4096L);

        // system 字段来自 instructions
        assertThat(params.system().isPresent()).isTrue();

        // messages 数 > 0（具体数量取决于合并逻辑）
        assertThat(params.messages()).isNotEmpty();

        // 验证最后几条消息中包含用户输入
        var lastMsgs = params.messages().subList(
            Math.max(0, params.messages().size() - 3),
            params.messages().size());
        boolean hasUserContent = lastMsgs.stream()
            .filter(m -> "user".equals(m.role().asString()))
            .anyMatch(m -> m.content().toString().length() > 0);
        assertThat(hasUserContent).as("最后的用户消息应被保留").isTrue();

        // tools 全部转换
        assertThat(params.tools().isPresent()).isTrue();
        assertThat(params.tools().get()).hasSizeGreaterThan(8);
    }

    @Test
    void realFixture_Gemini工具schema字段级验证() throws IOException {
        // 从日志提取的真实 Gemini 请求（模型调用 run_shell_command 时 args={} 导致报错）
        byte[] rawRequest = Files.readAllBytes(
            Path.of("src/test/resources/fixtures/gemini-real-request.json"));

        var protocolAdapter = new com.ai8493.llmproxy.adapter.gemini.GeminiProtocolAdapter();
        UnifiedChatRequest ir = protocolAdapter.toUnifiedRequest(rawRequest, null);
        ir = new UnifiedChatRequest("MiniMax-M2.7", ir.messages(), ir.config(),
            ir.tools(), ir.toolChoice(), ir.stream());

        // 验证 tools 非空
        assertThat(ir.tools()).as("IR tools 不应为空").isNotNull().isNotEmpty();

        // 验证 shell_command 的 schema
        var shellCmd = ir.tools().stream()
            .filter(t -> "run_shell_command".equals(t.function().name()))
            .findFirst();
        assertThat(shellCmd).as("run_shell_command 工具应存在").isPresent();
        var params = shellCmd.get().function().parameters();
        assertThat(params).as("parameters 不应为 null").isNotNull();
        assertThat(params.has("type")).as("应有 type 字段").isTrue();
        assertThat(params.get("type").asText()).isEqualTo("object");
        assertThat(params.has("properties")).as("应有 properties").isTrue();
        assertThat(params.get("properties").has("command")).as("应有 command 属性").isTrue();
        assertThat(params.has("required")).as("应有 required 数组").isTrue();
        assertThat(params.get("required").toString()).as("required 应包含 command")
            .contains("command");

        // 验证 write_file 的 schema
        var writeFile = ir.tools().stream()
            .filter(t -> "write_file".equals(t.function().name()))
            .findFirst();
        assertThat(writeFile).as("write_file 工具应存在").isPresent();
        var wfParams = writeFile.get().function().parameters();
        assertThat(wfParams.get("properties").has("file_path")).as("应有 file_path 属性").isTrue();

        // 验证 glob 的 schema (FindFiles)
        var glob = ir.tools().stream()
            .filter(t -> "glob".equals(t.function().name()))
            .findFirst();
        assertThat(glob).as("glob 工具应存在").isPresent();
        var globParams = glob.get().function().parameters();
        assertThat(globParams.get("properties").has("pattern")).as("应有 pattern 属性").isTrue();
        assertThat(globParams.get("required").toString()).as("required 应包含 pattern")
            .contains("pattern");

        // IR → Anthropic SDK
        var params2 = requestConverter.convert(ir);
        assertThat(params2).isNotNull();
        assertThat(params2.tools()).as("Anthropic tools 不应为空").isPresent();
        assertThat(params2.tools().get()).as("Anthropic tools 不应为空列表").isNotEmpty();
    }

    @Test
    void realFixture_toolCallId无效错误全链路排查() throws IOException {
        byte[] rawRequest = Files.readAllBytes(
            Path.of("src/test/resources/fixtures/error-invalid-tool-call-id.json"));

        var protocolAdapter = new com.ai8493.llmproxy.adapter.openai.ResponsesProtocolAdapter();
        UnifiedChatRequest ir = protocolAdapter.toUnifiedRequest(rawRequest, null);

        // 验证 IR 中所有 toolCallId / toolCall id 非空
        for (UnifiedMessage msg : ir.messages()) {
            if (msg.role() == UnifiedMessage.Role.TOOL && msg.toolCallId() != null) {
                assertThat(msg.toolCallId()).as("TOOL toolCallId 不应为空").isNotEmpty();
            }
            if (msg.role() == UnifiedMessage.Role.ASSISTANT && msg.toolCalls() != null) {
                for (UnifiedToolCall tc : msg.toolCalls()) {
                    assertThat(tc.id()).as("ASSISTANT toolCall id 不应为空").isNotBlank();
                }
            }
        }

        // IR → Anthropic SDK
        var params = requestConverter.convert(ir);
        assertThat(params).isNotNull();

        // 检查所有 tool_use block 的 ID
        for (var msg : params.messages()) {
            if ("assistant".equals(msg.role().asString())) {
                try {
                    for (var block : msg.content().asBlockParams()) {
                        if (block.isToolUse()) {
                            String tuId = block.asToolUse().id();
                            assertThat(tuId).as("tool_use id 不应为空").isNotBlank();
                            assertThat(tuId).as("tool_use id 应以 call_ 开头").startsWith("call_");
                        }
                    }
                } catch (Exception ignored) {}
            }
        }
    }
}
