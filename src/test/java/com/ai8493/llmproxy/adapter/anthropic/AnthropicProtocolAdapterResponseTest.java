package com.ai8493.llmproxy.adapter.anthropic;

import com.ai8493.llmproxy.model.*;
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
        assertThat(usage.has("cache_creation_input_tokens")).isFalse();
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
        assertThat(usage.has("cache_read_input_tokens")).isFalse();
        assertThat(usage.path("cache_creation_input_tokens").asInt()).isEqualTo(50);
    }

    @Test
    void shouldOutputReasoningTokensAlways() throws Exception {
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

        assertThat(usage.path("reasoning_tokens").asInt()).isEqualTo(20);
    }

    @Test
    void shouldOutputReasoningTokensZero() throws Exception {
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

        assertThat(usage.path("reasoning_tokens").asInt()).isEqualTo(0);
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
}
