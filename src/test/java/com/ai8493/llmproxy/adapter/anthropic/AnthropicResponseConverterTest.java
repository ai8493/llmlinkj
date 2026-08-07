package com.ai8493.llmproxy.adapter.anthropic;

import com.anthropic.core.ObjectMappers;
import com.anthropic.models.messages.*;
import com.ai8493.llmproxy.model.UnifiedPart;
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
}
