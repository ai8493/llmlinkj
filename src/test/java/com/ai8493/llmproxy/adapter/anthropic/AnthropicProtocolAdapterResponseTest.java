package com.ai8493.llmproxy.adapter.anthropic;

import com.ai8493.llmproxy.model.*;
import com.ai8493.llmproxy.model.extensions.AnthropicExtensions;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class AnthropicProtocolAdapterResponseTest {

    private final AnthropicProtocolAdapter adapter = new AnthropicProtocolAdapter();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void shouldOutputCacheReadInputTokensWhenCached() throws Exception {
        UnifiedChatResponse ir = buildResponse(UnifiedUsage.builder()
            .promptTokens(70)
            .completionTokens(50)
            .totalTokens(120)
            .cachedTokens(30)
            .cacheCreationTokens(0)
            .reasoningTokens(0)
            .build());

        byte[] result = adapter.fromUnifiedResponse(ir);
        JsonNode root = mapper.readTree(result);
        JsonNode usage = root.path("usage");

        assertThat(usage.path("input_tokens").asInt()).isEqualTo(70);
        assertThat(usage.path("cache_read_input_tokens").asInt()).isEqualTo(30);
        // Anthropic 协议规范要求 cache_creation_input_tokens 即使为 0 也输出
        assertThat(usage.path("cache_creation_input_tokens").asInt()).isEqualTo(0);
        assertThat(usage.path("output_tokens").asInt()).isEqualTo(50);
    }

    @Test
    void shouldOutputCacheCreationInputTokensWhenCreated() throws Exception {
        UnifiedChatResponse ir = buildResponse(UnifiedUsage.builder()
            .promptTokens(50)
            .completionTokens(40)
            .totalTokens(140)
            .cachedTokens(0)
            .cacheCreationTokens(50)
            .reasoningTokens(0)
            .build());

        byte[] result = adapter.fromUnifiedResponse(ir);
        JsonNode root = mapper.readTree(result);
        JsonNode usage = root.path("usage");

        assertThat(usage.path("input_tokens").asInt()).isEqualTo(50);
        // Anthropic 协议规范要求 cache_read_input_tokens 即使为 0 也输出
        assertThat(usage.path("cache_read_input_tokens").asInt()).isEqualTo(0);
        assertThat(usage.path("cache_creation_input_tokens").asInt()).isEqualTo(50);
    }

    @Test
    void shouldNotOutputReasoningTokensField() throws Exception {
        // reasoning_tokens 非 Anthropic 协议字段(实为 OpenAI 字段),不应出现在响应中
        UnifiedChatResponse ir = buildResponse(UnifiedUsage.builder()
            .promptTokens(100)
            .completionTokens(50)
            .totalTokens(150)
            .cachedTokens(0)
            .cacheCreationTokens(0)
            .reasoningTokens(20)
            .build());

        byte[] result = adapter.fromUnifiedResponse(ir);
        JsonNode root = mapper.readTree(result);
        JsonNode usage = root.path("usage");

        assertThat(usage.has("reasoning_tokens")).isFalse();
    }

    private UnifiedChatResponse buildResponse(UnifiedUsage usage) {
        return UnifiedChatResponse.builder()
            .id("msg-1")
            .model("claude-3-5-sonnet")
            .object("message")
            .created(1700000000L)
            .choices(List.of(UnifiedChoice.builder()
                .index(0)
                .message(UnifiedMessage.builder()
                    .role(UnifiedMessage.Role.ASSISTANT)
                    .content("hello")
                    .build())
                .finishReason("stop")
                .build()))
            .usage(usage)
            .build();
    }

    @Test
    void shouldMapContentFilterToRefusal() throws Exception {
        UnifiedChatResponse ir = UnifiedChatResponse.builder()
            .id("msg-2")
            .model("claude-3-5-sonnet")
            .object("message")
            .created(1700000000L)
            .choices(List.of(UnifiedChoice.builder()
                .index(0)
                .message(UnifiedMessage.builder()
                    .role(UnifiedMessage.Role.ASSISTANT)
                    .content("filtered content")
                    .build())
                .finishReason("content_filter")
                .build()))
            .usage(UnifiedUsage.builder()
                .promptTokens(10)
                .completionTokens(5)
                .totalTokens(15)
                .build())
            .build();

        byte[] result = adapter.fromUnifiedResponse(ir);
        JsonNode root = mapper.readTree(result);

        assertThat(root.path("stop_reason").asText()).isEqualTo("refusal");
    }

    @Test
    void shouldKeepStopMappingForRegression() throws Exception {
        for (String[] mapping : new String[][]{
            {"stop", "end_turn"},
            {"length", "max_tokens"},
            {"tool_calls", "tool_use"}
        }) {
            UnifiedChatResponse ir = UnifiedChatResponse.builder()
                .id("msg-reg-" + mapping[0])
                .model("claude-3-5-sonnet")
                .object("message")
                .created(1700000000L)
                .choices(List.of(UnifiedChoice.builder()
                    .index(0)
                    .message(UnifiedMessage.builder()
                        .role(UnifiedMessage.Role.ASSISTANT)
                        .content("x")
                        .build())
                    .finishReason(mapping[0])
                    .build()))
                .usage(UnifiedUsage.builder()
                    .promptTokens(1).completionTokens(1).totalTokens(2).build())
                .build();

            byte[] result = adapter.fromUnifiedResponse(ir);
            JsonNode root = mapper.readTree(result);
            assertThat(root.path("stop_reason").asText()).isEqualTo(mapping[1]);
        }
    }

    @Test
    void shouldSatisfyBillingIdentityWhenAllCacheBucketsPresent() throws Exception {
        // 模拟 OResC 入站后 IR 状态:原 promptTokens=100, cached=30, creation=20 → IR.promptTokens=50
        UnifiedChatResponse ir = buildResponse(UnifiedUsage.builder()
            .promptTokens(50)
            .completionTokens(40)
            .totalTokens(140)
            .cachedTokens(30)
            .cacheCreationTokens(20)
            .reasoningTokens(0)
            .build());

        byte[] result = adapter.fromUnifiedResponse(ir);
        JsonNode root = mapper.readTree(result);
        JsonNode usage = root.path("usage");

        int inputTokens = usage.path("input_tokens").asInt();
        int cacheRead = usage.path("cache_read_input_tokens").asInt();
        int cacheCreation = usage.path("cache_creation_input_tokens").asInt();

        // 计费恒等式:input_tokens + cache_read + cache_creation == 原 prompt_tokens(100)
        assertThat(inputTokens + cacheRead + cacheCreation).isEqualTo(100);
        assertThat(inputTokens).isEqualTo(50);
        assertThat(cacheRead).isEqualTo(30);
        assertThat(cacheCreation).isEqualTo(20);
    }

    @Test
    void shouldSatisfyBillingIdentityWhenNoCache() throws Exception {
        // 无 cache 场景:promptTokens=100, cached=0, creation=0
        UnifiedChatResponse ir = buildResponse(UnifiedUsage.builder()
            .promptTokens(100)
            .completionTokens(50)
            .totalTokens(150)
            .cachedTokens(0)
            .cacheCreationTokens(0)
            .reasoningTokens(0)
            .build());

        byte[] result = adapter.fromUnifiedResponse(ir);
        JsonNode root = mapper.readTree(result);
        JsonNode usage = root.path("usage");

        int inputTokens = usage.path("input_tokens").asInt();
        int cacheRead = usage.path("cache_read_input_tokens").asInt(0);
        int cacheCreation = usage.path("cache_creation_input_tokens").asInt(0);

        assertThat(inputTokens + cacheRead + cacheCreation).isEqualTo(100);
    }

    @Test
    void shouldOutputRawMessageWhenAvailable() throws Exception {
        // 模拟后端原始响应 JSON(含 signature/citations/caller/server_tool_use/service_tier)
        var rawMsg = mapper.readTree("""
            {
              "id": "msg-raw-1",
              "type": "message",
              "role": "assistant",
              "model": "claude-sonnet-4",
              "stop_reason": "tool_use",
              "stop_sequence": "",
              "content": [
                {"type": "thinking", "thinking": "思考", "signature": ""},
                {"type": "text", "text": "你好", "citations": []},
                {"type": "tool_use", "id": "call-1", "name": "get_weather", "input": {"city": "北京"}, "caller": ""}
              ],
              "usage": {
                "input_tokens": 10,
                "output_tokens": 5,
                "cache_read_input_tokens": 80,
                "cache_creation_input_tokens": 0,
                "server_tool_use": {"web_fetch_requests": 0, "web_search_requests": 0},
                "service_tier": "standard"
              }
            }
            """);
        var anthropicExt = AnthropicExtensions.builder()
            .responseRawMessage(rawMsg)
            .build();
        var uResp = UnifiedChatResponse.builder()
            .id("msg-raw-1")
            .model("claude-sonnet-4")
            .object("chat.completion")
            .created(1234567890L)
            .choices(List.of(UnifiedChoice.builder()
                .index(0)
                .message(UnifiedMessage.builder()
                    .role(UnifiedMessage.Role.ASSISTANT)
                    .content("你好")
                    .build())
                .finishReason("tool_use")
                .build()))
            .usage(UnifiedUsage.builder()
                .promptTokens(10)
                .completionTokens(5)
                .totalTokens(15)
                .build())
            .anthropic(anthropicExt)
            .build();

        var bytes = adapter.fromUnifiedResponse(uResp);
        var result = mapper.readTree(bytes);

        // 验证原始字段全保留
        assertThat(result.path("id").asText()).isEqualTo("msg-raw-1");
        assertThat(result.path("stop_sequence").asText()).isEqualTo("");
        var content = result.path("content");
        var thinking = content.get(0);
        assertThat(thinking.path("signature").asText()).isEqualTo("");
        assertThat(content.get(1).has("citations")).isTrue();
        assertThat(content.get(2).has("caller")).isTrue();
        var usage = result.path("usage");
        assertThat(usage.has("server_tool_use")).isTrue();
        assertThat(usage.has("service_tier")).isTrue();
    }

    @Test
    void shouldPatchStopSequenceNullWhenRawMessageMissingIt() throws Exception {
        // 模拟后端 SDK NON_NULL 序列化导致 stop_sequence 字段缺失(实测 58/58)
        var rawMsg = mapper.readTree("""
            {
              "id": "msg-raw-2",
              "type": "message",
              "role": "assistant",
              "model": "claude-sonnet-4",
              "stop_reason": "end_turn",
              "content": [{"type": "text", "text": "hi"}],
              "usage": {
                "input_tokens": 10,
                "output_tokens": 5
              }
            }
            """);
        var anthropicExt = AnthropicExtensions.builder()
            .responseRawMessage(rawMsg)
            .build();
        var uResp = UnifiedChatResponse.builder()
            .id("msg-raw-2")
            .model("claude-sonnet-4")
            .object("chat.completion")
            .created(1234567890L)
            .choices(List.of(UnifiedChoice.builder()
                .index(0)
                .message(UnifiedMessage.builder()
                    .role(UnifiedMessage.Role.ASSISTANT)
                    .content("hi")
                    .build())
                .finishReason("end_turn")
                .build()))
            .usage(UnifiedUsage.builder()
                .promptTokens(10)
                .completionTokens(5)
                .totalTokens(15)
                .build())
            .anthropic(anthropicExt)
            .build();

        var bytes = adapter.fromUnifiedResponse(uResp);
        var result = mapper.readTree(bytes);

        // Anthropic 协议规范要求 stop_sequence 字段必须存在,无匹配时为 null
        assertThat(result.has("stop_sequence")).isTrue();
        assertThat(result.path("stop_sequence").isNull()).isTrue();
    }

    @Test
    void shouldPatchCacheCreationZeroWhenRawMessageMissingIt() throws Exception {
        // 模拟后端 SDK NON_NULL 序列化导致 cache_creation_input_tokens=0 被跳过(实测 58/58)
        var rawMsg = mapper.readTree("""
            {
              "id": "msg-raw-3",
              "type": "message",
              "role": "assistant",
              "model": "claude-sonnet-4",
              "stop_reason": "end_turn",
              "content": [{"type": "text", "text": "hi"}],
              "usage": {
                "input_tokens": 10,
                "output_tokens": 5,
                "cache_read_input_tokens": 80
              }
            }
            """);
        var anthropicExt = AnthropicExtensions.builder()
            .responseRawMessage(rawMsg)
            .build();
        var uResp = UnifiedChatResponse.builder()
            .id("msg-raw-3")
            .model("claude-sonnet-4")
            .object("chat.completion")
            .created(1234567890L)
            .choices(List.of(UnifiedChoice.builder()
                .index(0)
                .message(UnifiedMessage.builder()
                    .role(UnifiedMessage.Role.ASSISTANT)
                    .content("hi")
                    .build())
                .finishReason("end_turn")
                .build()))
            .usage(UnifiedUsage.builder()
                .promptTokens(10)
                .completionTokens(5)
                .totalTokens(15)
                .cachedTokens(80)
                .cacheCreationTokens(0)
                .build())
            .anthropic(anthropicExt)
            .build();

        var bytes = adapter.fromUnifiedResponse(uResp);
        var result = mapper.readTree(bytes);
        var usage = result.path("usage");

        // Anthropic 协议规范要求 cache_creation_input_tokens 即使为 0 也应输出
        assertThat(usage.has("cache_creation_input_tokens")).isTrue();
        assertThat(usage.path("cache_creation_input_tokens").asInt()).isEqualTo(0);
    }
}
