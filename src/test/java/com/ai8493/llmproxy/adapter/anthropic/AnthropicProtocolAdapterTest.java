package com.ai8493.llmproxy.adapter.anthropic;

import com.ai8493.llmproxy.exception.BackendApiException;
import com.ai8493.llmproxy.model.UnifiedChatRequest;
import com.ai8493.llmproxy.model.UnifiedChatResponse;
import com.ai8493.llmproxy.model.UnifiedChoice;
import com.ai8493.llmproxy.model.UnifiedDelta;
import com.ai8493.llmproxy.model.UnifiedFunctionCall;
import com.ai8493.llmproxy.model.UnifiedMessage;
import com.ai8493.llmproxy.model.UnifiedToolCall;
import com.ai8493.llmproxy.model.UnifiedToolChoice;
import com.ai8493.llmproxy.model.UnifiedUsage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class AnthropicProtocolAdapterTest {

    private final AnthropicProtocolAdapter adapter = new AnthropicProtocolAdapter();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void errorStreamEvent_应返回Anthropic风格JSON() throws Exception {
        BackendApiException e = new BackendApiException("deepseek-openai", 429, "rate limited");
        String json = adapter.errorStreamEvent(e);

        JsonNode root = mapper.readTree(json);
        assertThat(root.get("type").asText()).isEqualTo("error");
        assertThat(root.get("error").get("type").asText()).isEqualTo("rate_limit_error");
        assertThat(root.get("error").get("message").asText()).isEqualTo("rate limited");
    }

    @Test
    void errorStreamEvent_非BackendApiException应返回502_api_error() throws Exception {
        Exception e = new RuntimeException("连接后端失败");
        String json = adapter.errorStreamEvent(e);

        JsonNode root = mapper.readTree(json);
        assertThat(root.get("type").asText()).isEqualTo("error");
        assertThat(root.get("error").get("type").asText()).isEqualTo("api_error");
        assertThat(root.get("error").get("message").asText()).isEqualTo("连接后端失败");
    }

    @Test
    void errorResponse_应返回Anthropic风格JSON字节() throws Exception {
        BackendApiException e = new BackendApiException("deepseek-openai", 400, "bad request");
        byte[] bytes = adapter.errorResponse(e);

        JsonNode root = mapper.readTree(bytes);
        assertThat(root.get("type").asText()).isEqualTo("error");
        assertThat(root.get("error").get("type").asText()).isEqualTo("invalid_request_error");
        assertThat(root.get("error").get("message").asText()).isEqualTo("bad request");
    }

    @Test
    void errorResponse_非BackendApiException应返回502_api_error() throws Exception {
        Exception e = new RuntimeException("连接后端失败");
        byte[] bytes = adapter.errorResponse(e);

        JsonNode root = mapper.readTree(bytes);
        assertThat(root.get("type").asText()).isEqualTo("error");
        assertThat(root.get("error").get("type").asText()).isEqualTo("api_error");
        assertThat(root.get("error").get("message").asText()).isEqualTo("连接后端失败");
    }

    @Test
    void errorStatusCode_应返回BackendApiException的statusCode() {
        BackendApiException e = new BackendApiException("deepseek-openai", 429, "rate limited");
        assertThat(adapter.errorStatusCode(e)).isEqualTo(429);
    }

    @Test
    void errorStatusCode_非BackendApiException应返回502() {
        Exception e = new RuntimeException("连接失败");
        assertThat(adapter.errorStatusCode(e)).isEqualTo(502);
    }

    @Test
    void protocolName_应返回anthropic() {
        assertThat(adapter.protocolName()).isEqualTo("anthropic");
    }

    @Test
    void toUnifiedRequest_纯文本messages应正确解析() throws Exception {
        String json = """
            {
              "model": "claude-sonnet-4-5",
              "max_tokens": 1024,
              "messages": [
                {"role": "user", "content": "你好"},
                {"role": "assistant", "content": "你好,有什么可以帮你?"}
              ]
            }
            """;
        UnifiedChatRequest req = adapter.toUnifiedRequest(json.getBytes(StandardCharsets.UTF_8), null);

        assertThat(req.model()).isEqualTo("claude-sonnet-4-5");
        assertThat(req.messages()).hasSize(2);
        assertThat(req.messages().get(0).role()).isEqualTo(UnifiedMessage.Role.USER);
        assertThat(req.messages().get(0).content()).isEqualTo("你好");
        assertThat(req.messages().get(1).role()).isEqualTo(UnifiedMessage.Role.ASSISTANT);
        assertThat(req.messages().get(1).content()).isEqualTo("你好,有什么可以帮你?");
        assertThat(req.config().maxOutputTokens()).isEqualTo(1024);
    }

    @Test
    void toUnifiedRequest_system字符串应插入SYSTEM消息() throws Exception {
        String json = """
            {
              "model": "claude-sonnet-4-5",
              "max_tokens": 1024,
              "system": "你是助手",
              "messages": [{"role": "user", "content": "你好"}]
            }
            """;
        UnifiedChatRequest req = adapter.toUnifiedRequest(json.getBytes(StandardCharsets.UTF_8), null);

        assertThat(req.messages()).hasSize(2);
        assertThat(req.messages().get(0).role()).isEqualTo(UnifiedMessage.Role.SYSTEM);
        assertThat(req.messages().get(0).content()).isEqualTo("你是助手");
        assertThat(req.messages().get(1).role()).isEqualTo(UnifiedMessage.Role.USER);
    }

    @Test
    void toUnifiedRequest_contentBlocks混合应正确解析() throws Exception {
        String json = """
            {
              "model": "claude-sonnet-4-5",
              "max_tokens": 1024,
              "messages": [
                {
                  "role": "user",
                  "content": [
                    {"type": "text", "text": "帮我查天气"},
                    {"type": "tool_result", "tool_use_id": "tool_123", "content": "北京 25 度"}
                  ]
                },
                {
                  "role": "assistant",
                  "content": [
                    {"type": "thinking", "thinking": "用户要查天气", "signature": "sig_abc"},
                    {"type": "text", "text": "好的"},
                    {"type": "tool_use", "id": "tool_123", "name": "get_weather", "input": {"city": "北京"}}
                  ]
                }
              ]
            }
            """;
        UnifiedChatRequest req = adapter.toUnifiedRequest(json.getBytes(StandardCharsets.UTF_8), null);

        // user 消息:text + tool_result(tool_result 拆为独立 TOOL 消息)
        assertThat(req.messages().get(0).role()).isEqualTo(UnifiedMessage.Role.USER);
        assertThat(req.messages().get(0).content()).isEqualTo("帮我查天气");

        // tool_result 拆为独立 TOOL 消息
        assertThat(req.messages().get(1).role()).isEqualTo(UnifiedMessage.Role.TOOL);
        assertThat(req.messages().get(1).toolCallId()).isEqualTo("tool_123");
        assertThat(req.messages().get(1).content()).isEqualTo("北京 25 度");

        // assistant 消息:thinking + text + tool_use
        UnifiedMessage assistant = req.messages().get(2);
        assertThat(assistant.role()).isEqualTo(UnifiedMessage.Role.ASSISTANT);
        assertThat(assistant.reasoningContent()).isEqualTo("用户要查天气");
        assertThat(assistant.thinkingSignature()).isEqualTo("sig_abc");
        assertThat(assistant.content()).isEqualTo("好的");
        assertThat(assistant.toolCalls()).hasSize(1);
        assertThat(assistant.toolCalls().get(0).id()).isEqualTo("tool_123");
        assertThat(assistant.toolCalls().get(0).function().name()).isEqualTo("get_weather");
        assertThat(assistant.toolCalls().get(0).function().arguments().get("city").asText()).isEqualTo("北京");
    }

    @Test
    void toUnifiedRequest_tools和tool_choice应正确解析() throws Exception {
        String json = """
            {
              "model": "claude-sonnet-4-5",
              "max_tokens": 1024,
              "messages": [{"role": "user", "content": "查天气"}],
              "tools": [
                {
                  "name": "get_weather",
                  "description": "查询天气",
                  "input_schema": {"type": "object", "properties": {"city": {"type": "string"}}}
                }
              ],
              "tool_choice": {"type": "tool", "name": "get_weather"},
              "temperature": 0.7,
              "top_p": 0.9,
              "stop_sequences": ["\\n\\n"],
              "stream": true
            }
            """;
        UnifiedChatRequest req = adapter.toUnifiedRequest(json.getBytes(StandardCharsets.UTF_8), null);

        assertThat(req.tools()).hasSize(1);
        assertThat(req.tools().get(0).type()).isEqualTo("function");
        assertThat(req.tools().get(0).function().name()).isEqualTo("get_weather");
        assertThat(req.tools().get(0).function().description()).isEqualTo("查询天气");
        assertThat(req.toolChoice()).isInstanceOf(UnifiedToolChoice.Required.class);
        assertThat(((UnifiedToolChoice.Required) req.toolChoice()).functionName()).isEqualTo("get_weather");
        assertThat(req.config().temperature()).isEqualTo(0.7);
        assertThat(req.config().topP()).isEqualTo(0.9);
        assertThat(req.config().stopSequences()).containsExactly("\n\n");
        assertThat(req.stream()).isTrue();
    }

    @Test
    void fromUnifiedResponse_纯文本响应应正确序列化() throws Exception {
        UnifiedChatResponse resp = UnifiedChatResponse.builder()
            .id("msg_001")
            .model("claude-sonnet-4-5")
            .object("message")
            .created(1700000000L)
            .choices(java.util.List.of(UnifiedChoice.builder()
                .index(0)
                .message(UnifiedMessage.builder()
                    .role(UnifiedMessage.Role.ASSISTANT)
                    .content("你好")
                    .build())
                .finishReason("stop")
                .build()))
            .usage(UnifiedUsage.builder()
                .promptTokens(10)
                .completionTokens(5)
                .totalTokens(15)
                .build())
            .build();

        byte[] bytes = adapter.fromUnifiedResponse(resp);
        JsonNode root = mapper.readTree(bytes);

        assertThat(root.get("id").asText()).isEqualTo("msg_001");
        assertThat(root.get("type").asText()).isEqualTo("message");
        assertThat(root.get("role").asText()).isEqualTo("assistant");
        assertThat(root.get("model").asText()).isEqualTo("claude-sonnet-4-5");
        assertThat(root.get("stop_reason").asText()).isEqualTo("end_turn");
        assertThat(root.has("stop_sequence")).isTrue();
        assertThat(root.get("stop_sequence").isNull()).isTrue();
        assertThat(root.get("content").isArray()).isTrue();
        assertThat(root.get("content").get(0).get("type").asText()).isEqualTo("text");
        assertThat(root.get("content").get(0).get("text").asText()).isEqualTo("你好");
        assertThat(root.get("usage").get("input_tokens").asInt()).isEqualTo(10);
        assertThat(root.get("usage").get("output_tokens").asInt()).isEqualTo(5);
    }

    @Test
    void fromUnifiedResponse_thinking和tool_use应正确序列化() throws Exception {
        UnifiedChatResponse resp = UnifiedChatResponse.builder()
            .id("msg_002")
            .model("claude-sonnet-4-5")
            .choices(java.util.List.of(UnifiedChoice.builder()
                .index(0)
                .message(UnifiedMessage.builder()
                    .role(UnifiedMessage.Role.ASSISTANT)
                    .reasoningContent("思考中")
                    .thinkingSignature("sig_xyz")
                    .content("调用工具")
                    .toolCalls(java.util.List.of(UnifiedToolCall.builder()
                        .id("tool_456")
                        .type("function")
                        .function(UnifiedFunctionCall.builder()
                            .name("get_weather")
                            .arguments(mapper.readTree("{\"city\":\"北京\"}"))
                            .build())
                        .build()))
                    .build())
                .finishReason("tool_calls")
                .build()))
            .usage(UnifiedUsage.builder().promptTokens(20).completionTokens(10).totalTokens(30).build())
            .build();

        byte[] bytes = adapter.fromUnifiedResponse(resp);
        JsonNode root = mapper.readTree(bytes);

        assertThat(root.get("stop_reason").asText()).isEqualTo("tool_use");
        assertThat(root.get("content")).hasSize(3);
        // thinking 块
        assertThat(root.get("content").get(0).get("type").asText()).isEqualTo("thinking");
        assertThat(root.get("content").get(0).get("thinking").asText()).isEqualTo("思考中");
        assertThat(root.get("content").get(0).get("signature").asText()).isEqualTo("sig_xyz");
        // text 块
        assertThat(root.get("content").get(1).get("type").asText()).isEqualTo("text");
        assertThat(root.get("content").get(1).get("text").asText()).isEqualTo("调用工具");
        // tool_use 块
        assertThat(root.get("content").get(2).get("type").asText()).isEqualTo("tool_use");
        assertThat(root.get("content").get(2).get("id").asText()).isEqualTo("tool_456");
        assertThat(root.get("content").get(2).get("name").asText()).isEqualTo("get_weather");
        assertThat(root.get("content").get(2).get("input").get("city").asText()).isEqualTo("北京");
    }

    @Test
    void 流式_单文本块应发出完整事件序列() throws Exception {
        AnthropicProtocolAdapter.StreamState state = new AnthropicProtocolAdapter.StreamState();

        // 首个 chunk(含 id/model)
        UnifiedChatResponse chunk1 = UnifiedChatResponse.builder()
            .id("msg_001")
            .model("claude-sonnet-4-5")
            .choices(java.util.List.of(UnifiedChoice.builder()
                .delta(UnifiedDelta.builder().content("你好").build())
                .build()))
            .build();

        // 末尾 chunk(含 finishReason + usage)
        UnifiedChatResponse chunk2 = UnifiedChatResponse.builder()
            .choices(java.util.List.of(UnifiedChoice.builder()
                .finishReason("stop")
                .build()))
            .usage(UnifiedUsage.builder().promptTokens(10).completionTokens(5).totalTokens(15).build())
            .build();

        java.util.List<String> events = new java.util.ArrayList<>();
        events.addAll(adapter.toStreamEvents(chunk1, state));
        events.addAll(adapter.toStreamEvents(chunk2, state));
        // message_delta + message_stop 延迟到 finalizeStream 发送(修复点 23)
        events.addAll(adapter.finalizeStream(state));

        // 断言事件序列
        assertThat(events).isNotEmpty();
        JsonNode first = mapper.readTree(events.get(0));
        assertThat(first.get("type").asText()).isEqualTo("message_start");
        assertThat(first.get("message").get("id").asText()).isEqualTo("msg_001");
        // Anthropic 规范要求 message_start.message 包含 content/stop_reason/stop_sequence
        JsonNode startMsg = first.get("message");
        assertThat(startMsg.has("content")).isTrue();
        assertThat(startMsg.get("content").isArray()).isTrue();
        assertThat(startMsg.has("stop_reason")).isTrue();
        assertThat(startMsg.get("stop_reason").isNull()).isTrue();
        assertThat(startMsg.has("stop_sequence")).isTrue();
        assertThat(startMsg.get("stop_sequence").isNull()).isTrue();

        // 应包含 content_block_start / content_block_delta / content_block_stop / message_delta / message_stop
        boolean hasBlockStart = false, hasDelta = false, hasBlockStop = false, hasMsgDelta = false, hasMsgStop = false;
        for (String ev : events) {
            JsonNode n = mapper.readTree(ev);
            String type = n.get("type").asText();
            switch (type) {
                case "content_block_start" -> hasBlockStart = true;
                case "content_block_delta" -> hasDelta = true;
                case "content_block_stop" -> hasBlockStop = true;
                case "message_delta" -> {
                    hasMsgDelta = true;
                    assertThat(n.get("delta").get("stop_reason").asText()).isEqualTo("end_turn");
                    assertThat(n.get("delta").has("stop_sequence")).isTrue();
                    assertThat(n.get("delta").get("stop_sequence").isNull()).isTrue();
                }
                case "message_stop" -> hasMsgStop = true;
            }
        }
        assertThat(hasBlockStart).isTrue();
        assertThat(hasDelta).isTrue();
        assertThat(hasBlockStop).isTrue();
        assertThat(hasMsgDelta).isTrue();
        assertThat(hasMsgStop).isTrue();
    }

    @Test
    void 流式_tool_use应切分input_json_delta() throws Exception {
        AnthropicProtocolAdapter.StreamState state = new AnthropicProtocolAdapter.StreamState();

        UnifiedChatResponse chunk = UnifiedChatResponse.builder()
            .id("msg_002")
            .model("claude-sonnet-4-5")
            .choices(java.util.List.of(UnifiedChoice.builder()
                .delta(UnifiedDelta.builder()
                    .toolCalls(java.util.List.of(UnifiedToolCall.builder()
                        .id("tool_789")
                        .type("function")
                        .function(UnifiedFunctionCall.builder()
                            .name("get_weather")
                            .arguments(mapper.readTree("{\"city\":\"北京\",\"date\":\"today\"}"))
                            .build())
                        .build()))
                    .build())
                .finishReason("tool_calls")
                .build()))
            .build();

        java.util.List<String> events = adapter.toStreamEvents(chunk, state);

        // 找出所有 input_json_delta,拼接 partial_json
        StringBuilder fullJson = new StringBuilder();
        for (String ev : events) {
            JsonNode n = mapper.readTree(ev);
            if ("content_block_delta".equals(n.get("type").asText())) {
                JsonNode delta = n.get("delta");
                if ("input_json_delta".equals(delta.get("type").asText())) {
                    fullJson.append(delta.get("partial_json").asText());
                }
            }
        }
        // 拼接后应是合法 JSON
        JsonNode parsed = mapper.readTree(fullJson.toString());
        assertThat(parsed.get("city").asText()).isEqualTo("北京");
        assertThat(parsed.get("date").asText()).isEqualTo("today");
    }

    @Test
    void 流式_多chunk文本应复用同一block() throws Exception {
        AnthropicProtocolAdapter.StreamState state = new AnthropicProtocolAdapter.StreamState();

        // chunk1: id + model + "Hello "
        UnifiedChatResponse chunk1 = UnifiedChatResponse.builder()
            .id("msg_001")
            .model("claude-sonnet-4-5")
            .choices(java.util.List.of(UnifiedChoice.builder()
                .delta(UnifiedDelta.builder().content("Hello ").build())
                .build()))
            .build();

        // chunk2: "world"
        UnifiedChatResponse chunk2 = UnifiedChatResponse.builder()
            .choices(java.util.List.of(UnifiedChoice.builder()
                .delta(UnifiedDelta.builder().content("world").build())
                .build()))
            .build();

        // chunk3: finishReason
        UnifiedChatResponse chunk3 = UnifiedChatResponse.builder()
            .choices(java.util.List.of(UnifiedChoice.builder()
                .finishReason("stop")
                .build()))
            .usage(UnifiedUsage.builder().promptTokens(10).completionTokens(5).totalTokens(15).build())
            .build();

        java.util.List<String> events = new java.util.ArrayList<>();
        events.addAll(adapter.toStreamEvents(chunk1, state));
        events.addAll(adapter.toStreamEvents(chunk2, state));
        events.addAll(adapter.toStreamEvents(chunk3, state));

        // 统计 content_block_start / content_block_delta / content_block_stop 数量
        long blockStartCount = events.stream()
            .map(this::safeReadTree)
            .filter(n -> "content_block_start".equals(n.path("type").asText()))
            .count();
        long deltaCount = events.stream()
            .map(this::safeReadTree)
            .filter(n -> "content_block_delta".equals(n.path("type").asText()))
            .count();
        long blockStopCount = events.stream()
            .map(this::safeReadTree)
            .filter(n -> "content_block_stop".equals(n.path("type").asText()))
            .count();

        // 应只有 1 个 content_block_start,2 个 delta("Hello " + "world"),1 个 content_block_stop
        assertThat(blockStartCount).isEqualTo(1);
        assertThat(deltaCount).isEqualTo(2);
        assertThat(blockStopCount).isEqualTo(1);

        // 所有 block 事件的 index 应该相同(都是 0)
        events.stream()
            .map(this::safeReadTree)
            .filter(n -> n.path("type").asText().startsWith("content_block"))
            .forEach(n -> assertThat(n.path("index").asInt()).isEqualTo(0));
    }

    @Test
    void 流式_同chunk多个toolCall应产生独立block() throws Exception {
        AnthropicProtocolAdapter.StreamState state = new AnthropicProtocolAdapter.StreamState();

        UnifiedChatResponse chunk = UnifiedChatResponse.builder()
            .id("msg_003")
            .model("claude-sonnet-4-5")
            .choices(java.util.List.of(UnifiedChoice.builder()
                .delta(UnifiedDelta.builder()
                    .toolCalls(java.util.List.of(
                        UnifiedToolCall.builder()
                            .id("tool_a")
                            .type("function")
                            .function(UnifiedFunctionCall.builder()
                                .name("get_weather")
                                .arguments(mapper.readTree("{\"city\":\"北京\"}"))
                                .build())
                            .build(),
                        UnifiedToolCall.builder()
                            .id("tool_b")
                            .type("function")
                            .function(UnifiedFunctionCall.builder()
                                .name("get_time")
                                .arguments(mapper.readTree("{\"zone\":\"UTC\"}"))
                                .build())
                            .build()
                    ))
                    .build())
                .finishReason("tool_calls")
                .build()))
            .build();

        java.util.List<String> events = adapter.toStreamEvents(chunk, state);

        // 统计 content_block_start 数量(应 2 个:tool_a + tool_b)
        long blockStartCount = events.stream()
            .map(this::safeReadTree)
            .filter(n -> "content_block_start".equals(n.path("type").asText()))
            .count();
        assertThat(blockStartCount).isEqualTo(2);

        // 两个 tool_use block 的 id 和 name 应分别对应 tool_a/tool_b
        java.util.List<String> toolIds = events.stream()
            .map(this::safeReadTree)
            .filter(n -> "content_block_start".equals(n.path("type").asText()))
            .map(n -> n.path("content_block").path("id").asText())
            .toList();
        assertThat(toolIds).containsExactly("tool_a", "tool_b");

        java.util.List<String> toolNames = events.stream()
            .map(this::safeReadTree)
            .filter(n -> "content_block_start".equals(n.path("type").asText()))
            .map(n -> n.path("content_block").path("name").asText())
            .toList();
        assertThat(toolNames).containsExactly("get_weather", "get_time");

        // 两个 block 的 index 应不同(0 和 1)
        java.util.List<Integer> blockIndexes = events.stream()
            .map(this::safeReadTree)
            .filter(n -> "content_block_start".equals(n.path("type").asText()))
            .map(n -> n.path("index").asInt())
            .toList();
        assertThat(blockIndexes).containsExactly(0, 1);

        // 按 index 分组拼接 input_json_delta,应能还原两个独立 JSON
        java.util.Map<Integer, StringBuilder> jsonByIndex = new java.util.HashMap<>();
        for (String ev : events) {
            JsonNode n = safeReadTree(ev);
            if ("content_block_delta".equals(n.path("type").asText())) {
                JsonNode delta = n.path("delta");
                if ("input_json_delta".equals(delta.path("type").asText())) {
                    int idx = n.path("index").asInt();
                    jsonByIndex.computeIfAbsent(idx, k -> new StringBuilder())
                        .append(delta.path("partial_json").asText());
                }
            }
        }
        JsonNode json0 = mapper.readTree(jsonByIndex.get(0).toString());
        assertThat(json0.get("city").asText()).isEqualTo("北京");
        JsonNode json1 = mapper.readTree(jsonByIndex.get(1).toString());
        assertThat(json1.get("zone").asText()).isEqualTo("UTC");
    }

    private JsonNode safeReadTree(String json) {
        try { return mapper.readTree(json); } catch (Exception e) { return mapper.missingNode(); }
    }
}
