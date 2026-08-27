package com.ai8493.llmproxy.adapter.anthropic;

import com.anthropic.core.ObjectMappers;
import com.anthropic.models.messages.*;
import com.ai8493.llmproxy.model.UnifiedPart;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AnthropicResponseConverterTest {

    @Test
    void shouldExtractThinkingSignature() {
        var converter = new AnthropicResponseConverter();
        var msg = Message.builder()
            .id("msg-1")
            .model("claude-sonnet-4-20250514")
            .role(com.anthropic.core.JsonValue.from("assistant"))
            .content(List.of(
                ContentBlock.ofThinking(ThinkingBlock.builder()
                    .thinking("思考中")
                    .signature("sig-abc")
                    .build()),
                ContentBlock.ofText(TextBlock.builder()
                    .text("你好")
                    .citations(List.of())
                    .build())
            ))
            .stopReason(StopReason.END_TURN)
            .stopSequence("")
            .stopDetails(RefusalStopDetails.builder()
                .category(RefusalStopDetails.Category.CYBER)
                .explanation("")
                .build())
            .usage(Usage.builder()
                .inputTokens(10L)
                .outputTokens(5L)
                .cacheCreation(CacheCreation.builder()
                    .ephemeral1hInputTokens(0)
                    .ephemeral5mInputTokens(0)
                    .build())
                .cacheCreationInputTokens(0L)
                .cacheReadInputTokens(0L)
                .inferenceGeo("")
                .serverToolUse(ServerToolUsage.builder()
                    .webFetchRequests(0)
                    .webSearchRequests(0)
                    .build())
                .serviceTier(Usage.ServiceTier.STANDARD)
                .build())
            .build();

        var result = converter.convert(msg);
        var uMsg = result.choices().get(0).message();
        assertThat(uMsg.reasoningContent()).isEqualTo("思考中");
        assertThat(uMsg.thinkingSignature()).isEqualTo("sig-abc");
    }

    @Test
    void shouldExtractCacheReadAndCreationConcurrently() {
        var converter = new AnthropicResponseConverter();
        var msg = Message.builder()
            .id("msg-1")
            .model("claude-3-5-sonnet")
            .role(com.anthropic.core.JsonValue.from("assistant"))
            .content(List.of(
                ContentBlock.ofText(TextBlock.builder()
                    .text("hi")
                    .citations(List.of())
                    .build())
            ))
            .stopReason(StopReason.END_TURN)
            .stopSequence("")
            .stopDetails(RefusalStopDetails.builder()
                .category(RefusalStopDetails.Category.CYBER)
                .explanation("")
                .build())
            .usage(Usage.builder()
                .inputTokens(100L)
                .outputTokens(50L)
                .cacheCreation(CacheCreation.builder()
                    .ephemeral1hInputTokens(0)
                    .ephemeral5mInputTokens(0)
                    .build())
                .cacheCreationInputTokens(20L)
                .cacheReadInputTokens(30L)
                .inferenceGeo("")
                .serverToolUse(ServerToolUsage.builder()
                    .webFetchRequests(0)
                    .webSearchRequests(0)
                    .build())
                .serviceTier(Usage.ServiceTier.STANDARD)
                .build())
            .build();

        var result = converter.convert(msg);
        // 两个桶应并存,非互斥
        assertThat(result.usage().cachedTokens()).isEqualTo(30);
        assertThat(result.usage().cacheCreationTokens()).isEqualTo(20);
    }

    @Test
    void shouldExtractRedactedThinkingToParts() {
        var converter = new AnthropicResponseConverter();
        var msg = Message.builder()
            .id("msg-1")
            .model("claude-3-5-sonnet")
            .role(com.anthropic.core.JsonValue.from("assistant"))
            .content(List.of(
                ContentBlock.ofRedactedThinking(
                    com.anthropic.models.messages.RedactedThinkingBlock.builder()
                        .data("redacted-data-123")
                        .build()),
                ContentBlock.ofText(TextBlock.builder()
                    .text("hi")
                    .citations(List.of())
                    .build())
            ))
            .stopReason(StopReason.END_TURN)
            .stopSequence("")
            .stopDetails(RefusalStopDetails.builder()
                .category(RefusalStopDetails.Category.CYBER)
                .explanation("")
                .build())
            .usage(Usage.builder()
                .inputTokens(10L)
                .outputTokens(5L)
                .cacheCreation(CacheCreation.builder()
                    .ephemeral1hInputTokens(0)
                    .ephemeral5mInputTokens(0)
                    .build())
                .cacheCreationInputTokens(0L)
                .cacheReadInputTokens(0L)
                .inferenceGeo("")
                .serverToolUse(ServerToolUsage.builder()
                    .webFetchRequests(0)
                    .webSearchRequests(0)
                    .build())
                .serviceTier(Usage.ServiceTier.STANDARD)
                .build())
            .build();

        var result = converter.convert(msg);
        var uMsg = result.choices().get(0).message();
        assertThat(uMsg.parts()).isNotNull().hasSize(1);
        assertThat(uMsg.parts().get(0)).isInstanceOf(UnifiedPart.RedactedThinkingPart.class);
        var rt = (UnifiedPart.RedactedThinkingPart) uMsg.parts().get(0);
        assertThat(rt.data().path("data").asText("")).isEqualTo("redacted-data-123");
    }

    @Test
    void shouldExtractStopSequenceToExtensions() {
        var converter = new AnthropicResponseConverter();
        var msg = Message.builder()
            .id("msg-1")
            .model("claude-3-5-sonnet")
            .role(com.anthropic.core.JsonValue.from("assistant"))
            .content(List.of(
                ContentBlock.ofText(TextBlock.builder()
                    .text("hello STOP")
                    .citations(List.of())
                    .build())
            ))
            .stopReason(StopReason.STOP_SEQUENCE)
            .stopSequence("STOP")
            .stopDetails(RefusalStopDetails.builder()
                .category(RefusalStopDetails.Category.CYBER)
                .explanation("")
                .build())
            .usage(Usage.builder()
                .inputTokens(10L)
                .outputTokens(5L)
                .cacheCreation(CacheCreation.builder()
                    .ephemeral1hInputTokens(0)
                    .ephemeral5mInputTokens(0)
                    .build())
                .cacheCreationInputTokens(0L)
                .cacheReadInputTokens(0L)
                .inferenceGeo("")
                .serverToolUse(ServerToolUsage.builder()
                    .webFetchRequests(0)
                    .webSearchRequests(0)
                    .build())
                .serviceTier(Usage.ServiceTier.STANDARD)
                .build())
            .build();

        var result = converter.convert(msg);
        assertThat(result.anthropic()).isNotNull();
        assertThat(result.anthropic().matchedStopSequence()).isEqualTo("STOP");
    }

    @Test
    void shouldStoreStopReasonOriginalValue() {
        var converter = new AnthropicResponseConverter();
        var msg = Message.builder()
            .id("msg-1")
            .model("claude-3-5-sonnet")
            .role(com.anthropic.core.JsonValue.from("assistant"))
            .content(List.of(
                ContentBlock.ofText(TextBlock.builder()
                    .text("hi")
                    .citations(List.of())
                    .build())
            ))
            .stopReason(StopReason.MAX_TOKENS)
            .stopSequence("")
            .stopDetails(RefusalStopDetails.builder()
                .category(RefusalStopDetails.Category.CYBER)
                .explanation("")
                .build())
            .usage(Usage.builder()
                .inputTokens(10L)
                .outputTokens(5L)
                .cacheCreation(CacheCreation.builder()
                    .ephemeral1hInputTokens(0)
                    .ephemeral5mInputTokens(0)
                    .build())
                .cacheCreationInputTokens(0L)
                .cacheReadInputTokens(0L)
                .inferenceGeo("")
                .serverToolUse(ServerToolUsage.builder()
                    .webFetchRequests(0)
                    .webSearchRequests(0)
                    .build())
                .serviceTier(Usage.ServiceTier.STANDARD)
                .build())
            .build();

        var result = converter.convert(msg);
        // 存 Anthropic 原值小写 "max_tokens",而非归一化 "length"
        assertThat(result.choices().get(0).finishReason()).isEqualTo("max_tokens");
    }

    @Test
    void shouldHandleThinkingBlockWithoutSignature() throws Exception {
        // 模拟 ark-claude 等第三方后端返回的 thinking block 缺失 signature 字段
        // SDK 反序列化不报错,但调用 signature() 会抛 AnthropicInvalidDataException
        ObjectMapper mapper = ObjectMappers.jsonMapper();
        String json = """
            {
              "id": "msg-1",
              "type": "message",
              "role": "assistant",
              "model": "claude-sonnet-4-20250514",
              "content": [
                {"type": "thinking", "thinking": "思考中"},
                {"type": "text", "text": "你好"}
              ],
              "stop_reason": "end_turn",
              "stop_sequence": null,
              "usage": {"input_tokens": 10, "output_tokens": 5}
            }
            """;
        Message msg = mapper.readValue(json, Message.class);

        var converter = new AnthropicResponseConverter();
        var result = converter.convert(msg);
        var uMsg = result.choices().get(0).message();
        assertThat(uMsg.reasoningContent()).isEqualTo("思考中");
        assertThat(uMsg.thinkingSignature()).isNull();
    }

    @Test
    void shouldHandleThinkingBlockWithoutThinking() throws Exception {
        // 极端情况: thinking block 连 thinking 字段都没有
        ObjectMapper mapper = ObjectMappers.jsonMapper();
        String json = """
            {
              "id": "msg-1",
              "type": "message",
              "role": "assistant",
              "model": "claude-sonnet-4-20250514",
              "content": [
                {"type": "thinking", "signature": "sig-abc"},
                {"type": "text", "text": "你好"}
              ],
              "stop_reason": "end_turn",
              "stop_sequence": null,
              "usage": {"input_tokens": 10, "output_tokens": 5}
            }
            """;
        Message msg = mapper.readValue(json, Message.class);

        var converter = new AnthropicResponseConverter();
        var result = converter.convert(msg);
        var uMsg = result.choices().get(0).message();
        assertThat(uMsg.reasoningContent()).isNull();
        assertThat(uMsg.thinkingSignature()).isEqualTo("sig-abc");
    }

    @Test
    void shouldParseToolUseInputAsJsonNode() throws Exception {
        // 复现: tu._input() 返回 JsonObject,其 toString() 是 Map.toString() 格式 {command=...}
        // 旧实现 MAPPER.readTree(tu._input().toString()) 会抛 JsonParseException,导致 arguments 为 null
        ObjectMapper mapper = ObjectMappers.jsonMapper();
        String json = """
            {
              "id": "msg-1",
              "type": "message",
              "role": "assistant",
              "model": "claude-sonnet-4-20250514",
              "content": [
                {"type": "tool_use", "id": "call_1", "name": "Bash", "input": {"command": "ls -la", "description": "列出文件"}},
                {"type": "text", "text": "执行命令"}
              ],
              "stop_reason": "tool_use",
              "stop_sequence": null,
              "usage": {"input_tokens": 10, "output_tokens": 5}
            }
            """;
        Message msg = mapper.readValue(json, Message.class);

        var converter = new AnthropicResponseConverter();
        var result = converter.convert(msg);
        var uMsg = result.choices().get(0).message();
        assertThat(uMsg.toolCalls()).hasSize(1);
        var tc = uMsg.toolCalls().get(0);
        assertThat(tc.id()).isEqualTo("call_1");
        assertThat(tc.function().name()).isEqualTo("Bash");
        JsonNode args = tc.function().arguments();
        assertThat(args).isNotNull();
        assertThat(args.isObject()).isTrue();
        assertThat(args.path("command").asText()).isEqualTo("ls -la");
        assertThat(args.path("description").asText()).isEqualTo("列出文件");
    }

    @Test
    void shouldExtractRawMessageToExtensions() throws Exception {
        // 验证后端原始响应 JSON 被提取到 extensions.responseRawMessage,保留所有字段
        ObjectMapper mapper = ObjectMappers.jsonMapper();
        String json = """
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
            """;
        Message msg = mapper.readValue(json, Message.class);

        var converter = new AnthropicResponseConverter();
        var result = converter.convert(msg);
        // 原始响应 JSON 应存到 extensions.responseRawMessage
        assertThat(result.anthropic()).isNotNull();
        assertThat(result.anthropic().responseRawMessage()).isNotNull();
        var rawMsg = result.anthropic().responseRawMessage();
        // 验证关键字段都在原始 JSON 中
        assertThat(rawMsg.path("id").asText()).isEqualTo("msg-raw-1");
        assertThat(rawMsg.path("stop_reason").asText()).isEqualTo("tool_use");
        assertThat(rawMsg.path("stop_sequence").asText()).isEqualTo("");
        // content blocks 字段
        var content = rawMsg.path("content");
        assertThat(content.isArray()).isTrue();
        assertThat(content.size()).isEqualTo(3);
        // thinking block 含 signature(即使是空串)
        var thinkingBlock = content.get(0);
        assertThat(thinkingBlock.path("type").asText()).isEqualTo("thinking");
        assertThat(thinkingBlock.has("signature")).isTrue();
        assertThat(thinkingBlock.path("signature").asText()).isEqualTo("");
        // text block 含 citations
        var textBlock = content.get(1);
        assertThat(textBlock.path("type").asText()).isEqualTo("text");
        assertThat(textBlock.has("citations")).isTrue();
        // tool_use block 含 caller
        var toolUseBlock = content.get(2);
        assertThat(toolUseBlock.path("type").asText()).isEqualTo("tool_use");
        assertThat(toolUseBlock.has("caller")).isTrue();
        // usage 含 server_tool_use 和 service_tier
        var usage = rawMsg.path("usage");
        assertThat(usage.has("server_tool_use")).isTrue();
        assertThat(usage.has("service_tier")).isTrue();
        assertThat(usage.path("service_tier").asText()).isEqualTo("standard");
    }
}
