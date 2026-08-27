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
            .filter(b ->b.isThinking())
            .findFirst().orElseThrow();
        // thinkingSignature 为 null 时,signature 用 JsonMissing(SDK 序列化时 @ExcludeMissing 忽略,
        // 不出现在出站 body,避免后端 400)
        assertThat(thinkingBlock.asThinking()._signature().isMissing()).isTrue();
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

    @Test
    void shouldApplyCacheControlToUserTextFromNewFlatFormat() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        // 新格式:{"0-0": {type:ephemeral}}
        var ccByBlock = mapper.readTree("""
            {"0-0": {"type": "ephemeral"}}
            """);
        var ext = com.ai8493.llmproxy.model.extensions.AnthropicExtensions.builder()
            .cacheControlByBlock(ccByBlock)
            .build();
        var req = UnifiedChatRequest.builder()
            .model("claude-3-5-sonnet")
            .messages(List.of(UnifiedMessage.builder()
                .role(UnifiedMessage.Role.USER)
                .content("cached text")
                .build()))
            .config(UnifiedGenerationConfig.builder().maxOutputTokens(4096).build())
            .anthropic(ext)
            .build();

        MessageCreateParams params = converter.convert(req);

        var userBlocks = params.messages().get(0).content().asBlockParams();
        var textBlock = userBlocks.stream().filter(b -> b.isText()).findFirst().orElseThrow();
        assertThat(textBlock.asText().cacheControl()).isPresent();
    }

    @Test
    void shouldNotIncrementBodyMsgIdxForToolMessage() throws Exception {
        // 验证 assistant 消息(tool_use)的 cache_control 用正确的 bodyMsgIdx 查询
        // 场景:body.messages() = [user(text), assistant(tool_use with cache_control)]
        // 入站 cacheControlByBlock: {"1-0": {type:ephemeral}} (bodyMsgIdx=1, blockIdx=0)
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var ccByBlock = mapper.readTree("""
            {"1-0": {"type": "ephemeral"}}
            """);
        var ext = com.ai8493.llmproxy.model.extensions.AnthropicExtensions.builder()
            .cacheControlByBlock(ccByBlock)
            .build();
        var assistantMsg = UnifiedMessage.builder()
            .role(UnifiedMessage.Role.ASSISTANT)
            .toolCalls(List.of(UnifiedToolCall.builder()
                .id("call_1")
                .type("function")
                .function(UnifiedFunctionCall.builder()
                    .name("get_weather")
                    .arguments(mapper.createObjectNode())
                    .build())
                .build()))
            .build();
        var req = UnifiedChatRequest.builder()
            .model("claude-3-5-sonnet")
            .messages(List.of(
                UnifiedMessage.builder().role(UnifiedMessage.Role.USER).content("hi").build(),
                assistantMsg))
            .config(UnifiedGenerationConfig.builder().maxOutputTokens(4096).build())
            .anthropic(ext)
            .build();

        MessageCreateParams params = converter.convert(req);

        // assistant 消息在 params.messages().get(1) (user=0, assistant=1)
        var assistantBlocks = params.messages().get(1).content().asBlockParams();
        var toolUseBlock = assistantBlocks.stream()
            .filter(b -> b.isToolUse()).findFirst().orElseThrow();
        assertThat(toolUseBlock.asToolUse().cacheControl()).isPresent();
    }

    @Test
    void shouldApplyCacheControlToAssistantTextBlock() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        // assistant 消息是 bodyMsgIdx=1,text 是 block 0(假设无 thinking)
        var ccByBlock = mapper.readTree("""
            {"1-0": {"type": "ephemeral"}}
            """);
        var ext = com.ai8493.llmproxy.model.extensions.AnthropicExtensions.builder()
            .cacheControlByBlock(ccByBlock)
            .build();
        var assistantMsg = UnifiedMessage.builder()
            .role(UnifiedMessage.Role.ASSISTANT)
            .content("answer")
            .build();
        var req = UnifiedChatRequest.builder()
            .model("claude-3-5-sonnet")
            .messages(List.of(
                UnifiedMessage.builder().role(UnifiedMessage.Role.USER).content("hi").build(),
                assistantMsg))
            .config(UnifiedGenerationConfig.builder().maxOutputTokens(4096).build())
            .anthropic(ext)
            .build();

        MessageCreateParams params = converter.convert(req);

        var assistantBlocks = params.messages().get(1).content().asBlockParams();
        var textBlock = assistantBlocks.stream().filter(b -> b.isText()).findFirst().orElseThrow();
        assertThat(textBlock.asText().cacheControl()).isPresent();
    }

    @Test
    void shouldApplyCacheControlToAssistantToolUseBlock() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        // assistant bodyMsgIdx=1, thinking block 0, text block 1, tool_use block 2
        var ccByBlock = mapper.readTree("""
            {"1-2": {"type": "ephemeral"}}
            """);
        var ext = com.ai8493.llmproxy.model.extensions.AnthropicExtensions.builder()
            .cacheControlByBlock(ccByBlock)
            .build();
        var assistantMsg = UnifiedMessage.builder()
            .role(UnifiedMessage.Role.ASSISTANT)
            .content("thinking then call")
            .reasoningContent("reasoning")
            .thinkingSignature("sig")
            .toolCalls(List.of(UnifiedToolCall.builder()
                .id("call_1")
                .type("function")
                .function(UnifiedFunctionCall.builder()
                    .name("get_weather")
                    .arguments(mapper.createObjectNode())
                    .build())
                .build()))
            .build();
        var req = UnifiedChatRequest.builder()
            .model("claude-3-5-sonnet")
            .messages(List.of(
                UnifiedMessage.builder().role(UnifiedMessage.Role.USER).content("hi").build(),
                assistantMsg))
            .config(UnifiedGenerationConfig.builder().maxOutputTokens(4096).build())
            .anthropic(ext)
            .build();

        MessageCreateParams params = converter.convert(req);

        var assistantBlocks = params.messages().get(1).content().asBlockParams();
        var toolUseBlock = assistantBlocks.stream()
            .filter(b -> b.isToolUse()).findFirst().orElseThrow();
        assertThat(toolUseBlock.asToolUse().cacheControl()).isPresent();
    }

    @Test
    void shouldRebuildOutputConfigFromExtensions() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var outputConfig = mapper.readTree("""
            {"effort": "max", "format": {"type": "json_schema", "schema": {"type": "object", "properties": {}}}}
            """);
        var ext = com.ai8493.llmproxy.model.extensions.AnthropicExtensions.builder()
            .outputConfig(outputConfig)
            .build();
        var req = UnifiedChatRequest.builder()
            .model("claude-3-5-sonnet")
            .messages(List.of(UnifiedMessage.builder()
                .role(UnifiedMessage.Role.USER).content("hi").build()))
            .config(UnifiedGenerationConfig.builder().maxOutputTokens(4096).build())
            .anthropic(ext)
            .build();

        MessageCreateParams params = converter.convert(req);

        assertThat(params.outputConfig()).isPresent();
        assertThat(params.outputConfig().get().effort().get().asString()).isEqualTo("max");
        assertThat(params.outputConfig().get().format()).isPresent();
    }

    @Test
    void shouldRebuildContextManagementViaAdditionalProperty() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var ctxMgmt = mapper.readTree("""
            {"edits": [{"type": "clear_thinking_20251015", "keep": "all"}]}
            """);
        var ext = com.ai8493.llmproxy.model.extensions.AnthropicExtensions.builder()
            .contextManagement(ctxMgmt)
            .build();
        var req = UnifiedChatRequest.builder()
            .model("claude-3-5-sonnet")
            .messages(List.of(UnifiedMessage.builder()
                .role(UnifiedMessage.Role.USER).content("hi").build()))
            .config(UnifiedGenerationConfig.builder().maxOutputTokens(4096).build())
            .anthropic(ext)
            .build();

        MessageCreateParams params = converter.convert(req);

        // context_management 走 additionalBodyProperties(SDK 顶层无 putAdditionalProperty)
        var ap = params._additionalBodyProperties();
        assertThat(ap.containsKey("context_management")).isTrue();
    }

    @Test
    void shouldRebuildMetadataUserIdFromExtensions() {
        var ext = com.ai8493.llmproxy.model.extensions.AnthropicExtensions.builder()
            .metadataUserId("user-from-ext")
            .build();
        var req = UnifiedChatRequest.builder()
            .model("claude-3-5-sonnet")
            .messages(List.of(UnifiedMessage.builder()
                .role(UnifiedMessage.Role.USER).content("hi").build()))
            .config(UnifiedGenerationConfig.builder().maxOutputTokens(4096).build())
            .anthropic(ext)
            .build();

        MessageCreateParams params = converter.convert(req);

        assertThat(params.metadata()).isPresent();
        assertThat(params.metadata().get().userId()).hasValue("user-from-ext");
    }

    @Test
    void shouldRebuildToolResultIsErrorAndCacheControl() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var ccByBlock = mapper.readTree("""
            {"call_1": {"type": "ephemeral"}}
            """);
        var ext = com.ai8493.llmproxy.model.extensions.AnthropicExtensions.builder()
            .cacheControlByBlock(ccByBlock)
            .toolResultIsError(java.util.Map.of("call_1", true))
            .build();
        var req = UnifiedChatRequest.builder()
            .model("claude-3-5-sonnet")
            .messages(List.of(
                UnifiedMessage.builder().role(UnifiedMessage.Role.USER).content("hi").build(),
                UnifiedMessage.builder()
                    .role(UnifiedMessage.Role.ASSISTANT)
                    .toolCalls(List.of(UnifiedToolCall.builder()
                        .id("call_1").type("function")
                        .function(UnifiedFunctionCall.builder()
                            .name("get_weather")
                            .arguments(mapper.createObjectNode())
                            .build())
                        .build()))
                    .build(),
                UnifiedMessage.builder()
                    .role(UnifiedMessage.Role.TOOL)
                    .toolCallId("call_1")
                    .content("error: not found")
                    .build()))
            .config(UnifiedGenerationConfig.builder().maxOutputTokens(4096).build())
            .anthropic(ext)
            .build();

        MessageCreateParams params = converter.convert(req);

        // tool_result 在 user 消息里(params.messages() 的最后一条 user 消息)
        var lastMsg = params.messages().get(params.messages().size() - 1);
        var toolResultBlock = lastMsg.content().asBlockParams().stream()
            .filter(b -> b.isToolResult()).findFirst().orElseThrow();
        assertThat(toolResultBlock.asToolResult().isError()).isPresent();
        assertThat(toolResultBlock.asToolResult().isError().get()).isTrue();
        assertThat(toolResultBlock.asToolResult().cacheControl()).isPresent();
    }

    @Test
    void shouldRebuildToolResultContentArrayFromRawBlocks() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var rawBlocks = mapper.readTree("""
            [{"type": "text", "text": "result with image"}, {"type": "image", "source": {"type": "base64", "media_type": "image/png", "data": "iVBORw0KGgo="}}]
            """);
        var ext = com.ai8493.llmproxy.model.extensions.AnthropicExtensions.builder()
            .rawToolResultBlocks(java.util.Map.of("call_1", rawBlocks))
            .build();
        var req = UnifiedChatRequest.builder()
            .model("claude-3-5-sonnet")
            .messages(List.of(
                UnifiedMessage.builder().role(UnifiedMessage.Role.USER).content("hi").build(),
                UnifiedMessage.builder()
                    .role(UnifiedMessage.Role.ASSISTANT)
                    .toolCalls(List.of(UnifiedToolCall.builder()
                        .id("call_1").type("function")
                        .function(UnifiedFunctionCall.builder()
                            .name("get_weather")
                            .arguments(mapper.createObjectNode())
                            .build())
                        .build()))
                    .build(),
                UnifiedMessage.builder()
                    .role(UnifiedMessage.Role.TOOL)
                    .toolCallId("call_1")
                    .content("fallback string")
                    .build()))
            .config(UnifiedGenerationConfig.builder().maxOutputTokens(4096).build())
            .anthropic(ext)
            .build();

        MessageCreateParams params = converter.convert(req);

        var lastMsg = params.messages().get(params.messages().size() - 1);
        var toolResultBlock = lastMsg.content().asBlockParams().stream()
            .filter(b -> b.isToolResult()).findFirst().orElseThrow();
        // content 是 blocks(含 image),不是 string
        assertThat(toolResultBlock.asToolResult().content().isPresent()).isTrue();
        var content = toolResultBlock.asToolResult().content().get();
        assertThat(content.isBlocks()).isTrue();
        assertThat(content.asBlocks().size()).isEqualTo(2);
    }

    @Test
    void shouldFallbackToStringContentWhenRawBlocksAbsent() {
        var req = UnifiedChatRequest.builder()
            .model("claude-3-5-sonnet")
            .messages(List.of(
                UnifiedMessage.builder().role(UnifiedMessage.Role.USER).content("hi").build(),
                UnifiedMessage.builder()
                    .role(UnifiedMessage.Role.ASSISTANT)
                    .toolCalls(List.of(UnifiedToolCall.builder()
                        .id("call_1").type("function")
                        .function(UnifiedFunctionCall.builder()
                            .name("get_weather")
                            .arguments(new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode())
                            .build())
                        .build()))
                    .build(),
                UnifiedMessage.builder()
                    .role(UnifiedMessage.Role.TOOL)
                    .toolCallId("call_1")
                    .content("simple string content")
                    .build()))
            .config(UnifiedGenerationConfig.builder().maxOutputTokens(4096).build())
            .build();

        MessageCreateParams params = converter.convert(req);

        var lastMsg = params.messages().get(params.messages().size() - 1);
        var toolResultBlock = lastMsg.content().asBlockParams().stream()
            .filter(b -> b.isToolResult()).findFirst().orElseThrow();
        var content = toolResultBlock.asToolResult().content().get();
        assertThat(content.isString()).isTrue();
        assertThat(content.asString()).isEqualTo("simple string content");
    }
}
