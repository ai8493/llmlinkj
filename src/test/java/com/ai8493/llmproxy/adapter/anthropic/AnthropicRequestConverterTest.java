package com.ai8493.llmproxy.adapter.anthropic;

import com.ai8493.llmproxy.model.UnifiedChatRequest;
import com.ai8493.llmproxy.model.UnifiedChatResponse;
import com.ai8493.llmproxy.model.UnifiedChoice;
import com.ai8493.llmproxy.model.UnifiedFunctionCall;
import com.ai8493.llmproxy.model.UnifiedFunctionDefinition;
import com.ai8493.llmproxy.model.UnifiedGenerationConfig;
import com.ai8493.llmproxy.model.UnifiedMessage;
import com.ai8493.llmproxy.model.UnifiedPart;
import com.ai8493.llmproxy.model.UnifiedTool;
import com.ai8493.llmproxy.model.UnifiedToolCall;
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
        // cacheControlByBlock: 消息索引 0(user)的 block 1 含 cache_control(新扁平格式 msgIdx-blockIdx)
        var ccByBlock = mapper.readTree("""
            {"0-1": {"type": "ephemeral"}}
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

    @Test
    void shouldSkipSystemBlockWhenBillingHeaderStrippedToEmpty() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        // 模拟入站 system array: 第一块是 billing header(剥离后空),第二块是正常系统指令
        var rawSystemArray = mapper.readTree("""
            [
              {"type": "text", "text": "x-anthropic-billing-header: cc_version=2.1.152.9df; cc_entrypoint=cli; cch=4ea93;"},
              {"type": "text", "text": "系统指令"}
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
        assertThat(params.system()).isPresent();
        var sys = params.system().get();
        assertThat(sys.isTextBlockParams()).isTrue();
        var blocks = sys.asTextBlockParams();
        // 只有 1 个 block(billing header 被剥离后空,跳过),内容是"系统指令"
        assertThat(blocks).hasSize(1);
        assertThat(blocks.get(0).text()).isEqualTo("系统指令");
    }

    @Test
    void shouldPreserveToolInputSchemaDollarSchemaField() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        // input_schema 含 $schema 字段
        var parameters = mapper.readTree("""
            {
              "$schema": "https://json-schema.org/draft/2020-12/schema",
              "type": "object",
              "properties": {
                "name": {"type": "string"}
              },
              "required": ["name"]
            }
            """);
        var uReq = UnifiedChatRequest.builder()
            .model("claude-3-5-sonnet-20241022")
            .messages(List.of(UnifiedMessage.builder()
                .role(UnifiedMessage.Role.USER)
                .content("hi")
                .build()))
            .tools(List.of(UnifiedTool.builder()
                .type("function")
                .function(UnifiedFunctionDefinition.builder()
                    .name("get_weather")
                    .description("获取天气")
                    .parameters(parameters)
                    .build())
                .build()))
            .config(UnifiedGenerationConfig.builder().maxOutputTokens(1024).build())
            .stream(false)
            .build();

        var converter = new AnthropicRequestConverter();
        var params = converter.convert(uReq);
        assertThat(params.tools()).isPresent();
        var tool = params.tools().get().get(0).asTool();
        // $schema 应透传到 additionalProperty
        assertThat(tool.inputSchema()._additionalProperties())
            .containsKey("$schema");
        assertThat(tool.inputSchema()._additionalProperties().get("$schema").toString())
            .contains("https://json-schema.org/draft/2020-12/schema");
    }

    @Test
    void shouldPreserveAdditionalPropertiesInInputSchema() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        // input_schema 含 additionalProperties 字段(JSON Schema 规范字段,非 SDK 内置)
        var parameters = mapper.readTree("""
            {
              "type": "object",
              "properties": {
                "name": {"type": "string"}
              },
              "required": ["name"],
              "additionalProperties": false
            }
            """);
        var uReq = UnifiedChatRequest.builder()
            .model("claude-3-5-sonnet-20241022")
            .messages(List.of(UnifiedMessage.builder()
                .role(UnifiedMessage.Role.USER)
                .content("hi")
                .build()))
            .tools(List.of(UnifiedTool.builder()
                .type("function")
                .function(UnifiedFunctionDefinition.builder()
                    .name("get_weather")
                    .description("获取天气")
                    .parameters(parameters)
                    .build())
                .build()))
            .config(UnifiedGenerationConfig.builder().maxOutputTokens(1024).build())
            .stream(false)
            .build();

        var converter = new AnthropicRequestConverter();
        var params = converter.convert(uReq);
        assertThat(params.tools()).isPresent();
        var tool = params.tools().get().get(0).asTool();
        // additionalProperties 应透传到 additionalProperty
        assertThat(tool.inputSchema()._additionalProperties())
            .containsKey("additionalProperties");
        assertThat(tool.inputSchema()._additionalProperties().get("additionalProperties").toString())
            .contains("false");
    }

    @Test
    void shouldOmitSignatureWhenThinkingSignatureIsNull() {
        // 入站 ASSISTANT 含 reasoningContent 但 thinkingSignature 为 null
        // 修复前: signature 被强制设为空串,出站 body 含 "signature":"" 导致后端 400
        // 修复后: signature 用 JsonMissing,SDK 序列化时 @ExcludeMissing 忽略,不出现在出站 body
        var uReq = UnifiedChatRequest.builder()
            .model("claude-3-5-sonnet-20241022")
            .messages(List.of(
                UnifiedMessage.builder()
                    .role(UnifiedMessage.Role.USER)
                    .content("hi")
                    .build(),
                UnifiedMessage.builder()
                    .role(UnifiedMessage.Role.ASSISTANT)
                    .reasoningContent("思考内容")
                    // thinkingSignature 留 null
                    .build()
            ))
            .config(UnifiedGenerationConfig.builder().maxOutputTokens(1024).build())
            .stream(false)
            .build();

        var converter = new AnthropicRequestConverter();
        var params = converter.convert(uReq);
        // 取出 ASSISTANT 消息(索引 1)的 thinking block
        var msg = params.messages().get(1);
        assertThat(msg.role()).isEqualTo(com.anthropic.models.messages.MessageParam.Role.ASSISTANT);
        var blocks = msg.content().asBlockParams();
        assertThat(blocks).hasSize(1);
        assertThat(blocks.get(0).isThinking()).isTrue();
        var thinkingBlock = blocks.get(0).asThinking();
        // signature 应是 JsonMissing(不出现在出站 body)
        assertThat(thinkingBlock._signature().isMissing()).isTrue();
    }

    @Test
    void shouldRebuildThinkingBlockWhenReasoningContentEmptyButSignaturePresent() {
        // 入站 ASSISTANT: reasoningContent="" (空字符串, deepseek 后端有时返回空 thinking) + thinkingSignature 有值
        // 修复前: hasReasoning = "" != null && !"".isEmpty() = false, thinking block 不重建, signature 丢失
        // 修复后: hasReasoning = reasoningContent != null || thinkingSignature != null = true, thinking block 重建
        // 场景: deepseek 链路 122 个 thinking="" 的 block 全部丢失(含 signature)
        var uReq = UnifiedChatRequest.builder()
            .model("claude-3-5-sonnet-20241022")
            .messages(List.of(
                UnifiedMessage.builder()
                    .role(UnifiedMessage.Role.USER)
                    .content("hi")
                    .build(),
                UnifiedMessage.builder()
                    .role(UnifiedMessage.Role.ASSISTANT)
                    .reasoningContent("")  // 空字符串(deepseek 后端返回空 thinking)
                    .thinkingSignature("sig_abc")  // 有 signature
                    .build()
            ))
            .config(UnifiedGenerationConfig.builder().maxOutputTokens(1024).build())
            .stream(false)
            .build();

        var converter = new AnthropicRequestConverter();
        var params = converter.convert(uReq);
        var msg = params.messages().get(1);
        assertThat(msg.role()).isEqualTo(com.anthropic.models.messages.MessageParam.Role.ASSISTANT);
        var blocks = msg.content().asBlockParams();
        // 修复后: 应有 thinking block(含 signature)
        assertThat(blocks).hasSize(1);
        assertThat(blocks.get(0).isThinking()).isTrue();
        var thinkingBlock = blocks.get(0).asThinking();
        assertThat(thinkingBlock.thinking()).isEqualTo("");
        assertThat(thinkingBlock._signature().asString()).hasValue("sig_abc");
    }

    @Test
    void shouldRebuildThinkingBlockWhenReasoningContentNullButSignaturePresent() {
        // 入站 ASSISTANT: reasoningContent=null + thinkingSignature 有值
        // 防御性测试: 即使 reasoningContent 为 null(SDK 把空字符串解析成 Optional.empty),
        // 只要 thinkingSignature 有值就应该重建 thinking block,thinking 字段设为 ""
        var uReq = UnifiedChatRequest.builder()
            .model("claude-3-5-sonnet-20241022")
            .messages(List.of(
                UnifiedMessage.builder()
                    .role(UnifiedMessage.Role.USER)
                    .content("hi")
                    .build(),
                UnifiedMessage.builder()
                    .role(UnifiedMessage.Role.ASSISTANT)
                    // reasoningContent 留 null
                    .thinkingSignature("sig_xyz")
                    .build()
            ))
            .config(UnifiedGenerationConfig.builder().maxOutputTokens(1024).build())
            .stream(false)
            .build();

        var converter = new AnthropicRequestConverter();
        var params = converter.convert(uReq);
        var msg = params.messages().get(1);
        assertThat(msg.role()).isEqualTo(com.anthropic.models.messages.MessageParam.Role.ASSISTANT);
        var blocks = msg.content().asBlockParams();
        assertThat(blocks).hasSize(1);
        assertThat(blocks.get(0).isThinking()).isTrue();
        var thinkingBlock = blocks.get(0).asThinking();
        assertThat(thinkingBlock.thinking()).isEqualTo("");
        assertThat(thinkingBlock._signature().asString()).hasValue("sig_xyz");
    }

    @Test
    void shouldMergeToolResultWithPendingUserTextToAvoidConsecutiveUserMessages() {
        // Bug 1 复现:入站 IR 是 [user(text), TOOL(tool_result)] (来自入站 user [tool_result, text] 的拆分)
        // 修复前: convert 输出 [user(text), user(tool_result)] -- 连续两条 user,违反 anthropic 协议(minimax 400)
        // 修复后: convert 输出 [user([tool_result, text])] -- 合并成一条 user 消息
        var uReq = UnifiedChatRequest.builder()
            .model("claude-3-5-sonnet-20241022")
            .messages(List.of(
                UnifiedMessage.builder()
                    .role(UnifiedMessage.Role.USER)
                    .content("继续")
                    .build(),
                UnifiedMessage.builder()
                    .role(UnifiedMessage.Role.TOOL)
                    .toolCallId("t1")
                    .content("r1")
                    .build()
            ))
            .config(UnifiedGenerationConfig.builder().maxOutputTokens(1024).build())
            .stream(false)
            .build();

        var converter = new AnthropicRequestConverter();
        var params = converter.convert(uReq);
        // 应只有 1 条 user 消息(合并 tool_result + text),不是 2 条
        assertThat(params.messages()).hasSize(1);
        var msg = params.messages().get(0);
        assertThat(msg.role()).isEqualTo(com.anthropic.models.messages.MessageParam.Role.USER);
        var blocks = msg.content().asBlockParams();
        assertThat(blocks).hasSize(2);
        // 第 1 个 block 是 tool_result
        assertThat(blocks.get(0).isToolResult()).isTrue();
        assertThat(blocks.get(0).asToolResult().toolUseId()).isEqualTo("t1");
        // 第 2 个 block 是 text
        assertThat(blocks.get(1).isText()).isTrue();
        assertThat(blocks.get(1).asText().text()).isEqualTo("继续");
    }

    @Test
    void shouldMergeToolResultWithPendingUserBeforeAssistantMessage() throws Exception {
        // Bug 1 场景 2:入站 IR 是 [user(text), TOOL(tool_result), assistant(tool_use)]
        // (来自入站 user [tool_result, text] + assistant [tool_use] 的拆分)
        // 修复前: convert 输出 [user(text), user(tool_result), assistant(tool_use)] -- 连续两条 user
        // 修复后: convert 输出 [user([tool_result, text]), assistant([tool_use])]
        var args = new com.fasterxml.jackson.databind.ObjectMapper()
            .readTree("{\"type\":\"object\",\"properties\":{}}");
        var uReq = UnifiedChatRequest.builder()
            .model("claude-3-5-sonnet-20241022")
            .messages(List.of(
                UnifiedMessage.builder()
                    .role(UnifiedMessage.Role.USER)
                    .content("继续")
                    .build(),
                UnifiedMessage.builder()
                    .role(UnifiedMessage.Role.TOOL)
                    .toolCallId("t1")
                    .content("r1")
                    .build(),
                UnifiedMessage.builder()
                    .role(UnifiedMessage.Role.ASSISTANT)
                    .toolCalls(List.of(UnifiedToolCall.builder()
                        .id("t1")
                        .type("function")
                        .function(UnifiedFunctionCall.builder()
                            .name("f")
                            .arguments(args)
                            .build())
                        .build()))
                    .build()
            ))
            .config(UnifiedGenerationConfig.builder().maxOutputTokens(1024).build())
            .stream(false)
            .build();

        var converter = new AnthropicRequestConverter();
        var params = converter.convert(uReq);
        // 应有 2 条消息:user(合并) + assistant
        assertThat(params.messages()).hasSize(2);
        var userMsg = params.messages().get(0);
        assertThat(userMsg.role()).isEqualTo(com.anthropic.models.messages.MessageParam.Role.USER);
        var userBlocks = userMsg.content().asBlockParams();
        assertThat(userBlocks).hasSize(2);
        assertThat(userBlocks.get(0).isToolResult()).isTrue();
        assertThat(userBlocks.get(1).isText()).isTrue();
        assertThat(userBlocks.get(1).asText().text()).isEqualTo("继续");
        var assistantMsg = params.messages().get(1);
        assertThat(assistantMsg.role()).isEqualTo(com.anthropic.models.messages.MessageParam.Role.ASSISTANT);
    }
}
