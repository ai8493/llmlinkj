package com.ai8493.llmproxy.adapter.gemini;

import com.ai8493.llmproxy.model.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class GeminiProtocolAdapterContextTest {

    private final GeminiProtocolAdapter adapter = new GeminiProtocolAdapter();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void shouldAccumulateToolCallArgumentDeltaWithContextAcrossChunks() throws Exception {
        GeminiRequestContext ctx = new GeminiRequestContext("test-session");

        // chunk1: toolCalls 含 id + name(arguments=null,content_block_start 信号)
        UnifiedChatResponse chunk1 = UnifiedChatResponse.builder()
            .id("msg-1").model("gpt-4").object("chat.completion.chunk").created(1700000000L)
            .choices(List.of(UnifiedChoice.builder()
                .index(0)
                .delta(UnifiedDelta.builder()
                    .toolCalls(List.of(UnifiedToolCall.builder()
                        .index(0).id("call_abc").type("function")
                        .function(UnifiedFunctionCall.builder().name("get_weather").build())
                        .build()))
                    .build())
                .build()))
            .build();
        adapter.fromUnifiedStreamChunk(chunk1, ctx);
        assertThat(ctx.toolCallAccs()).hasSize(1);
        assertThat(ctx.toolCallAccs().get(0).id).isEqualTo("call_abc");
        assertThat(ctx.toolCallAccs().get(0).fnName).isEqualTo("get_weather");

        // chunk2: toolCallArgumentDeltas 增量(按 index=0 累积)
        UnifiedChatResponse chunk2 = UnifiedChatResponse.builder()
            .id("msg-1").model("gpt-4").object("chat.completion.chunk").created(1700000000L)
            .choices(List.of(UnifiedChoice.builder()
                .index(0)
                .delta(UnifiedDelta.builder()
                    .toolCallArgumentDeltas(List.of(
                        new IndexedArgumentDelta(0, "{\"location\":\"Beijing\"}")
                    ))
                    .build())
                .build()))
            .build();
        adapter.fromUnifiedStreamChunk(chunk2, ctx);
        assertThat(ctx.toolCallAccs().get(0).argsBuilder.toString())
            .isEqualTo("{\"location\":\"Beijing\"}");

        // chunk3: finishReason -> 组装 functionCall + reset acc
        UnifiedChatResponse chunk3 = UnifiedChatResponse.builder()
            .id("msg-1").model("gpt-4").object("chat.completion.chunk").created(1700000000L)
            .choices(List.of(UnifiedChoice.builder()
                .index(0)
                .finishReason("tool_calls")
                .build()))
            .build();
        String sse = adapter.fromUnifiedStreamChunk(chunk3, ctx);

        JsonNode sseJson = mapper.readTree(sse);
        JsonNode fc = sseJson.get("candidates").get(0)
            .get("content").get("parts").get(0).get("functionCall");
        assertThat(fc.get("name").asText()).isEqualTo("get_weather");
        assertThat(fc.get("id").asText()).isEqualTo("call_abc");
        assertThat(fc.get("args").get("location").asText()).isEqualTo("Beijing");
        assertThat(ctx.toolCallAccs().get(0).fnName).isNull();
        assertThat(ctx.toolCallAccs().get(0).argsBuilder).isEmpty();
    }

    @Test
    void shouldAccumulateMultipleToolCallsByIndex() throws Exception {
        GeminiRequestContext ctx = new GeminiRequestContext("test-session");

        UnifiedChatResponse chunk1 = UnifiedChatResponse.builder()
            .id("msg-2").model("gpt-4").object("chat.completion.chunk").created(1700000000L)
            .choices(List.of(UnifiedChoice.builder()
                .index(0)
                .delta(UnifiedDelta.builder()
                    .toolCalls(List.of(UnifiedToolCall.builder()
                        .index(0).id("call_1").type("function")
                        .function(UnifiedFunctionCall.builder().name("get_weather").build())
                        .build()))
                    .build())
                .build()))
            .build();
        adapter.fromUnifiedStreamChunk(chunk1, ctx);

        UnifiedChatResponse chunk2 = UnifiedChatResponse.builder()
            .id("msg-2").model("gpt-4").object("chat.completion.chunk").created(1700000000L)
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
        adapter.fromUnifiedStreamChunk(chunk2, ctx);

        assertThat(ctx.toolCallAccs()).hasSize(2);
        assertThat(ctx.toolCallAccs().get(0).id).isEqualTo("call_1");
        assertThat(ctx.toolCallAccs().get(0).fnName).isEqualTo("get_weather");
        assertThat(ctx.toolCallAccs().get(1).id).isEqualTo("call_2");
        assertThat(ctx.toolCallAccs().get(1).fnName).isEqualTo("get_time");
    }

    @Test
    void shouldRouteMultipleToolCallsByIndexFromOpenAiBackend() throws Exception {
        // 模拟 OpenAI 后端流式:同流 2 个 toolCall,index=0 get_weather + index=1 get_time
        // 验证 Gemini 出站累积 ctx 中 2 个 acc 互不覆盖
        // 注意:此测试手动设 .index(),不验证生产链路修复(OpenAiStreamingResponseConverter 传 index)
        // 仅验证 GeminiProtocolAdapter 侧的 Map<Integer,ToolCallAcc> 累积 + args delta 归属边界
        GeminiRequestContext ctx = new GeminiRequestContext("test-session");

        // chunk1: OpenAI 后端 toolCall index=0, id=call_1, name=get_weather
        UnifiedChatResponse chunk1 = UnifiedChatResponse.builder()
            .id("msg-multi").model("gpt-4").object("chat.completion.chunk").created(1700000000L)
            .choices(List.of(UnifiedChoice.builder()
                .index(0)
                .delta(UnifiedDelta.builder()
                    .toolCalls(List.of(UnifiedToolCall.builder()
                        .index(0).id("call_1").type("function")
                        .function(UnifiedFunctionCall.builder().name("get_weather").build())
                        .build()))
                    .build())
                .build()))
            .build();
        adapter.fromUnifiedStreamChunk(chunk1, ctx);

        // chunk2: OpenAI 后端 toolCall index=1, id=call_2, name=get_time
        UnifiedChatResponse chunk2 = UnifiedChatResponse.builder()
            .id("msg-multi").model("gpt-4").object("chat.completion.chunk").created(1700000000L)
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
        adapter.fromUnifiedStreamChunk(chunk2, ctx);

        // 验证:ctx 累积了 2 个 acc,id/name 互不覆盖
        assertThat(ctx.toolCallAccs()).hasSize(2);
        assertThat(ctx.toolCallAccs().get(0).id).isEqualTo("call_1");
        assertThat(ctx.toolCallAccs().get(0).fnName).isEqualTo("get_weather");
        assertThat(ctx.toolCallAccs().get(1).id).isEqualTo("call_2");
        assertThat(ctx.toolCallAccs().get(1).fnName).isEqualTo("get_time");

        // chunk3: arguments 增量(按 index=1 累积到对应 acc)
        UnifiedChatResponse chunk3 = UnifiedChatResponse.builder()
            .id("msg-multi").model("gpt-4").object("chat.completion.chunk").created(1700000000L)
            .choices(List.of(UnifiedChoice.builder()
                .index(0)
                .delta(UnifiedDelta.builder()
                    .toolCallArgumentDeltas(List.of(
                        new IndexedArgumentDelta(1, "{\"city\":\"Beijing\"}")
                    ))
                    .build())
                .build()))
            .build();
        adapter.fromUnifiedStreamChunk(chunk3, ctx);

        // 验证:args delta 按 index=1 严格归属,index=0 的 acc 不受影响
        assertThat(ctx.toolCallAccs().get(0).argsBuilder).isEmpty();
        assertThat(ctx.toolCallAccs().get(1).argsBuilder.toString())
            .isEqualTo("{\"city\":\"Beijing\"}");
    }

    @Test
    void shouldFallbackToNullContextWithoutError() throws Exception {        UnifiedChatResponse chunk = UnifiedChatResponse.builder()
            .id("msg-3").model("gpt-4").object("chat.completion.chunk").created(1700000000L)
            .choices(List.of(UnifiedChoice.builder()
                .index(0)
                .delta(UnifiedDelta.builder().content("hi").build())
                .build()))
            .build();
        String sse = adapter.fromUnifiedStreamChunk(chunk);
        assertThat(sse).isNotNull();
        JsonNode root = new ObjectMapper().readTree(sse);
        assertThat(root.path("candidates").get(0)
            .path("content").path("parts").get(0).path("text").asText()).isEqualTo("hi");
    }

    @Test
    void shouldRouteArgumentDeltaByIndexNotLastAcc() throws Exception {
        GeminiRequestContext ctx = new GeminiRequestContext("test-route");

        // chunk1: start index=0, id=call_1, name=get_weather
        UnifiedChatResponse chunk1 = UnifiedChatResponse.builder()
            .id("msg-route").model("gpt-4").object("chat.completion.chunk").created(1700000000L)
            .choices(List.of(UnifiedChoice.builder()
                .index(0)
                .delta(UnifiedDelta.builder()
                    .toolCalls(List.of(UnifiedToolCall.builder()
                        .index(0).id("call_1").type("function")
                        .function(UnifiedFunctionCall.builder().name("get_weather").build())
                        .build()))
                    .build())
                .build()))
            .build();
        adapter.fromUnifiedStreamChunk(chunk1, ctx);

        // chunk2: start index=1, id=call_2, name=get_time
        UnifiedChatResponse chunk2 = UnifiedChatResponse.builder()
            .id("msg-route").model("gpt-4").object("chat.completion.chunk").created(1700000000L)
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
        adapter.fromUnifiedStreamChunk(chunk2, ctx);

        // chunk3: argument delta index=0, '{"city":"Beijing"}'
        UnifiedChatResponse chunk3 = UnifiedChatResponse.builder()
            .id("msg-route").model("gpt-4").object("chat.completion.chunk").created(1700000000L)
            .choices(List.of(UnifiedChoice.builder()
                .index(0)
                .delta(UnifiedDelta.builder()
                    .toolCallArgumentDeltas(List.of(
                        new IndexedArgumentDelta(0, "{\"city\":\"Beijing\"}")
                    ))
                    .build())
                .build()))
            .build();
        adapter.fromUnifiedStreamChunk(chunk3, ctx);

        // chunk4: argument delta index=1, '{"tz":"UTC"}'
        UnifiedChatResponse chunk4 = UnifiedChatResponse.builder()
            .id("msg-route").model("gpt-4").object("chat.completion.chunk").created(1700000000L)
            .choices(List.of(UnifiedChoice.builder()
                .index(0)
                .delta(UnifiedDelta.builder()
                    .toolCallArgumentDeltas(List.of(
                        new IndexedArgumentDelta(1, "{\"tz\":\"UTC\"}")
                    ))
                    .build())
                .build()))
            .build();
        adapter.fromUnifiedStreamChunk(chunk4, ctx);

        // 验证:args 按 index 严格归属,不混淆
        assertThat(ctx.toolCallAccs().get(0).argsBuilder.toString()).isEqualTo("{\"city\":\"Beijing\"}");
        assertThat(ctx.toolCallAccs().get(1).argsBuilder.toString()).isEqualTo("{\"tz\":\"UTC\"}");
    }
}
