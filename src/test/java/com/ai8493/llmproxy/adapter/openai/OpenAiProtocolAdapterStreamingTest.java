package com.ai8493.llmproxy.adapter.openai;

import com.ai8493.llmproxy.model.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class OpenAiProtocolAdapterStreamingTest {

    private final OpenAiProtocolAdapter adapter = new OpenAiProtocolAdapter();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void shouldConsumeToolCallArgumentDeltasAsDeltaToolCallWithIndex() throws Exception {
        // IR chunk: toolCallArgumentDeltas 含 2 个增量(index=0 + index=1)
        UnifiedChatResponse chunk = UnifiedChatResponse.builder()
            .id("msg-1").model("gpt-4").object("chat.completion.chunk").created(1700000000L)
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

        String sse = adapter.fromUnifiedStreamChunk(chunk);
        JsonNode root = mapper.readTree(sse);
        JsonNode toolCalls = root.path("choices").get(0).path("delta").path("tool_calls");

        // 验证:转成 2 个 Delta.ToolCall,各自带 index + arguments
        assertThat(toolCalls).hasSize(2);
        assertThat(toolCalls.get(0).path("index").asInt()).isEqualTo(0);
        assertThat(toolCalls.get(0).path("function").path("arguments").asText()).isEqualTo("{\"city\":");
        assertThat(toolCalls.get(1).path("index").asInt()).isEqualTo(1);
        assertThat(toolCalls.get(1).path("function").path("arguments").asText()).isEqualTo("{\"tz\":");
    }

    @Test
    void shouldMergeToolCallsAndArgumentDeltas() throws Exception {
        // IR chunk: toolCalls(start 信号,index=0,id+name) + toolCallArgumentDeltas(index=1,args)
        UnifiedChatResponse chunk = UnifiedChatResponse.builder()
            .id("msg-2").model("gpt-4").object("chat.completion.chunk").created(1700000000L)
            .choices(List.of(UnifiedChoice.builder()
                .index(0)
                .delta(UnifiedDelta.builder()
                    .toolCalls(List.of(UnifiedToolCall.builder()
                        .index(0).id("call_1").type("function")
                        .function(UnifiedFunctionCall.builder().name("get_weather").build())
                        .build()))
                    .toolCallArgumentDeltas(List.of(
                        new IndexedArgumentDelta(1, "{\"tz\":\"UTC\"}")
                    ))
                    .build())
                .build()))
            .build();

        String sse = adapter.fromUnifiedStreamChunk(chunk);
        JsonNode root = mapper.readTree(sse);
        JsonNode toolCalls = root.path("choices").get(0).path("delta").path("tool_calls");

        // 验证:toolCalls(start) + argumentDeltas 合并输出,共 2 个
        assertThat(toolCalls).hasSize(2);
        // index=0:start 信号(id+name)
        assertThat(toolCalls.get(0).path("index").asInt()).isEqualTo(0);
        assertThat(toolCalls.get(0).path("id").asText()).isEqualTo("call_1");
        assertThat(toolCalls.get(0).path("function").path("name").asText()).isEqualTo("get_weather");
        // index=1:argument delta(无 id/name,只有 arguments)
        assertThat(toolCalls.get(1).path("index").asInt()).isEqualTo(1);
        assertThat(toolCalls.get(1).path("function").path("arguments").asText()).isEqualTo("{\"tz\":\"UTC\"}");
    }

    @Test
    void shouldNotEmitToolCallsWhenArgumentDeltasNull() throws Exception {
        UnifiedChatResponse chunk = UnifiedChatResponse.builder()
            .id("msg-3").model("gpt-4").object("chat.completion.chunk").created(1700000000L)
            .choices(List.of(UnifiedChoice.builder()
                .index(0)
                .delta(UnifiedDelta.builder().content("hi").build())
                .build()))
            .build();

        String sse = adapter.fromUnifiedStreamChunk(chunk);
        JsonNode root = mapper.readTree(sse);
        // 无 toolCalls 字段(content chunk)
        assertThat(root.path("choices").get(0).path("delta").has("tool_calls")).isFalse();
    }

    @Test
    void shouldPreserveToolCallIndexFromStartSignal() throws Exception {
        // IR chunk: 单个 start 信号 toolCall,index=1(第二个 toolCall 的 start,在单独 chunk)
        // 验证:输出的 Delta.ToolCall index=1,不是循环 i=0
        UnifiedChatResponse chunk = UnifiedChatResponse.builder()
            .id("msg-4").model("gpt-4").object("chat.completion.chunk").created(1700000000L)
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

        String sse = adapter.fromUnifiedStreamChunk(chunk);
        JsonNode root = mapper.readTree(sse);
        JsonNode toolCalls = root.path("choices").get(0).path("delta").path("tool_calls");

        assertThat(toolCalls).hasSize(1);
        assertThat(toolCalls.get(0).path("index").asInt()).isEqualTo(1);
        assertThat(toolCalls.get(0).path("id").asText()).isEqualTo("call_2");
        assertThat(toolCalls.get(0).path("function").path("name").asText()).isEqualTo("get_time");
    }

    @Test
    void shouldSerializeUsageInStreamChunk() throws Exception {
        var adapter = new OpenAiProtocolAdapter();
        var chunk = UnifiedChatResponse.builder()
            .id("chatcmpl-1")
            .model("gpt-4o")
            .created(100L)
            .choices(List.of(UnifiedChoice.builder()
                .index(0)
                .delta(UnifiedDelta.builder().build())
                .finishReason("stop")
                .build()))
            .usage(UnifiedUsage.builder()
                .promptTokens(100)
                .completionTokens(50)
                .totalTokens(150)
                .cachedTokens(20)
                .reasoningTokens(10)
                .build())
            .build();

        String out = adapter.fromUnifiedStreamChunk(chunk);
        var json = new ObjectMapper().readTree(out);
        var usage = json.path("usage");
        assertThat(usage.path("prompt_tokens").asInt()).isEqualTo(100);
        assertThat(usage.path("prompt_tokens_details").path("cached_tokens").asInt()).isEqualTo(20);
        assertThat(usage.path("completion_tokens_details").path("reasoning_tokens").asInt()).isEqualTo(10);
    }

    @Test
    void shouldSerializeReasoningContentInStreamChunk() throws Exception {
        var adapter = new OpenAiProtocolAdapter();
        var chunk = UnifiedChatResponse.builder()
            .id("chatcmpl-1")
            .model("gpt-4o")
            .created(100L)
            .choices(List.of(UnifiedChoice.builder()
                .index(0)
                .delta(UnifiedDelta.builder()
                    .reasoningContent("思考增量")
                    .build())
                .build()))
            .build();

        String out = adapter.fromUnifiedStreamChunk(chunk);
        var json = new ObjectMapper().readTree(out);
        String rc = json.path("choices").get(0).path("delta").path("reasoning_content").asText("");
        assertThat(rc).isEqualTo("思考增量");
    }
}
