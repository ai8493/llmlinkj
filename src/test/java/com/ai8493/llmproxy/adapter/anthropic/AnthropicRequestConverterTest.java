package com.ai8493.llmproxy.adapter.anthropic;

import com.ai8493.llmproxy.model.UnifiedChatRequest;
import com.ai8493.llmproxy.model.UnifiedChatResponse;
import com.ai8493.llmproxy.model.UnifiedChoice;
import com.ai8493.llmproxy.model.UnifiedFunctionDefinition;
import com.ai8493.llmproxy.model.UnifiedGenerationConfig;
import com.ai8493.llmproxy.model.UnifiedMessage;
import com.ai8493.llmproxy.model.UnifiedPart;
import com.ai8493.llmproxy.model.UnifiedTool;
import com.ai8493.llmproxy.model.UnifiedToolChoice;
import com.ai8493.llmproxy.model.UnifiedUsage;
import com.ai8493.llmproxy.model.extensions.AnthropicExtensions;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AnthropicRequestConverterTest {

    @Test
    void shouldMapTopKToParams() {
        var config = UnifiedGenerationConfig.builder()
            .maxOutputTokens(1024)
            .topK(40)
            .build();
        var uReq = UnifiedChatRequest.builder()
            .model("claude-3-5-sonnet-20241022")
            .messages(List.of(UnifiedMessage.builder()
                .role(UnifiedMessage.Role.USER)
                .content("hi")
                .build()))
            .config(config)
            .stream(false)
            .build();

        var converter = new AnthropicRequestConverter();
        var params = converter.convert(uReq);
        assertThat(params.topK()).hasValue(40L);
    }

    @Test
    void shouldPreserveSystemBlockCacheControl() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        // 模拟入站 system array(含 cache_control)
        var rawSystemArray = mapper.readTree("""
            [
              {"type": "text", "text": "系统指令", "cache_control": {"type": "ephemeral"}}
            ]
            """);
        var anthropicExt = AnthropicExtensions.builder()
            .rawSystemArray(rawSystemArray)
            .build();
        var uReq = UnifiedChatRequest.builder()
            .model("claude-3-5-sonnet-20241022")
            .messages(List.of(UnifiedMessage.builder()
                .role(UnifiedMessage.Role.USER)
                .content("hi")
                .build()))
            .anthropic(anthropicExt)
            .config(UnifiedGenerationConfig.builder().maxOutputTokens(1024).build())
            .stream(false)
            .build();

        var converter = new AnthropicRequestConverter();
        var params = converter.convert(uReq);
        // system 应含 cache_control
        assertThat(params.system()).isPresent();
        var sys = params.system().get();
        assertThat(sys.isTextBlockParams()).isTrue();
        var sysBlock = sys.asTextBlockParams().get(0);
        assertThat(sysBlock.cacheControl()).isPresent();
    }

    @Test
    void shouldPreserveMessageBlockCacheControl() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        // cacheControlByBlock: 消息索引 0(user)的 block 0 含 cache_control
        var ccByBlock = mapper.readTree("""
            {"0": [null, {"type": "ephemeral"}]}
            """);
        var anthropicExt = AnthropicExtensions.builder()
            .cacheControlByBlock(ccByBlock)
            .build();
        var uReq = UnifiedChatRequest.builder()
            .model("claude-3-5-sonnet-20241022")
            .messages(List.of(
                UnifiedMessage.builder()
                    .role(UnifiedMessage.Role.USER)
                    .content("带缓存的文本")
                    .parts(List.of(
                        new UnifiedPart.TextPart("前缀"),
                        new UnifiedPart.TextPart("要缓存的内容")
                    ))
                    .build()
            ))
            .anthropic(anthropicExt)
            .config(UnifiedGenerationConfig.builder().maxOutputTokens(1024).build())
            .stream(false)
            .build();

        var converter = new AnthropicRequestConverter();
        var params = converter.convert(uReq);
        // 第二个 user block 应含 cache_control
        var msg = params.messages().get(0);
        assertThat(msg.role()).isEqualTo(com.anthropic.models.messages.MessageParam.Role.USER);
        var blocks = msg.content().asBlockParams();
        assertThat(blocks).hasSize(2);
        assertThat(blocks.get(1).asText().cacheControl()).isPresent();
    }

    @Test
    void shouldPreserveBuiltinToolBash() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var rawTool = mapper.readTree("""
            {"type": "bash_20250124", "name": "bash"}
            """);
        var uReq = UnifiedChatRequest.builder()
            .model("claude-3-5-sonnet-20241022")
            .messages(List.of(UnifiedMessage.builder()
                .role(UnifiedMessage.Role.USER)
                .content("执行命令")
                .build()))
            .tools(List.of(UnifiedTool.builder()
                .type("bash_20250124")
                .rawTool(rawTool)
                .build()))
            .config(UnifiedGenerationConfig.builder().maxOutputTokens(1024).build())
            .stream(false)
            .build();

        var converter = new AnthropicRequestConverter();
        var params = converter.convert(uReq);
        assertThat(params.tools()).isPresent();
        var tools = params.tools().get();
        assertThat(tools).hasSize(1);
        // 应是 bash 工具(非 function)
        assertThat(tools.get(0).isBash20250124()).isTrue();
    }

    @Test
    void shouldMapDisableParallelToolUse() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var parameters = mapper.readTree("""
            {"type": "object", "properties": {}}
            """);
        var anthropicExt = AnthropicExtensions.builder()
            .disableParallelToolUse(true)
            .build();
        var uReq = UnifiedChatRequest.builder()
            .model("claude-3-5-sonnet-20241022")
            .messages(List.of(UnifiedMessage.builder()
                .role(UnifiedMessage.Role.USER)
                .content("hi")
                .build()))
            .tools(List.of(UnifiedTool.builder()
                .type("function")
                .function(UnifiedFunctionDefinition.builder()
                    .name("f")
                    .parameters(parameters)
                    .build())
                .build()))
            .toolChoice(UnifiedToolChoice.Auto.builder().build())
            .anthropic(anthropicExt)
            .config(UnifiedGenerationConfig.builder().maxOutputTokens(1024).build())
            .stream(false)
            .build();

        var converter = new AnthropicRequestConverter();
        var params = converter.convert(uReq);
        var tc = params.toolChoice().get();
        assertThat(tc.isAuto()).isTrue();
        assertThat(tc.asAuto().disableParallelToolUse()).hasValue(true);
    }

    @Test
    void shouldMapMetadataUserIdFromAnthropicExtensions() {
        var anthropicExt = AnthropicExtensions.builder()
            .metadataUserId("user-from-ext-123")
            .build();
        var uReq = UnifiedChatRequest.builder()
            .model("claude-3-5-sonnet-20241022")
            .messages(List.of(UnifiedMessage.builder()
                .role(UnifiedMessage.Role.USER)
                .content("hi")
                .build()))
            .anthropic(anthropicExt)
            .config(UnifiedGenerationConfig.builder()
                .maxOutputTokens(1024)
                .user("user-from-config-456")
                .build())
            .stream(false)
            .build();

        var converter = new AnthropicRequestConverter();
        var params = converter.convert(uReq);
        assertThat(params.metadata()).isPresent();
        assertThat(params.metadata().get().userId()).hasValue("user-from-ext-123");
    }

    @Test
    void shouldFallbackMetadataUserIdToConfigUser() {
        // AnthropicExtensions.metadataUserId 为 null 时,回退到 config.user
        var anthropicExt = AnthropicExtensions.builder().build();
        var uReq = UnifiedChatRequest.builder()
            .model("claude-3-5-sonnet-20241022")
            .messages(List.of(UnifiedMessage.builder()
                .role(UnifiedMessage.Role.USER)
                .content("hi")
                .build()))
            .anthropic(anthropicExt)
            .config(UnifiedGenerationConfig.builder()
                .maxOutputTokens(1024)
                .user("user-from-config-456")
                .build())
            .stream(false)
            .build();

        var converter = new AnthropicRequestConverter();
        var params = converter.convert(uReq);
        assertThat(params.metadata()).isPresent();
        assertThat(params.metadata().get().userId()).hasValue("user-from-config-456");
    }

    @Test
    void shouldUseStopReasonOriginalValueWhenValidAnthropic() throws Exception {
        var adapter = new AnthropicProtocolAdapter();
        // IR finishReason 是 Anthropic 合法原值 "end_turn"(小写),出站应直接用
        var uResp = UnifiedChatResponse.builder()
            .id("msg-1")
            .model("claude-3-5-sonnet")
            .object("message")
            .created(1700000000L)
            .choices(List.of(UnifiedChoice.builder()
                .index(0)
                .message(UnifiedMessage.builder()
                    .role(UnifiedMessage.Role.ASSISTANT)
                    .content("hi")
                    .build())
                .finishReason("end_turn")
                .build()))
            .usage(UnifiedUsage.builder()
                .promptTokens(1).completionTokens(1).totalTokens(2).build())
            .build();

        byte[] out = adapter.fromUnifiedResponse(uResp);
        var json = new com.fasterxml.jackson.databind.ObjectMapper().readTree(out);
        String sr = json.path("stop_reason").asText("");
        assertThat(sr).isEqualTo("end_turn");
    }
}
