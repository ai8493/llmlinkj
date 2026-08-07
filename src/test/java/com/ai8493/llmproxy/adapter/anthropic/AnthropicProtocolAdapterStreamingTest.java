package com.ai8493.llmproxy.adapter.anthropic;

import com.ai8493.llmproxy.model.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class AnthropicProtocolAdapterStreamingTest {

    private final AnthropicProtocolAdapter adapter = new AnthropicProtocolAdapter();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void shouldStreamInputJsonDeltaFromToolCallArgumentDelta() throws Exception {
        AnthropicProtocolAdapter.StreamState state = new AnthropicProtocolAdapter.StreamState();

        // chunk 1: message_start + content_block_start(tool_use)
        UnifiedChatResponse chunk1 = UnifiedChatResponse.builder()
            .id("msg-1")
            .model("claude-3-5-sonnet")
            .object("chat.completion.chunk")
            .created(1700000000L)
            .choices(List.of(UnifiedChoice.builder()
                .index(0)
                .delta(UnifiedDelta.builder()
                    .toolCalls(List.of(UnifiedToolCall.builder()
                        .id("call_abc")
                        .type("function")
                        .function(UnifiedFunctionCall.builder()
                            .name("get_weather")
                            .build())  // arguments 为 null,content_block_start 信号
                        .build()))
                    .build())
                .build()))
            .usage(UnifiedUsage.builder()
                .promptTokens(10).completionTokens(0).totalTokens(10).build())
            .build();

        List<String> events1 = adapter.toStreamEvents(chunk1, state);
        // 应有:message_start, content_block_start
        assertThat(events1).hasSizeGreaterThanOrEqualTo(2);
        String startEvent = events1.stream()
            .filter(e -> e.contains("\"content_block_start\""))
            .findFirst().orElseThrow();
        assertThat(startEvent).contains("\"tool_use\"");
        assertThat(startEvent).contains("\"call_abc\"");
        assertThat(startEvent).contains("\"get_weather\"");
        // 解析 content_block_start 的 index,用于后续验证 delta 归属同一 block
        JsonNode startRoot = mapper.readTree(startEvent);
        int blockIndex = startRoot.path("index").asInt();

        // chunk 2: input_json_delta(真流式,按 index 转发 toolCallArgumentDeltas)
        UnifiedChatResponse chunk2 = UnifiedChatResponse.builder()
            .id("msg-1")
            .model("claude-3-5-sonnet")
            .object("chat.completion.chunk")
            .created(1700000000L)
            .choices(List.of(UnifiedChoice.builder()
                .index(0)
                .delta(UnifiedDelta.builder()
                    .toolCallArgumentDeltas(List.of(
                        new IndexedArgumentDelta(blockIndex, "{\"location\":")
                    ))
                    .build())
                .build()))
            .build();

        List<String> events2 = adapter.toStreamEvents(chunk2, state);
        // 应有:content_block_delta(input_json_delta),partial_json = "{\"location\":"
        String deltaEvent = events2.stream()
            .filter(e -> e.contains("\"input_json_delta\""))
            .findFirst().orElseThrow(() -> new AssertionError("未找到 input_json_delta 事件"));
        JsonNode deltaRoot = mapper.readTree(deltaEvent);
        assertThat(deltaRoot.path("delta").path("partial_json").asText()).isEqualTo("{\"location\":");
        // 验证不是 20 字符切片(原始增量直接转发)
        assertThat(deltaRoot.path("delta").path("partial_json").asText()).hasSize(12);
        // 验证 delta 归到 chunk1 打开的 block(跨 chunk 状态正确)
        assertThat(deltaRoot.path("index").asInt()).isEqualTo(blockIndex);
    }

    @Test
    void shouldFallbackToSliceForNonStreamingBackend() throws Exception {
        // 非流式后端场景:message.toolCalls 含完整 arguments,走 20 字符切片 fallback
        AnthropicProtocolAdapter.StreamState state = new AnthropicProtocolAdapter.StreamState();

        com.fasterxml.jackson.databind.node.ObjectNode fullArgs = mapper.createObjectNode();
        fullArgs.put("location", "Beijing");

        UnifiedChatResponse chunk = UnifiedChatResponse.builder()
            .id("msg-2")
            .model("claude-3-5-sonnet")
            .object("chat.completion.chunk")
            .created(1700000000L)
            .choices(List.of(UnifiedChoice.builder()
                .index(0)
                .message(UnifiedMessage.builder()
                    .role(UnifiedMessage.Role.ASSISTANT)
                    .toolCalls(List.of(UnifiedToolCall.builder()
                        .id("call_def")
                        .type("function")
                        .function(UnifiedFunctionCall.builder()
                            .name("get_weather")
                            .arguments(fullArgs)  // 完整 arguments,非流式后端
                            .build())
                        .build()))
                    .build())
                .finishReason("tool_calls")
                .build()))
            .usage(UnifiedUsage.builder()
                .promptTokens(10).completionTokens(5).totalTokens(15).build())
            .build();

        List<String> events = adapter.toStreamEvents(chunk, state);
        // 应有 content_block_start + content_block_delta(input_json_delta,20 字符切片)
        // {"location":"Beijing"} 序列化共 22 字符,按 20 切分应为 2 片(20+2)
        long inputJsonDeltaCount = events.stream()
            .filter(e -> e.contains("\"input_json_delta\""))
            .count();
        assertThat(inputJsonDeltaCount).isEqualTo(2);
    }

    @Test
    void shouldOutputCacheBreakdownInMessageStartUsage() throws Exception {
        AnthropicProtocolAdapter.StreamState state = new AnthropicProtocolAdapter.StreamState();

        UnifiedChatResponse chunk = UnifiedChatResponse.builder()
            .id("msg-cache-1")
            .model("claude-3-5-sonnet")
            .object("chat.completion.chunk")
            .created(1700000000L)
            .choices(List.of(UnifiedChoice.builder()
                .index(0)
                .delta(UnifiedDelta.builder().content("hi").build())
                .build()))
            .usage(UnifiedUsage.builder()
                .promptTokens(70)        // OResC 已扣减 cached(100-30=70)
                .completionTokens(0)
                .totalTokens(100)
                .cachedTokens(30)
                .cacheCreationTokens(0)
                .reasoningTokens(0)
                .build())
            .build();

        List<String> events = adapter.toStreamEvents(chunk, state);
        String messageStart = events.stream()
            .filter(e -> e.contains("\"message_start\""))
            .findFirst().orElseThrow();

        JsonNode root = mapper.readTree(messageStart);
        JsonNode usage = root.path("message").path("usage");

        // 计费恒等式:input_tokens(70) + cache_read(30) + cache_creation(0) = 100
        assertThat(usage.path("input_tokens").asInt()).isEqualTo(70);
        assertThat(usage.path("cache_read_input_tokens").asInt()).isEqualTo(30);
        assertThat(usage.path("output_tokens").asInt()).isEqualTo(0);
        // cache_creation 为 0 不输出(与非流式一致)
        assertThat(usage.has("cache_creation_input_tokens")).isFalse();
        // reasoning_tokens 即使为 0 也输出
        assertThat(usage.path("reasoning_tokens").asInt()).isEqualTo(0);
    }

    @Test
    void shouldOutputCacheCreationInMessageStartUsage() throws Exception {
        AnthropicProtocolAdapter.StreamState state = new AnthropicProtocolAdapter.StreamState();

        UnifiedChatResponse chunk = UnifiedChatResponse.builder()
            .id("msg-cache-2")
            .model("claude-3-5-sonnet")
            .object("chat.completion.chunk")
            .created(1700000000L)
            .choices(List.of(UnifiedChoice.builder()
                .index(0)
                .delta(UnifiedDelta.builder().content("hi").build())
                .build()))
            .usage(UnifiedUsage.builder()
                .promptTokens(50)
                .completionTokens(0)
                .totalTokens(100)
                .cachedTokens(0)
                .cacheCreationTokens(50)
                .reasoningTokens(10)
                .build())
            .build();

        List<String> events = adapter.toStreamEvents(chunk, state);
        String messageStart = events.stream()
            .filter(e -> e.contains("\"message_start\""))
            .findFirst().orElseThrow();

        JsonNode root = mapper.readTree(messageStart);
        JsonNode usage = root.path("message").path("usage");

        // 计费恒等式:input_tokens(50) + cache_read(0) + cache_creation(50) = 100
        assertThat(usage.path("input_tokens").asInt()).isEqualTo(50);
        assertThat(usage.path("cache_creation_input_tokens").asInt()).isEqualTo(50);
        assertThat(usage.has("cache_read_input_tokens")).isFalse();
        assertThat(usage.path("reasoning_tokens").asInt()).isEqualTo(10);
    }

    @Test
    void shouldOutputBothCacheBucketsAndVerifyIdentity() throws Exception {
        AnthropicProtocolAdapter.StreamState state = new AnthropicProtocolAdapter.StreamState();

        UnifiedChatResponse chunk = UnifiedChatResponse.builder()
            .id("msg-cache-3")
            .model("claude-3-5-sonnet")
            .object("chat.completion.chunk")
            .created(1700000000L)
            .choices(List.of(UnifiedChoice.builder()
                .index(0)
                .delta(UnifiedDelta.builder().content("hi").build())
                .build()))
            .usage(UnifiedUsage.builder()
                .promptTokens(50)
                .completionTokens(0)
                .totalTokens(100)
                .cachedTokens(30)
                .cacheCreationTokens(20)
                .reasoningTokens(5)
                .build())
            .build();

        List<String> events = adapter.toStreamEvents(chunk, state);
        String messageStart = events.stream()
            .filter(e -> e.contains("\"message_start\""))
            .findFirst().orElseThrow();

        JsonNode root = mapper.readTree(messageStart);
        JsonNode usage = root.path("message").path("usage");

        int inputTokens = usage.path("input_tokens").asInt();
        int cacheRead = usage.path("cache_read_input_tokens").asInt();
        int cacheCreation = usage.path("cache_creation_input_tokens").asInt();

        // 双桶共存:cache_read 与 cache_creation 同时输出
        assertThat(cacheRead).isEqualTo(30);
        assertThat(cacheCreation).isEqualTo(20);
        assertThat(inputTokens).isEqualTo(50);
        assertThat(usage.path("reasoning_tokens").asInt()).isEqualTo(5);
        // 计费恒等式显式求和:input + cache_read + cache_creation == 原 promptTokens(100)
        assertThat(inputTokens + cacheRead + cacheCreation).isEqualTo(100);
    }

    @Test
    void shouldNotEmitMessageDeltaImmediatelyOnFinishReason() {
        AnthropicProtocolAdapter.StreamState state = new AnthropicProtocolAdapter.StreamState();

        // chunk 1: 启动 message_start + 内容
        UnifiedChatResponse chunk1 = UnifiedChatResponse.builder()
            .id("msg-delta-1")
            .model("claude-3-5-sonnet")
            .object("chat.completion.chunk")
            .created(1700000000L)
            .choices(List.of(UnifiedChoice.builder()
                .index(0)
                .delta(UnifiedDelta.builder().content("hello").build())
                .build()))
            .usage(UnifiedUsage.builder()
                .promptTokens(10).completionTokens(0).totalTokens(10).build())
            .build();
        adapter.toStreamEvents(chunk1, state);

        // chunk 2: finishReason 出现(output_tokens=5,非最终值)
        UnifiedChatResponse chunk2 = UnifiedChatResponse.builder()
            .id("msg-delta-1")
            .model("claude-3-5-sonnet")
            .object("chat.completion.chunk")
            .created(1700000000L)
            .choices(List.of(UnifiedChoice.builder()
                .index(0)
                .delta(UnifiedDelta.builder().build())
                .finishReason("stop")
                .build()))
            .usage(UnifiedUsage.builder()
                .promptTokens(10).completionTokens(5).totalTokens(15).build())
            .build();

        List<String> events2 = adapter.toStreamEvents(chunk2, state);

        // 验证:finishReason 出现时不立即发 message_delta(延迟到 finalizeStream)
        boolean hasMessageDelta = events2.stream()
            .anyMatch(e -> e.contains("\"message_delta\""));
        assertThat(hasMessageDelta).isFalse();
        boolean hasMessageStop = events2.stream()
            .anyMatch(e -> e.contains("\"message_stop\""));
        assertThat(hasMessageStop).isFalse();
    }

    @Test
    void shouldEmitMessageDeltaInFinalizeStream() {
        AnthropicProtocolAdapter.StreamState state = new AnthropicProtocolAdapter.StreamState();

        // chunk 1: 启动
        UnifiedChatResponse chunk1 = UnifiedChatResponse.builder()
            .id("msg-delta-2")
            .model("claude-3-5-sonnet")
            .object("chat.completion.chunk")
            .created(1700000000L)
            .choices(List.of(UnifiedChoice.builder()
                .index(0)
                .delta(UnifiedDelta.builder().content("hi").build())
                .build()))
            .usage(UnifiedUsage.builder()
                .promptTokens(10).completionTokens(0).totalTokens(10).build())
            .build();
        adapter.toStreamEvents(chunk1, state);

        // chunk 2: finishReason(output_tokens=5,中间值)
        UnifiedChatResponse chunk2 = UnifiedChatResponse.builder()
            .id("msg-delta-2")
            .model("claude-3-5-sonnet")
            .object("chat.completion.chunk")
            .created(1700000000L)
            .choices(List.of(UnifiedChoice.builder()
                .index(0)
                .delta(UnifiedDelta.builder().build())
                .finishReason("stop")
                .build()))
            .usage(UnifiedUsage.builder()
                .promptTokens(10).completionTokens(5).totalTokens(15).build())
            .build();
        adapter.toStreamEvents(chunk2, state);

        // chunk 3: usage 更新(output_tokens=50,最终值,无 finishReason)
        UnifiedChatResponse chunk3 = UnifiedChatResponse.builder()
            .id("msg-delta-2")
            .model("claude-3-5-sonnet")
            .object("chat.completion.chunk")
            .created(1700000000L)
            .choices(List.of())
            .usage(UnifiedUsage.builder()
                .promptTokens(10).completionTokens(50).totalTokens(60).build())
            .build();
        adapter.toStreamEvents(chunk3, state);

        // finalizeStream:发 message_delta + message_stop
        List<String> finalEvents = adapter.finalizeStream(state);

        assertThat(finalEvents).hasSize(2);
        assertThat(finalEvents.get(0)).contains("\"message_delta\"");
        assertThat(finalEvents.get(0)).contains("\"stop_reason\":\"end_turn\"");
        // output_tokens 应为最终值 50(非中间值 5)
        assertThat(finalEvents.get(0)).contains("\"output_tokens\":50");
        assertThat(finalEvents.get(1)).contains("\"message_stop\"");
    }

    @Test
    void shouldDeduplicateMessageDeltaWhenMultipleFinishReasons() {
        AnthropicProtocolAdapter.StreamState state = new AnthropicProtocolAdapter.StreamState();

        // chunk 1: 启动
        UnifiedChatResponse chunk1 = UnifiedChatResponse.builder()
            .id("msg-delta-3")
            .model("claude-3-5-sonnet")
            .object("chat.completion.chunk")
            .created(1700000000L)
            .choices(List.of(UnifiedChoice.builder()
                .index(0)
                .delta(UnifiedDelta.builder().content("hi").build())
                .build()))
            .usage(UnifiedUsage.builder()
                .promptTokens(10).completionTokens(0).totalTokens(10).build())
            .build();
        adapter.toStreamEvents(chunk1, state);

        // chunk 2: 第一个 finishReason(OpenRouter 场景,usage 不完整)
        UnifiedChatResponse chunk2 = UnifiedChatResponse.builder()
            .id("msg-delta-3")
            .model("claude-3-5-sonnet")
            .object("chat.completion.chunk")
            .created(1700000000L)
            .choices(List.of(UnifiedChoice.builder()
                .index(0)
                .delta(UnifiedDelta.builder().build())
                .finishReason("stop")
                .build()))
            .usage(UnifiedUsage.builder()
                .promptTokens(10).completionTokens(5).totalTokens(15).build())
            .build();
        adapter.toStreamEvents(chunk2, state);

        // chunk 3: 第二个 finishReason(OpenRouter 场景,usage 完整)
        UnifiedChatResponse chunk3 = UnifiedChatResponse.builder()
            .id("msg-delta-3")
            .model("claude-3-5-sonnet")
            .object("chat.completion.chunk")
            .created(1700000000L)
            .choices(List.of(UnifiedChoice.builder()
                .index(0)
                .delta(UnifiedDelta.builder().build())
                .finishReason("stop")
                .build()))
            .usage(UnifiedUsage.builder()
                .promptTokens(10).completionTokens(50).totalTokens(60).build())
            .build();
        adapter.toStreamEvents(chunk3, state);

        // finalizeStream:只发一次 message_delta(去重)
        List<String> finalEvents = adapter.finalizeStream(state);

        long messageDeltaCount = finalEvents.stream()
            .filter(e -> e.contains("\"message_delta\""))
            .count();
        assertThat(messageDeltaCount).isEqualTo(1);
        // output_tokens 应为最终值 50
        assertThat(finalEvents.stream()
            .filter(e -> e.contains("\"message_delta\""))
            .findFirst().orElseThrow()).contains("\"output_tokens\":50");
    }

    @Test
    void shouldRouteInputJsonDeltaByIndexNotCurrentBlock() throws Exception {
        AnthropicProtocolAdapter.StreamState state = new AnthropicProtocolAdapter.StreamState();

        // chunk1: content_block_start index=0, tool_use, id=call_1, name=get_weather
        UnifiedChatResponse chunk1 = UnifiedChatResponse.builder()
            .id("msg-route").model("claude-3-5-sonnet").object("chat.completion.chunk").created(1700000000L)
            .choices(List.of(UnifiedChoice.builder()
                .index(0)
                .delta(UnifiedDelta.builder()
                    .toolCalls(List.of(UnifiedToolCall.builder()
                        .index(0).id("call_1").type("function")
                        .function(UnifiedFunctionCall.builder().name("get_weather").build())
                        .build()))
                    .build())
                .build()))
            .usage(UnifiedUsage.builder().promptTokens(10).completionTokens(0).totalTokens(10).build())
            .build();
        adapter.toStreamEvents(chunk1, state);

        // chunk2: content_block_start index=1, tool_use, id=call_2, name=get_time
        UnifiedChatResponse chunk2 = UnifiedChatResponse.builder()
            .id("msg-route").model("claude-3-5-sonnet").object("chat.completion.chunk").created(1700000000L)
            .choices(List.of(UnifiedChoice.builder()
                .index(0)
                .delta(UnifiedDelta.builder()
                    .toolCalls(List.of(UnifiedToolCall.builder()
                        .index(1).id("call_2").type("function")
                        .function(UnifiedFunctionCall.builder().name("get_time").build())
                        .build()))
                    .build())
                .build()))
            .build();
        adapter.toStreamEvents(chunk2, state);

        // chunk3: argument delta index=0 + index=1(同 chunk 多 tc,验证按 index 路由而非 currentBlockIndex)
        UnifiedChatResponse chunk3 = UnifiedChatResponse.builder()
            .id("msg-route").model("claude-3-5-sonnet").object("chat.completion.chunk").created(1700000000L)
            .choices(List.of(UnifiedChoice.builder()
                .index(0)
                .delta(UnifiedDelta.builder()
                    .toolCallArgumentDeltas(List.of(
                        new IndexedArgumentDelta(0, "{\"city\":"),
                        new IndexedArgumentDelta(1, "{\"tz\":")
                    ))
                    .build())
                .build()))
            .build();
        List<String> events3 = adapter.toStreamEvents(chunk3, state);

        // 验证:产出 2 个 content_block_delta,index 分别为 0 和 1(按 delta 携带的 index 路由,非 currentBlockIndex)
        List<String> inputJsonDeltas = events3.stream()
            .filter(e -> e.contains("\"input_json_delta\""))
            .toList();
        assertThat(inputJsonDeltas).hasSize(2);
        assertThat(inputJsonDeltas.get(0)).contains("\"index\":0");
        assertThat(inputJsonDeltas.get(0)).contains("\"partial_json\":\"{\\\"city\\\":");
        assertThat(inputJsonDeltas.get(1)).contains("\"index\":1");
        assertThat(inputJsonDeltas.get(1)).contains("\"partial_json\":\"{\\\"tz\\\":");
    }
}
