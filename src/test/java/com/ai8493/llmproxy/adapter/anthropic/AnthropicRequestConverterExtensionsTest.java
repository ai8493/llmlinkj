package com.ai8493.llmproxy.adapter.anthropic;

import com.ai8493.llmproxy.model.*;
import com.ai8493.llmproxy.model.extensions.ThinkingConfig;
import com.anthropic.models.messages.MessageCreateParams;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class AnthropicRequestConverterExtensionsTest {

    private final AnthropicRequestConverter converter = new AnthropicRequestConverter();

    @Test
    void shouldPreserveThinkingSignatureWhenConvertingAssistantMessage() {
        UnifiedMessage assistantMsg = UnifiedMessage.builder()
            .role(UnifiedMessage.Role.ASSISTANT)
            .content("answer")
            .reasoningContent("thinking content")
            .thinkingSignature("sig-abc-123")
            .build();
        UnifiedChatRequest req = UnifiedChatRequest.builder()
            .model("claude-3-5-sonnet")
            .messages(List.of(
                UnifiedMessage.builder().role(UnifiedMessage.Role.USER).content("hi").build(),
                assistantMsg))
            .build();

        MessageCreateParams params = converter.convert(req);

        // 验证 assistant 消息的 thinking block signature 被保留(非空串)
        var assistantBlocks = params.messages().get(1).content().asBlockParams();
        var thinkingBlock = assistantBlocks.stream()
            .filter(b -> b.isThinking())
            .findFirst().orElseThrow();
        assertThat(thinkingBlock.asThinking().signature()).isEqualTo("sig-abc-123");
    }

    @Test
    void shouldUseEmptySignatureWhenThinkingSignatureAbsent() {
        UnifiedMessage assistantMsg = UnifiedMessage.builder()
            .role(UnifiedMessage.Role.ASSISTANT)
            .content("answer")
            .reasoningContent("thinking content")
            .build();
        UnifiedChatRequest req = UnifiedChatRequest.builder()
            .model("claude-3-5-sonnet")
            .messages(List.of(
                UnifiedMessage.builder().role(UnifiedMessage.Role.USER).content("hi").build(),
                assistantMsg))
            .build();

        MessageCreateParams params = converter.convert(req);

        var assistantBlocks = params.messages().get(1).content().asBlockParams();
        var thinkingBlock = assistantBlocks.stream()
            .filter(b -> b.isThinking())
            .findFirst().orElseThrow();
        // thinkingSignature 为 null 时,fallback 空串(向后兼容)
        assertThat(thinkingBlock.asThinking().signature()).isEmpty();
    }

    @Test
    void shouldRebuildThinkingEnabledFromConfig() {
        UnifiedChatRequest req = UnifiedChatRequest.builder()
            .model("claude-3-5-sonnet")
            .messages(List.of(UnifiedMessage.builder().role(UnifiedMessage.Role.USER).content("hi").build()))
            .config(UnifiedGenerationConfig.builder()
                .maxOutputTokens(4096)
                .thinkingConfig(ThinkingConfig.builder()
                    .type("enabled")
                    .budgetTokens(10000)
                    .build())
                .build())
            .build();

        MessageCreateParams params = converter.convert(req);

        assertThat(params.thinking()).isPresent();
        assertThat(params.thinking().get().isEnabled()).isTrue();
        assertThat(params.thinking().get().asEnabled().budgetTokens()).isEqualTo(10000L);
    }

    @Test
    void shouldRebuildThinkingAdaptiveFromConfig() {
        UnifiedChatRequest req = UnifiedChatRequest.builder()
            .model("claude-3-5-sonnet")
            .messages(List.of(UnifiedMessage.builder().role(UnifiedMessage.Role.USER).content("hi").build()))
            .config(UnifiedGenerationConfig.builder()
                .maxOutputTokens(4096)
                .thinkingConfig(ThinkingConfig.builder()
                    .type("adaptive")
                    .build())
                .build())
            .build();

        MessageCreateParams params = converter.convert(req);

        assertThat(params.thinking()).isPresent();
        assertThat(params.thinking().get().isAdaptive()).isTrue();
    }

    @Test
    void shouldRebuildThinkingDisabledFromConfig() {
        UnifiedChatRequest req = UnifiedChatRequest.builder()
            .model("claude-3-5-sonnet")
            .messages(List.of(UnifiedMessage.builder().role(UnifiedMessage.Role.USER).content("hi").build()))
            .config(UnifiedGenerationConfig.builder()
                .maxOutputTokens(4096)
                .thinkingConfig(ThinkingConfig.builder()
                    .type("disabled")
                    .build())
                .build())
            .build();

        MessageCreateParams params = converter.convert(req);

        assertThat(params.thinking()).isPresent();
        assertThat(params.thinking().get().isDisabled()).isTrue();
    }

    @Test
    void shouldNotSetThinkingWhenConfigAbsent() {
        UnifiedChatRequest req = UnifiedChatRequest.builder()
            .model("claude-3-5-sonnet")
            .messages(List.of(UnifiedMessage.builder().role(UnifiedMessage.Role.USER).content("hi").build()))
            .config(UnifiedGenerationConfig.builder().maxOutputTokens(4096).build())
            .build();

        MessageCreateParams params = converter.convert(req);

        assertThat(params.thinking()).isEmpty();
    }

    @Test
    void shouldRebuildServiceTierFromConfig() {
        UnifiedChatRequest req = UnifiedChatRequest.builder()
            .model("claude-3-5-sonnet")
            .messages(List.of(UnifiedMessage.builder().role(UnifiedMessage.Role.USER).content("hi").build()))
            .config(UnifiedGenerationConfig.builder()
                .maxOutputTokens(4096)
                .serviceTier("priority")
                .build())
            .build();

        MessageCreateParams params = converter.convert(req);

        assertThat(params.serviceTier()).isPresent();
        assertThat(params.serviceTier().get().asString()).isEqualTo("priority");
    }

    @Test
    void shouldRebuildSystemArrayFromExtensions() throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.node.ArrayNode sysArray = mapper.createArrayNode();
        sysArray.add(mapper.createObjectNode().put("type", "text").put("text", "main instruction"));
        sysArray.add(mapper.createObjectNode().put("type", "text").put("text", "extra context"));

        UnifiedChatRequest req = UnifiedChatRequest.builder()
            .model("claude-3-5-sonnet")
            .messages(List.of(
                UnifiedMessage.builder().role(UnifiedMessage.Role.SYSTEM).content("main instruction\nextra context").build(),
                UnifiedMessage.builder().role(UnifiedMessage.Role.USER).content("hi").build()))
            .config(UnifiedGenerationConfig.builder().maxOutputTokens(4096).build())
            .anthropic(com.ai8493.llmproxy.model.extensions.AnthropicExtensions.builder()
                .rawSystemArray(sysArray)
                .build())
            .build();

        MessageCreateParams params = converter.convert(req);

        // 验证 system 是 array 形态(非 string)
        assertThat(params.system()).isPresent();
        assertThat(params.system().get().isTextBlockParams()).isTrue();
        var blocks = params.system().get().asTextBlockParams();
        assertThat(blocks).hasSize(2);
        assertThat(blocks.get(0).text()).isEqualTo("main instruction");
        assertThat(blocks.get(1).text()).isEqualTo("extra context");
    }

    @Test
    void shouldKeepStringSystemWhenRawSystemArrayAbsent() {
        UnifiedChatRequest req = UnifiedChatRequest.builder()
            .model("claude-3-5-sonnet")
            .messages(List.of(
                UnifiedMessage.builder().role(UnifiedMessage.Role.SYSTEM).content("simple string").build(),
                UnifiedMessage.builder().role(UnifiedMessage.Role.USER).content("hi").build()))
            .config(UnifiedGenerationConfig.builder().maxOutputTokens(4096).build())
            .build();

        MessageCreateParams params = converter.convert(req);

        // 无 rawSystemArray 时,system 仍为 string 形态(向后兼容)
        assertThat(params.system()).isPresent();
        assertThat(params.system().get().isString()).isTrue();
        assertThat(params.system().get().asString()).isEqualTo("simple string");
    }

    @Test
    void shouldRebuildDocumentPartFromBase64Source() {
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.node.ObjectNode docData = mapper.createObjectNode();
        docData.put("source_type", "base64");
        docData.put("media_type", "application/pdf");
        docData.put("data", "JVBERi0xLjQK...");
        docData.put("title", "test.pdf");

        UnifiedMessage userMsg = UnifiedMessage.builder()
            .role(UnifiedMessage.Role.USER)
            .parts(List.of(new UnifiedPart.DocumentPart(docData)))
            .build();
        UnifiedChatRequest req = UnifiedChatRequest.builder()
            .model("claude-3-5-sonnet")
            .messages(List.of(userMsg))
            .config(UnifiedGenerationConfig.builder().maxOutputTokens(4096).build())
            .build();

        MessageCreateParams params = converter.convert(req);

        var userBlocks = params.messages().get(0).content().asBlockParams();
        var docBlock = userBlocks.stream()
            .filter(b -> b.isDocument())
            .findFirst().orElseThrow();
        assertThat(docBlock.asDocument().source().isBase64()).isTrue();
        assertThat(docBlock.asDocument().source().asBase64().data()).isEqualTo("JVBERi0xLjQK...");
        assertThat(docBlock.asDocument().title()).hasValue("test.pdf");
    }

    @Test
    void shouldRebuildDocumentPartFromUrlSource() {
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.node.ObjectNode docData = mapper.createObjectNode();
        docData.put("source_type", "url");
        docData.put("url", "https://example.com/doc.pdf");

        UnifiedMessage userMsg = UnifiedMessage.builder()
            .role(UnifiedMessage.Role.USER)
            .parts(List.of(new UnifiedPart.DocumentPart(docData)))
            .build();
        UnifiedChatRequest req = UnifiedChatRequest.builder()
            .model("claude-3-5-sonnet")
            .messages(List.of(userMsg))
            .config(UnifiedGenerationConfig.builder().maxOutputTokens(4096).build())
            .build();

        MessageCreateParams params = converter.convert(req);

        var userBlocks = params.messages().get(0).content().asBlockParams();
        var docBlock = userBlocks.stream()
            .filter(b -> b.isDocument())
            .findFirst().orElseThrow();
        assertThat(docBlock.asDocument().source().isUrl()).isTrue();
        assertThat(docBlock.asDocument().source().asUrl().url()).isEqualTo("https://example.com/doc.pdf");
    }

    @Test
    void shouldRebuildDocumentPartFromTextSource() {
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.node.ObjectNode docData = mapper.createObjectNode();
        docData.put("source_type", "text");
        docData.put("data", "plain text document content");
        docData.put("context", "additional context");

        UnifiedMessage userMsg = UnifiedMessage.builder()
            .role(UnifiedMessage.Role.USER)
            .parts(List.of(new UnifiedPart.DocumentPart(docData)))
            .build();
        UnifiedChatRequest req = UnifiedChatRequest.builder()
            .model("claude-3-5-sonnet")
            .messages(List.of(userMsg))
            .config(UnifiedGenerationConfig.builder().maxOutputTokens(4096).build())
            .build();

        MessageCreateParams params = converter.convert(req);

        var userBlocks = params.messages().get(0).content().asBlockParams();
        var docBlock = userBlocks.stream()
            .filter(b -> b.isDocument())
            .findFirst().orElseThrow();
        assertThat(docBlock.asDocument().source().isText()).isTrue();
        assertThat(docBlock.asDocument().source().asText().data()).isEqualTo("plain text document content");
        assertThat(docBlock.asDocument().context()).hasValue("additional context");
    }
}
