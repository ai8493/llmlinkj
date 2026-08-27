package com.ai8493.llmproxy.adapter.anthropic;

import com.ai8493.llmproxy.model.UnifiedChatRequest;
import com.ai8493.llmproxy.model.UnifiedMessage;
import com.ai8493.llmproxy.model.UnifiedPart;
import com.ai8493.llmproxy.model.UnifiedToolChoice;
import org.junit.jupiter.api.Test;
import java.util.Map;
import java.nio.charset.StandardCharsets;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * APA 解析 anthropic-beta header → AnthropicExtensions.betaHeaders(修复点 9)
 */
class AnthropicProtocolAdapterExtensionsTest {

    private final AnthropicProtocolAdapter adapter = new AnthropicProtocolAdapter();

    private static final String BASE_BODY =
        "{\"model\":\"claude-3-5-sonnet-20241022\",\"max_tokens\":100,\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}";

    @Test
    void shouldParseAnthropicBetaHeaderToExtensions() {
        Map<String, String> headers = Map.of(
            "anthropic-beta", "prompt-caching-2024-07-31,extended-thinking-2025-05-14");
        UnifiedChatRequest req = adapter.toUnifiedRequest(BASE_BODY.getBytes(StandardCharsets.UTF_8), headers);
        assertThat(req.anthropic()).isNotNull();
        assertThat(req.anthropic().betaHeaders())
            .containsExactly("prompt-caching-2024-07-31", "extended-thinking-2025-05-14");
    }

    @Test
    void shouldDefaultExtensionsToNullWhenNoBetaHeader() {
        Map<String, String> headers = Map.of();
        UnifiedChatRequest req = adapter.toUnifiedRequest(BASE_BODY.getBytes(StandardCharsets.UTF_8), headers);
        // 无 beta header 时,anthropic 可为 null 或 betaHeaders 为 null
        if (req.anthropic() != null) {
            assertThat(req.anthropic().betaHeaders()).isNull();
        }
    }

    @Test
    void shouldParseThinkingEnabledToThinkingConfig() {
        String body = "{\"model\":\"claude-3-5-sonnet\",\"max_tokens\":100,\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],\"thinking\":{\"type\":\"enabled\",\"budget_tokens\":10000}}";
        UnifiedChatRequest req = adapter.toUnifiedRequest(body.getBytes(StandardCharsets.UTF_8), Map.of());
        assertThat(req.config().thinkingConfig()).isNotNull();
        assertThat(req.config().thinkingConfig().type()).isEqualTo("enabled");
        assertThat(req.config().thinkingConfig().budgetTokens()).isEqualTo(10000);
    }

    @Test
    void shouldParseThinkingAdaptiveWithoutBudget() {
        String body = "{\"model\":\"claude-3-5-sonnet\",\"max_tokens\":100,\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],\"thinking\":{\"type\":\"adaptive\"}}";
        UnifiedChatRequest req = adapter.toUnifiedRequest(body.getBytes(StandardCharsets.UTF_8), Map.of());
        assertThat(req.config().thinkingConfig()).isNotNull();
        assertThat(req.config().thinkingConfig().type()).isEqualTo("adaptive");
        assertThat(req.config().thinkingConfig().budgetTokens()).isNull();
    }

    @Test
    void shouldDefaultThinkingConfigToNullWhenAbsent() {
        UnifiedChatRequest req = adapter.toUnifiedRequest(BASE_BODY.getBytes(StandardCharsets.UTF_8), Map.of());
        assertThat(req.config().thinkingConfig()).isNull();
    }

    @Test
    void shouldParseThinkingDisabled() {
        String body = "{\"model\":\"claude-3-5-sonnet\",\"max_tokens\":100,\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],\"thinking\":{\"type\":\"disabled\"}}";
        UnifiedChatRequest req = adapter.toUnifiedRequest(body.getBytes(StandardCharsets.UTF_8), Map.of());
        assertThat(req.config().thinkingConfig()).isNotNull();
        assertThat(req.config().thinkingConfig().type()).isEqualTo("disabled");
        assertThat(req.config().thinkingConfig().budgetTokens()).isNull();
    }

    @Test
    void shouldParseTopK() {
        String body = "{\"model\":\"claude-3-5-sonnet\",\"max_tokens\":100,\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],\"top_k\":40}";
        UnifiedChatRequest req = adapter.toUnifiedRequest(body.getBytes(StandardCharsets.UTF_8), Map.of());
        assertThat(req.config().topK()).isEqualTo(40);
    }

    @Test
    void shouldParseServiceTier() {
        String body = "{\"model\":\"claude-3-5-sonnet\",\"max_tokens\":100,\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],\"service_tier\":\"priority\"}";
        UnifiedChatRequest req = adapter.toUnifiedRequest(body.getBytes(StandardCharsets.UTF_8), Map.of());
        assertThat(req.config().serviceTier()).isEqualTo("priority");
    }

    @Test
    void shouldDefaultTopKAndServiceTierToNull() {
        UnifiedChatRequest req = adapter.toUnifiedRequest(BASE_BODY.getBytes(StandardCharsets.UTF_8), Map.of());
        assertThat(req.config().topK()).isNull();
        assertThat(req.config().serviceTier()).isNull();
    }

    @Test
    void shouldPreserveSystemArrayStructure() {
        String body = "{\"model\":\"claude-3-5-sonnet\",\"max_tokens\":100,\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],\"system\":[{\"type\":\"text\",\"text\":\"main instruction\"},{\"type\":\"text\",\"text\":\"cache: extra\",\"cache_control\":{\"type\":\"ephemeral\"}}]}";
        UnifiedChatRequest req = adapter.toUnifiedRequest(body.getBytes(StandardCharsets.UTF_8), Map.of());
        // rawSystemArray 保留
        assertThat(req.anthropic()).isNotNull();
        assertThat(req.anthropic().rawSystemArray()).isNotNull();
        assertThat(req.anthropic().rawSystemArray().isArray()).isTrue();
        assertThat(req.anthropic().rawSystemArray().size()).isEqualTo(2);
        // systemBlocks 填充
        UnifiedMessage systemMsg = req.messages().stream()
            .filter(m -> m.role() == UnifiedMessage.Role.SYSTEM)
            .findFirst().orElseThrow();
        assertThat(systemMsg.systemBlocks()).hasSize(2);
        assertThat(systemMsg.systemBlocks().get(0)).isInstanceOf(UnifiedPart.TextPart.class);
        assertThat(((UnifiedPart.TextPart) systemMsg.systemBlocks().get(0)).text()).isEqualTo("main instruction");
        // string 折叠行为保留(content 仍为拼接字符串)
        assertThat(systemMsg.content()).contains("main instruction");
        assertThat(systemMsg.content()).contains("cache: extra");
    }

    @Test
    void shouldHandleStringSystemWithoutExtensions() {
        String body = "{\"model\":\"claude-3-5-sonnet\",\"max_tokens\":100,\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],\"system\":\"simple string\"}";
        UnifiedChatRequest req = adapter.toUnifiedRequest(body.getBytes(StandardCharsets.UTF_8), Map.of());
        // string 形态不填 rawSystemArray/systemBlocks
        if (req.anthropic() != null) {
            assertThat(req.anthropic().rawSystemArray()).isNull();
        }
        UnifiedMessage systemMsg = req.messages().stream()
            .filter(m -> m.role() == UnifiedMessage.Role.SYSTEM)
            .findFirst().orElseThrow();
        assertThat(systemMsg.content()).isEqualTo("simple string");
        assertThat(systemMsg.systemBlocks()).isNull();
    }

    @Test
    void shouldMergeRawSystemArrayAndBetaHeadersWhenBothPresent() {
        String body = "{\"model\":\"claude-3-5-sonnet\",\"max_tokens\":100,\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],\"system\":[{\"type\":\"text\",\"text\":\"instruction\"}]}";
        Map<String, String> headers = Map.of("anthropic-beta", "prompt-caching-2024-07-31");
        UnifiedChatRequest req = adapter.toUnifiedRequest(body.getBytes(StandardCharsets.UTF_8), headers);
        assertThat(req.anthropic()).isNotNull();
        assertThat(req.anthropic().rawSystemArray()).isNotNull();
        assertThat(req.anthropic().rawSystemArray().isArray()).isTrue();
        assertThat(req.anthropic().betaHeaders())
            .containsExactly("prompt-caching-2024-07-31");
    }

    @Test
    void shouldParseImageBlockToImagePart() {
        String body = "{\"model\":\"claude-3-5-sonnet\",\"max_tokens\":100,\"messages\":[{\"role\":\"user\",\"content\":[{\"type\":\"image\",\"source\":{\"type\":\"base64\",\"media_type\":\"image/png\",\"data\":\"iVBORw0KGgo=\"}}]}]}";
        UnifiedChatRequest req = adapter.toUnifiedRequest(body.getBytes(StandardCharsets.UTF_8), Map.of());
        UnifiedMessage userMsg = req.messages().stream()
            .filter(m -> m.role() == UnifiedMessage.Role.USER)
            .findFirst().orElseThrow();
        assertThat(userMsg.parts()).isNotNull();
        assertThat(userMsg.parts()).hasSize(1);
        assertThat(userMsg.parts().get(0)).isInstanceOf(UnifiedPart.ImagePart.class);
        UnifiedPart.ImagePart img = (UnifiedPart.ImagePart) userMsg.parts().get(0);
        // 验证 data-URL 格式(跨后端契约:OpenAiRequestConverter 读 url 字段)
        assertThat(img.imageData().path("url").asText())
            .isEqualTo("data:image/png;base64,iVBORw0KGgo=");
        assertThat(img.imageData().has("detail")).isTrue();
        assertThat(img.imageData().path("detail").isNull()).isTrue();
        // 不应含 SDK 内部字段
        assertThat(img.imageData().has("valid")).isFalse();
        assertThat(img.imageData().has("cache_control")).isFalse();
        assertThat(img.imageData().has("citations")).isFalse();
    }

    @Test
    void shouldParseImageUrlBlockToImagePart() {
        String body = "{\"model\":\"claude-3-5-sonnet\",\"max_tokens\":100,\"messages\":[{\"role\":\"user\",\"content\":[{\"type\":\"image\",\"source\":{\"type\":\"url\",\"url\":\"https://example.com/cat.png\"}}]}]}";
        UnifiedChatRequest req = adapter.toUnifiedRequest(body.getBytes(StandardCharsets.UTF_8), Map.of());
        UnifiedMessage userMsg = req.messages().stream()
            .filter(m -> m.role() == UnifiedMessage.Role.USER)
            .findFirst().orElseThrow();
        UnifiedPart.ImagePart img = (UnifiedPart.ImagePart) userMsg.parts().get(0);
        assertThat(img.imageData().path("url").asText()).isEqualTo("https://example.com/cat.png");
        assertThat(img.imageData().has("valid")).isFalse();
    }

    @Test
    void shouldParseDocumentBlockWithoutSdkPollution() {
        String body = "{\"model\":\"claude-3-5-sonnet\",\"max_tokens\":100,\"messages\":[{\"role\":\"user\",\"content\":[{\"type\":\"document\",\"source\":{\"type\":\"base64\",\"media_type\":\"application/pdf\",\"data\":\"JVBERi0=\"},\"title\":\"report\",\"context\":\"Q3 2026\"}]}]}";
        UnifiedChatRequest req = adapter.toUnifiedRequest(body.getBytes(StandardCharsets.UTF_8), Map.of());
        UnifiedMessage userMsg = req.messages().stream()
            .filter(m -> m.role() == UnifiedMessage.Role.USER)
            .findFirst().orElseThrow();
        assertThat(userMsg.parts()).isNotNull();
        assertThat(userMsg.parts()).hasSize(1);
        assertThat(userMsg.parts().get(0)).isInstanceOf(UnifiedPart.DocumentPart.class);
        UnifiedPart.DocumentPart doc = (UnifiedPart.DocumentPart) userMsg.parts().get(0);
        // 提取的核心字段
        assertThat(doc.documentData().path("source_type").asText()).isEqualTo("base64");
        assertThat(doc.documentData().path("media_type").asText()).isEqualTo("application/pdf");
        assertThat(doc.documentData().path("data").asText()).isEqualTo("JVBERi0=");
        assertThat(doc.documentData().path("title").asText()).isEqualTo("report");
        assertThat(doc.documentData().path("context").asText()).isEqualTo("Q3 2026");
        // 不应含 SDK 内部字段
        assertThat(doc.documentData().has("valid")).isFalse();
        assertThat(doc.documentData().has("citations")).isFalse();
        assertThat(doc.documentData().has("cache_control")).isFalse();
    }

    @Test
    void shouldPreserveTextWhenImagePresent() {
        String body = "{\"model\":\"claude-3-5-sonnet\",\"max_tokens\":100,\"messages\":[{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"describe this\"},{\"type\":\"image\",\"source\":{\"type\":\"base64\",\"media_type\":\"image/png\",\"data\":\"iVBORw0KGgo=\"}}]}]}";
        UnifiedChatRequest req = adapter.toUnifiedRequest(body.getBytes(StandardCharsets.UTF_8), Map.of());
        UnifiedMessage userMsg = req.messages().stream()
            .filter(m -> m.role() == UnifiedMessage.Role.USER)
            .findFirst().orElseThrow();
        // text 应包装为 TextPart 放在 parts 开头,image 紧随其后
        assertThat(userMsg.parts()).hasSize(2);
        assertThat(userMsg.parts().get(0)).isInstanceOf(UnifiedPart.TextPart.class);
        assertThat(((UnifiedPart.TextPart) userMsg.parts().get(0)).text()).isEqualTo("describe this");
        assertThat(userMsg.parts().get(1)).isInstanceOf(UnifiedPart.ImagePart.class);
        // content 仍保留 text(向后兼容:其他 adapter 可能读 content)
        assertThat(userMsg.content()).isEqualTo("describe this");
    }

    @Test
    void shouldParseRedactedThinkingBlock() {
        String body = "{\"model\":\"claude-3-5-sonnet\",\"max_tokens\":100,\"messages\":[{\"role\":\"assistant\",\"content\":[{\"type\":\"redacted_thinking\",\"data\":\"encrypted-blob-123\"}]}]}";
        UnifiedChatRequest req = adapter.toUnifiedRequest(body.getBytes(StandardCharsets.UTF_8), Map.of());
        UnifiedMessage asstMsg = req.messages().stream()
            .filter(m -> m.role() == UnifiedMessage.Role.ASSISTANT)
            .findFirst().orElseThrow();
        assertThat(asstMsg.parts()).isNotNull();
        assertThat(asstMsg.parts().get(0)).isInstanceOf(UnifiedPart.RedactedThinkingPart.class);
        UnifiedPart.RedactedThinkingPart rtp = (UnifiedPart.RedactedThinkingPart) asstMsg.parts().get(0);
        assertThat(rtp.data().path("data").asText()).isEqualTo("encrypted-blob-123");
    }

    @Test
    void shouldParseToolChoiceAnyWithoutDowngrade() {
        String body = "{\"model\":\"claude-3-5-sonnet\",\"max_tokens\":100,\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],\"tool_choice\":{\"type\":\"any\"},\"tools\":[{\"name\":\"get_weather\",\"description\":\"weather\",\"input_schema\":{\"type\":\"object\",\"properties\":{}}}]}";
        UnifiedChatRequest req = adapter.toUnifiedRequest(body.getBytes(StandardCharsets.UTF_8), Map.of());
        assertThat(req.toolChoice()).isInstanceOf(UnifiedToolChoice.Any.class);
    }

    @Test
    void shouldSupportNewExtensionsFields() {
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var ext = com.ai8493.llmproxy.model.extensions.AnthropicExtensions.builder()
            .outputConfig(mapper.createObjectNode().put("effort", "max"))
            .contextManagement(mapper.createObjectNode().put("clear_thinking", true))
            .toolResultIsError(java.util.Map.of("call_abc", true))
            .rawToolResultBlocks(java.util.Map.of("call_abc", mapper.createArrayNode()))
            .build();
        assertThat(ext.outputConfig()).isNotNull();
        assertThat(ext.contextManagement()).isNotNull();
        assertThat(ext.toolResultIsError()).containsEntry("call_abc", true);
        assertThat(ext.rawToolResultBlocks()).containsKey("call_abc");
    }

    @Test
    void shouldParseMetadataUserIdToExtensions() {
        String body = "{\"model\":\"claude-3-5-sonnet\",\"max_tokens\":100,\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],\"metadata\":{\"user_id\":\"user-abc\"}}";
        UnifiedChatRequest req = adapter.toUnifiedRequest(body.getBytes(StandardCharsets.UTF_8), Map.of());
        assertThat(req.anthropic()).isNotNull();
        assertThat(req.anthropic().metadataUserId()).isEqualTo("user-abc");
    }

    @Test
    void shouldParseOutputConfigToExtensions() {
        String body = "{\"model\":\"claude-3-5-sonnet\",\"max_tokens\":100,\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],\"output_config\":{\"effort\":\"max\",\"format\":{\"type\":\"json_schema\",\"schema\":{\"type\":\"object\",\"properties\":{}}}}}";
        UnifiedChatRequest req = adapter.toUnifiedRequest(body.getBytes(StandardCharsets.UTF_8), Map.of());
        assertThat(req.anthropic()).isNotNull();
        assertThat(req.anthropic().outputConfig()).isNotNull();
        assertThat(req.anthropic().outputConfig().path("effort").asText()).isEqualTo("max");
        assertThat(req.anthropic().outputConfig().path("format").path("type").asText()).isEqualTo("json_schema");
    }

    @Test
    void shouldParseContextManagementToExtensions() {
        String body = "{\"model\":\"claude-3-5-sonnet\",\"max_tokens\":100,\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],\"context_management\":{\"edits\":[{\"type\":\"clear_thinking_20251015\",\"keep\":\"all\"}]}}";
        UnifiedChatRequest req = adapter.toUnifiedRequest(body.getBytes(StandardCharsets.UTF_8), Map.of());
        assertThat(req.anthropic()).isNotNull();
        assertThat(req.anthropic().contextManagement()).isNotNull();
        assertThat(req.anthropic().contextManagement().path("edits").isArray()).isTrue();
    }

    @Test
    void shouldMergeAllTopLevelFieldsWithBetaHeaderAndSystemArray() {
        String body = "{\"model\":\"claude-3-5-sonnet\",\"max_tokens\":100,\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],\"system\":[{\"type\":\"text\",\"text\":\"instr\"}],\"metadata\":{\"user_id\":\"u1\"},\"output_config\":{\"effort\":\"high\"},\"context_management\":{\"edits\":[]}}";
        Map<String, String> headers = Map.of("anthropic-beta", "prompt-caching-2024-07-31");
        UnifiedChatRequest req = adapter.toUnifiedRequest(body.getBytes(StandardCharsets.UTF_8), headers);
        assertThat(req.anthropic()).isNotNull();
        assertThat(req.anthropic().rawSystemArray()).isNotNull();
        assertThat(req.anthropic().betaHeaders()).containsExactly("prompt-caching-2024-07-31");
        assertThat(req.anthropic().metadataUserId()).isEqualTo("u1");
        assertThat(req.anthropic().outputConfig()).isNotNull();
        assertThat(req.anthropic().contextManagement()).isNotNull();
    }

    @Test
    void shouldParseUserTextBlockCacheControl() {
        String body = "{\"model\":\"claude-3-5-sonnet\",\"max_tokens\":100,\"messages\":[{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"cached text\",\"cache_control\":{\"type\":\"ephemeral\"}}]}]}";
        UnifiedChatRequest req = adapter.toUnifiedRequest(body.getBytes(StandardCharsets.UTF_8), Map.of());
        assertThat(req.anthropic()).isNotNull();
        assertThat(req.anthropic().cacheControlByBlock()).isNotNull();
        assertThat(req.anthropic().cacheControlByBlock().path("0-0").path("type").asText()).isEqualTo("ephemeral");
    }

    @Test
    void shouldParseAssistantToolUseBlockCacheControl() {
        String body = "{\"model\":\"claude-3-5-sonnet\",\"max_tokens\":100,\"messages\":[{\"role\":\"user\",\"content\":\"hi\"},{\"role\":\"assistant\",\"content\":[{\"type\":\"text\",\"text\":\"ok\"},{\"type\":\"tool_use\",\"id\":\"call_1\",\"name\":\"get_weather\",\"input\":{\"city\":\"sf\"},\"cache_control\":{\"type\":\"ephemeral\"}}]}]}";
        UnifiedChatRequest req = adapter.toUnifiedRequest(body.getBytes(StandardCharsets.UTF_8), Map.of());
        assertThat(req.anthropic().cacheControlByBlock()).isNotNull();
        // assistant 消息是 body.messages()[1],text 是 block 0,tool_use 是 block 1
        assertThat(req.anthropic().cacheControlByBlock().path("1-1").path("type").asText()).isEqualTo("ephemeral");
    }

    @Test
    void shouldParseToolResultIsError() {
        String body = "{\"model\":\"claude-3-5-sonnet\",\"max_tokens\":100,\"messages\":[{\"role\":\"user\",\"content\":\"hi\"},{\"role\":\"assistant\",\"content\":[{\"type\":\"tool_use\",\"id\":\"call_1\",\"name\":\"get_weather\",\"input\":{}}]},{\"role\":\"user\",\"content\":[{\"type\":\"tool_result\",\"tool_use_id\":\"call_1\",\"content\":\"sunny\",\"is_error\":false,\"cache_control\":{\"type\":\"ephemeral\"}}]}]}";
        UnifiedChatRequest req = adapter.toUnifiedRequest(body.getBytes(StandardCharsets.UTF_8), Map.of());
        assertThat(req.anthropic()).isNotNull();
        assertThat(req.anthropic().toolResultIsError()).containsEntry("call_1", false);
        // tool_result 的 cache_control 用 toolUseId 作 key
        assertThat(req.anthropic().cacheControlByBlock().path("call_1").path("type").asText()).isEqualTo("ephemeral");
    }

    @Test
    void shouldParseToolResultContentArrayToRawBlocks() {
        String body = "{\"model\":\"claude-3-5-sonnet\",\"max_tokens\":100,\"messages\":[{\"role\":\"user\",\"content\":\"hi\"},{\"role\":\"assistant\",\"content\":[{\"type\":\"tool_use\",\"id\":\"call_1\",\"name\":\"get_weather\",\"input\":{}}]},{\"role\":\"user\",\"content\":[{\"type\":\"tool_result\",\"tool_use_id\":\"call_1\",\"content\":[{\"type\":\"text\",\"text\":\"result text\"},{\"type\":\"image\",\"source\":{\"type\":\"base64\",\"media_type\":\"image/png\",\"data\":\"iVBORw0KGgo=\"}}]}]}]}";
        UnifiedChatRequest req = adapter.toUnifiedRequest(body.getBytes(StandardCharsets.UTF_8), Map.of());
        assertThat(req.anthropic()).isNotNull();
        assertThat(req.anthropic().rawToolResultBlocks()).containsKey("call_1");
        assertThat(req.anthropic().rawToolResultBlocks().get("call_1").isArray()).isTrue();
        assertThat(req.anthropic().rawToolResultBlocks().get("call_1").size()).isEqualTo(2);
    }

    @Test
    void shouldNotFillRawToolResultBlocksWhenContentIsString() {
        String body = "{\"model\":\"claude-3-5-sonnet\",\"max_tokens\":100,\"messages\":[{\"role\":\"user\",\"content\":\"hi\"},{\"role\":\"assistant\",\"content\":[{\"type\":\"tool_use\",\"id\":\"call_1\",\"name\":\"get_weather\",\"input\":{}}]},{\"role\":\"user\",\"content\":[{\"type\":\"tool_result\",\"tool_use_id\":\"call_1\",\"content\":\"sunny\"}]}]}";
        UnifiedChatRequest req = adapter.toUnifiedRequest(body.getBytes(StandardCharsets.UTF_8), Map.of());
        // string content 不填 rawToolResultBlocks(走 IR.content)
        if (req.anthropic().rawToolResultBlocks() != null) {
            assertThat(req.anthropic().rawToolResultBlocks()).doesNotContainKey("call_1");
        }
    }
}
