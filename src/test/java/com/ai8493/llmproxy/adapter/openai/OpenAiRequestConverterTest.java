package com.ai8493.llmproxy.adapter.openai;

import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.ChatCompletionMessageParam;
import com.ai8493.llmproxy.config.BackendConfig;
import com.ai8493.llmproxy.model.*;
import com.ai8493.llmproxy.model.extensions.ThinkingConfig;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OpenAiRequestConverterTest {

    private final OpenAiRequestConverter converter = new OpenAiRequestConverter();

    @Test
    void shouldConvertUserMessage() {
        var req = UnifiedChatRequest.builder()
            .model("gpt-4o")
            .messages(List.of(UnifiedMessage.builder()
                .role(UnifiedMessage.Role.USER)
                .content("你好")
                .build()))
            .stream(false)
            .build();

        ChatCompletionCreateParams params = converter.convert(req);

        assertThat(params.model().asString()).isEqualTo("gpt-4o");
        assertThat(params.messages()).hasSize(1);
        ChatCompletionMessageParam msg = params.messages().get(0);
        assertThat(msg.isUser()).isTrue();
        assertThat(msg.asUser().content().asText()).isEqualTo("你好");
    }

    @Test
    void shouldConvertAllRoleTypes() {
        var req = UnifiedChatRequest.builder()
            .model("gpt-4o")
            .messages(List.of(
                UnifiedMessage.builder()
                    .role(UnifiedMessage.Role.SYSTEM)
                    .content("系统提示")
                    .build(),
                UnifiedMessage.builder()
                    .role(UnifiedMessage.Role.USER)
                    .content("用户问题")
                    .build(),
                UnifiedMessage.builder()
                    .role(UnifiedMessage.Role.ASSISTANT)
                    .content("助手回答")
                    .build(),
                UnifiedMessage.builder()
                    .role(UnifiedMessage.Role.TOOL)
                    .content("工具结果")
                    .toolCallId("call_123")
                    .build()
            ))
            .stream(false)
            .build();

        ChatCompletionCreateParams params = converter.convert(req);

        assertThat(params.messages()).hasSize(4);
        assertThat(params.messages().get(0).isSystem()).isTrue();
        assertThat(params.messages().get(1).isUser()).isTrue();
        assertThat(params.messages().get(2).isAssistant()).isTrue();
        assertThat(params.messages().get(3).isTool()).isTrue();
        assertThat(params.messages().get(3).asTool().toolCallId()).isEqualTo("call_123");
    }

    @Test
    void shouldConvertTools() {
        var req = UnifiedChatRequest.builder()
            .model("gpt-4o")
            .messages(List.of(UnifiedMessage.builder()
                .role(UnifiedMessage.Role.USER)
                .content("查询天气")
                .build()))
            .tools(List.of(UnifiedTool.builder()
                .type("function")
                .function(UnifiedFunctionDefinition.builder()
                    .name("get_weather")
                    .description("获取天气")
                    .build())
                .build()))
            .stream(false)
            .build();

        ChatCompletionCreateParams params = converter.convert(req);

        assertThat(params.tools()).isPresent();
        assertThat(params.tools().get()).hasSize(1);
        var tool = params.tools().get().get(0);
        assertThat(tool.isFunction()).isTrue();
        assertThat(tool.asFunction().function().name()).isEqualTo("get_weather");
        assertThat(tool.asFunction().function().description()).hasValue("获取天气");
    }

    @Test
    void shouldConvertToolChoice() {
        var autoReq = UnifiedChatRequest.builder()
            .model("gpt-4o")
            .messages(List.of(UnifiedMessage.builder()
                .role(UnifiedMessage.Role.USER)
                .content("hi")
                .build()))
            .tools(List.of(UnifiedTool.builder()
                .type("function")
                .function(UnifiedFunctionDefinition.builder()
                    .name("get_weather")
                    .description("获取天气")
                    .build())
                .build()))
            .toolChoice(UnifiedToolChoice.Auto.builder().build())
            .stream(false)
            .build();

        var params = converter.convert(autoReq);
        assertThat(params.toolChoice()).isPresent();
        assertThat(params.toolChoice().get().isAuto()).isTrue();
    }

    @Test
    void shouldNotSetToolChoiceWhenToolsIsEmpty() {
        // P3-12: 无 tools 时不应发送 tool_choice,避免后端 400
        var req = UnifiedChatRequest.builder()
            .model("gpt-4o")
            .messages(List.of(UnifiedMessage.builder()
                .role(UnifiedMessage.Role.USER)
                .content("hi")
                .build()))
            .toolChoice(UnifiedToolChoice.Auto.builder().build())
            .stream(false)
            .build();

        var params = converter.convert(req);
        assertThat(params.toolChoice()).isEmpty();
    }

    @Test
    void shouldNotSetParallelToolCallsWhenToolsIsEmpty() {
        // P3-12: 无 tools 时不应发送 parallel_tool_calls
        var req = UnifiedChatRequest.builder()
            .model("gpt-4o")
            .messages(List.of(UnifiedMessage.builder()
                .role(UnifiedMessage.Role.USER)
                .content("hi")
                .build()))
            .config(UnifiedGenerationConfig.builder()
                .parallelToolCalls(true)
                .build())
            .stream(false)
            .build();

        var params = converter.convert(req);
        assertThat(params.parallelToolCalls()).isEmpty();
    }

    @Test
    void shouldSetParallelToolCallsWhenToolsPresent() {
        var req = UnifiedChatRequest.builder()
            .model("gpt-4o")
            .messages(List.of(UnifiedMessage.builder()
                .role(UnifiedMessage.Role.USER)
                .content("hi")
                .build()))
            .tools(List.of(UnifiedTool.builder()
                .type("function")
                .function(UnifiedFunctionDefinition.builder()
                    .name("get_weather")
                    .description("获取天气")
                    .build())
                .build()))
            .config(UnifiedGenerationConfig.builder()
                .parallelToolCalls(true)
                .build())
            .stream(false)
            .build();

        var params = converter.convert(req);
        assertThat(params.parallelToolCalls()).isPresent();
    }

    @Test
    void shouldConvertGenerationConfig() {
        var req = UnifiedChatRequest.builder()
            .model("gpt-4o")
            .messages(List.of(UnifiedMessage.builder()
                .role(UnifiedMessage.Role.USER)
                .content("hi")
                .build()))
            .config(UnifiedGenerationConfig.builder()
                .temperature(0.7)
                .topP(0.9)
                .maxOutputTokens(1024)
                .stopSequences(List.of("END"))
                .build())
            .stream(false)
            .build();

        ChatCompletionCreateParams params = converter.convert(req);

        assertThat(params.temperature()).hasValue(0.7);
        assertThat(params.topP()).hasValue(0.9);
        assertThat(params.maxTokens()).hasValue(1024L);
        assertThat(params.stop()).isPresent();
    }

    @Test
    void shouldConvertAssistantMessageWithToolCalls() {
        var req = UnifiedChatRequest.builder()
            .model("gpt-4o")
            .messages(List.of(UnifiedMessage.builder()
                .role(UnifiedMessage.Role.ASSISTANT)
                .toolCalls(List.of(UnifiedToolCall.builder()
                    .id("call_abc")
                    .type("function")
                    .function(UnifiedFunctionCall.builder()
                        .name("get_weather")
                        .build())
                    .build()))
                .build()))
            .stream(false)
            .build();

        ChatCompletionCreateParams params = converter.convert(req);

        assertThat(params.messages()).hasSize(1);
        var msg = params.messages().get(0);
        assertThat(msg.isAssistant()).isTrue();
        assertThat(msg.asAssistant().toolCalls()).isPresent();
        assertThat(msg.asAssistant().toolCalls().get()).hasSize(1);
        assertThat(msg.asAssistant().toolCalls().get().get(0).asFunction().id()).isEqualTo("call_abc");
    }

    // ===== P0-1: thinking -> reasoning_effort 映射 =====

    @Test
    void shouldResolveReasoningEffortFromThinkingEnabledWithMediumBudget() {
        var req = UnifiedChatRequest.builder()
            .model("o3-mini")
            .messages(simpleUserMessage())
            .config(UnifiedGenerationConfig.builder()
                .thinkingConfig(ThinkingConfig.builder()
                    .type("enabled")
                    .budgetTokens(8000)
                    .build())
                .build())
            .stream(false)
            .build();

        ChatCompletionCreateParams params = converter.convert(req);

        assertThat(params.reasoningEffort()).isPresent();
        assertThat(params.reasoningEffort().get().toString()).isEqualTo("medium");
    }

    @Test
    void shouldResolveReasoningEffortFromThinkingAdaptive() {
        var req = UnifiedChatRequest.builder()
            .model("o3-mini")
            .messages(simpleUserMessage())
            .config(UnifiedGenerationConfig.builder()
                .thinkingConfig(ThinkingConfig.builder()
                    .type("adaptive")
                    .build())
                .build())
            .stream(false)
            .build();

        ChatCompletionCreateParams params = converter.convert(req);

        assertThat(params.reasoningEffort()).isPresent();
        assertThat(params.reasoningEffort().get().toString()).isEqualTo("xhigh");
    }

    @Test
    void shouldResolveReasoningEffortFromThinkingEnabledWithSmallBudget() {
        var req = UnifiedChatRequest.builder()
            .model("o3-mini")
            .messages(simpleUserMessage())
            .config(UnifiedGenerationConfig.builder()
                .thinkingConfig(ThinkingConfig.builder()
                    .type("enabled")
                    .budgetTokens(2000)
                    .build())
                .build())
            .stream(false)
            .build();

        ChatCompletionCreateParams params = converter.convert(req);

        assertThat(params.reasoningEffort()).isPresent();
        assertThat(params.reasoningEffort().get().toString()).isEqualTo("low");
    }

    @Test
    void shouldResolveReasoningEffortFromThinkingEnabledWithLargeBudget() {
        var req = UnifiedChatRequest.builder()
            .model("o3-mini")
            .messages(simpleUserMessage())
            .config(UnifiedGenerationConfig.builder()
                .thinkingConfig(ThinkingConfig.builder()
                    .type("enabled")
                    .budgetTokens(20000)
                    .build())
                .build())
            .stream(false)
            .build();

        ChatCompletionCreateParams params = converter.convert(req);

        assertThat(params.reasoningEffort()).isPresent();
        assertThat(params.reasoningEffort().get().toString()).isEqualTo("high");
    }

    @Test
    void shouldResolveReasoningEffortFromThinkingEnabledWithoutBudget() {
        var req = UnifiedChatRequest.builder()
            .model("o3-mini")
            .messages(simpleUserMessage())
            .config(UnifiedGenerationConfig.builder()
                .thinkingConfig(ThinkingConfig.builder()
                    .type("enabled")
                    .build())
                .build())
            .stream(false)
            .build();

        ChatCompletionCreateParams params = converter.convert(req);

        assertThat(params.reasoningEffort()).isPresent();
        assertThat(params.reasoningEffort().get().toString()).isEqualTo("high");
    }

    @Test
    void shouldNotSetReasoningEffortWhenThinkingDisabled() {
        var req = UnifiedChatRequest.builder()
            .model("o3-mini")
            .messages(simpleUserMessage())
            .config(UnifiedGenerationConfig.builder()
                .thinkingConfig(ThinkingConfig.builder()
                    .type("disabled")
                    .build())
                .build())
            .stream(false)
            .build();

        ChatCompletionCreateParams params = converter.convert(req);

        assertThat(params.reasoningEffort()).isEmpty();
    }

    @Test
    void shouldNotSetReasoningEffortWhenThinkingAbsent() {
        var req = UnifiedChatRequest.builder()
            .model("o3-mini")
            .messages(simpleUserMessage())
            .config(UnifiedGenerationConfig.builder().build())
            .stream(false)
            .build();

        ChatCompletionCreateParams params = converter.convert(req);

        assertThat(params.reasoningEffort()).isEmpty();
    }

    @Test
    void shouldPreferExplicitReasoningEffortOverThinking() {
        var req = UnifiedChatRequest.builder()
            .model("o3-mini")
            .messages(simpleUserMessage())
            .config(UnifiedGenerationConfig.builder()
                .reasoningEffort("low")
                .thinkingConfig(ThinkingConfig.builder()
                    .type("enabled")
                    .budgetTokens(20000)
                    .build())
                .build())
            .stream(false)
            .build();

        ChatCompletionCreateParams params = converter.convert(req);

        assertThat(params.reasoningEffort()).isPresent();
        assertThat(params.reasoningEffort().get().toString()).isEqualTo("low");
    }

    @Test
    void shouldNotSetReasoningEffortForNonReasoningModel() {
        var req = UnifiedChatRequest.builder()
            .model("gpt-4o")
            .messages(simpleUserMessage())
            .config(UnifiedGenerationConfig.builder()
                .thinkingConfig(ThinkingConfig.builder()
                    .type("enabled")
                    .budgetTokens(8000)
                    .build())
                .build())
            .stream(false)
            .build();

        ChatCompletionCreateParams params = converter.convert(req);

        assertThat(params.reasoningEffort()).isEmpty();
    }

    @Test
    void shouldSetReasoningEffortForGpt5SeriesModel() {
        var req = UnifiedChatRequest.builder()
            .model("gpt-5")
            .messages(simpleUserMessage())
            .config(UnifiedGenerationConfig.builder()
                .reasoningEffort("high")
                .build())
            .stream(false)
            .build();

        ChatCompletionCreateParams params = converter.convert(req);

        assertThat(params.reasoningEffort()).isPresent();
        assertThat(params.reasoningEffort().get().toString()).isEqualTo("high");
    }

    // ===== P0-2: o-series 用 max_completion_tokens =====

    @Test
    void shouldUseMaxCompletionTokensForOSeriesModels() {
        var req = UnifiedChatRequest.builder()
            .model("o3-mini")
            .messages(simpleUserMessage())
            .config(UnifiedGenerationConfig.builder()
                .maxOutputTokens(4096)
                .build())
            .stream(false)
            .build();

        ChatCompletionCreateParams params = converter.convert(req);

        assertThat(params.maxCompletionTokens()).hasValue(4096L);
        assertThat(params.maxTokens()).isEmpty();
    }

    @Test
    void shouldUseMaxTokensForNonOSeriesModels() {
        var req = UnifiedChatRequest.builder()
            .model("gpt-4o")
            .messages(simpleUserMessage())
            .config(UnifiedGenerationConfig.builder()
                .maxOutputTokens(1024)
                .build())
            .stream(false)
            .build();

        ChatCompletionCreateParams params = converter.convert(req);

        assertThat(params.maxTokens()).hasValue(1024L);
        assertThat(params.maxCompletionTokens()).isEmpty();
    }

    private List<UnifiedMessage> simpleUserMessage() {
        return List.of(UnifiedMessage.builder()
            .role(UnifiedMessage.Role.USER)
            .content("hi")
            .build());
    }

    // ===== P0-3: reasoning.effort provider 配置(BackendConfig 扩展) =====

    private BackendConfig backendWithReasoning(String mode) {
        return new BackendConfig(
            "openai", "sk-test", "http://localhost:8090/v1",
            "o3-mini", null,
            Duration.ofSeconds(5), Duration.ofSeconds(10), Duration.ofSeconds(5),
            new BackendConfig.PoolConfig(5, Duration.ofMinutes(1)),
            new BackendConfig.ReasoningConfig(mode, null, null, null));
    }

    @Test
    void shouldMapEffortToMaxForDeepseekMode() {
        var req = UnifiedChatRequest.builder()
            .model("o3-mini")
            .messages(simpleUserMessage())
            .config(UnifiedGenerationConfig.builder()
                .reasoningEffort("xhigh")
                .build())
            .stream(false)
            .build();

        ChatCompletionCreateParams params = converter.convert(req, backendWithReasoning("deepseek"));

        assertThat(params.reasoningEffort()).isPresent();
        assertThat(params.reasoningEffort().get().toString()).isEqualTo("max");
    }

    @Test
    void shouldMapEffortToHighForLowHighMode() {
        var req = UnifiedChatRequest.builder()
            .model("o3-mini")
            .messages(simpleUserMessage())
            .config(UnifiedGenerationConfig.builder()
                .reasoningEffort("medium")
                .build())
            .stream(false)
            .build();

        ChatCompletionCreateParams params = converter.convert(req, backendWithReasoning("low_high"));

        assertThat(params.reasoningEffort()).isPresent();
        assertThat(params.reasoningEffort().get().toString()).isEqualTo("high");
    }

    @Test
    void shouldMapEffortToLowForLowHighModeWithLowInput() {
        var req = UnifiedChatRequest.builder()
            .model("o3-mini")
            .messages(simpleUserMessage())
            .config(UnifiedGenerationConfig.builder()
                .reasoningEffort("low")
                .build())
            .stream(false)
            .build();

        ChatCompletionCreateParams params = converter.convert(req, backendWithReasoning("low_high"));

        assertThat(params.reasoningEffort()).isPresent();
        assertThat(params.reasoningEffort().get().toString()).isEqualTo("low");
    }

    @Test
    void shouldMapEffortToXhighForOpenrouterModeWithMaxInput() {
        var req = UnifiedChatRequest.builder()
            .model("gpt-5")
            .messages(simpleUserMessage())
            .config(UnifiedGenerationConfig.builder()
                .reasoningEffort("max")
                .build())
            .stream(false)
            .build();

        ChatCompletionCreateParams params = converter.convert(req, backendWithReasoning("openrouter"));

        assertThat(params.reasoningEffort()).isPresent();
        assertThat(params.reasoningEffort().get().toString()).isEqualTo("xhigh");
    }

    @Test
    void shouldPassthroughEffortForPassthroughMode() {
        var req = UnifiedChatRequest.builder()
            .model("o3-mini")
            .messages(simpleUserMessage())
            .config(UnifiedGenerationConfig.builder()
                .reasoningEffort("high")
                .build())
            .stream(false)
            .build();

        ChatCompletionCreateParams params = converter.convert(req, backendWithReasoning("passthrough"));

        assertThat(params.reasoningEffort()).isPresent();
        assertThat(params.reasoningEffort().get().toString()).isEqualTo("high");
    }

    @Test
    void shouldUseConfigEffortDefaultWhenClientAbsent() {
        BackendConfig cfg = new BackendConfig(
            "openai", "sk-test", "http://localhost:8090/v1",
            "o3-mini", null,
            Duration.ofSeconds(5), Duration.ofSeconds(10), Duration.ofSeconds(5),
            new BackendConfig.PoolConfig(5, Duration.ofMinutes(1)),
            new BackendConfig.ReasoningConfig("passthrough", "medium", null, null));

        var req = UnifiedChatRequest.builder()
            .model("o3-mini")
            .messages(simpleUserMessage())
            .config(UnifiedGenerationConfig.builder().build())
            .stream(false)
            .build();

        ChatCompletionCreateParams params = converter.convert(req, cfg);

        assertThat(params.reasoningEffort()).isPresent();
        assertThat(params.reasoningEffort().get().toString()).isEqualTo("medium");
    }

    @Test
    void shouldUseConfigThinkingDefaultWhenClientAndEffortDefaultAbsent() {
        BackendConfig cfg = new BackendConfig(
            "openai", "sk-test", "http://localhost:8090/v1",
            "o3-mini", null,
            Duration.ofSeconds(5), Duration.ofSeconds(10), Duration.ofSeconds(5),
            new BackendConfig.PoolConfig(5, Duration.ofMinutes(1)),
            new BackendConfig.ReasoningConfig("passthrough", null, "enabled", 8000));

        var req = UnifiedChatRequest.builder()
            .model("o3-mini")
            .messages(simpleUserMessage())
            .config(UnifiedGenerationConfig.builder().build())
            .stream(false)
            .build();

        ChatCompletionCreateParams params = converter.convert(req, cfg);

        assertThat(params.reasoningEffort()).isPresent();
        assertThat(params.reasoningEffort().get().toString()).isEqualTo("medium");
    }

    @Test
    void shouldNotInjectEffortWhenAllSourcesAbsent() {
        var req = UnifiedChatRequest.builder()
            .model("o3-mini")
            .messages(simpleUserMessage())
            .config(UnifiedGenerationConfig.builder().build())
            .stream(false)
            .build();

        ChatCompletionCreateParams params = converter.convert(req, backendWithReasoning("passthrough"));

        assertThat(params.reasoningEffort()).isEmpty();
    }

    // ===== P1-5: bare tool_call 缺 reasoning 占位 =====

    private UnifiedMessage assistantWithToolCall() {
        return UnifiedMessage.builder()
            .role(UnifiedMessage.Role.ASSISTANT)
            .toolCalls(List.of(UnifiedToolCall.builder()
                .id("call_1")
                .type("function")
                .function(UnifiedFunctionCall.builder()
                    .name("get_weather")
                    .arguments(new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode()
                        .put("city", "北京"))
                    .build())
                .build()))
            .build();
    }

    @Test
    void shouldInjectToolCallPlaceholderForDeepseekMode() {
        // effortMode=deepseek 后端,assistant + tool_calls + 无 reasoning -> 注入 "tool call"
        var req = UnifiedChatRequest.builder()
            .model("deepseek-chat")
            .messages(List.of(
                UnifiedMessage.builder().role(UnifiedMessage.Role.USER).content("hi").build(),
                assistantWithToolCall(),
                UnifiedMessage.builder().role(UnifiedMessage.Role.TOOL).content("result").toolCallId("call_1").build()
            ))
            .stream(false)
            .build();

        ChatCompletionCreateParams params = converter.convert(req, backendWithReasoning("deepseek"));

        var assistant = params.messages().get(1).asAssistant();
        var rc = assistant._additionalProperties().get("reasoning_content");
        assertThat(rc).isNotNull();
        assertThat(rc.asString().orElse(null)).isEqualTo("tool call");
    }

    @Test
    void shouldInjectToolCallPlaceholderForLowHighMode() {
        // effortMode=low_high(kimi 等)后端,同样注入
        var req = UnifiedChatRequest.builder()
            .model("kimi-k2")
            .messages(List.of(
                UnifiedMessage.builder().role(UnifiedMessage.Role.USER).content("hi").build(),
                assistantWithToolCall(),
                UnifiedMessage.builder().role(UnifiedMessage.Role.TOOL).content("result").toolCallId("call_1").build()
            ))
            .stream(false)
            .build();

        ChatCompletionCreateParams params = converter.convert(req, backendWithReasoning("low_high"));

        var assistant = params.messages().get(1).asAssistant();
        var rc = assistant._additionalProperties().get("reasoning_content");
        assertThat(rc).isNotNull();
        assertThat(rc.asString().orElse(null)).isEqualTo("tool call");
    }

    @Test
    void shouldNotInjectPlaceholderForPassthroughMode() {
        // effortMode=passthrough + 模型名也不匹配 vendor hints -> 不注入
        var req = UnifiedChatRequest.builder()
            .model("gpt-4o")
            .messages(List.of(
                UnifiedMessage.builder().role(UnifiedMessage.Role.USER).content("hi").build(),
                assistantWithToolCall(),
                UnifiedMessage.builder().role(UnifiedMessage.Role.TOOL).content("result").toolCallId("call_1").build()
            ))
            .stream(false)
            .build();

        ChatCompletionCreateParams params = converter.convert(req, backendWithReasoning("passthrough"));

        var assistant = params.messages().get(1).asAssistant();
        assertThat(assistant._additionalProperties().get("reasoning_content")).isNull();
    }

    @Test
    void shouldNotInjectPlaceholderWhenReasoningContentAlreadyPresent() {
        // 已有真实 reasoning_content 时不注入占位符
        var req = UnifiedChatRequest.builder()
            .model("deepseek-chat")
            .messages(List.of(
                UnifiedMessage.builder().role(UnifiedMessage.Role.USER).content("hi").build(),
                UnifiedMessage.builder()
                    .role(UnifiedMessage.Role.ASSISTANT)
                    .reasoningContent("真实推理")
                    .toolCalls(List.of(UnifiedToolCall.builder()
                        .id("call_1")
                        .type("function")
                        .function(UnifiedFunctionCall.builder()
                            .name("get_weather")
                            .build())
                        .build()))
                    .build(),
                UnifiedMessage.builder().role(UnifiedMessage.Role.TOOL).content("result").toolCallId("call_1").build()
            ))
            .stream(false)
            .build();

        ChatCompletionCreateParams params = converter.convert(req, backendWithReasoning("deepseek"));

        var assistant = params.messages().get(1).asAssistant();
        var rc = assistant._additionalProperties().get("reasoning_content");
        assertThat(rc).isNotNull();
        assertThat(rc.asString().orElse(null)).isEqualTo("真实推理");
    }

    @Test
    void shouldNotInjectPlaceholderWhenNoToolCalls() {
        // assistant 无 tool_calls 时不需要占位
        var req = UnifiedChatRequest.builder()
            .model("deepseek-chat")
            .messages(List.of(
                UnifiedMessage.builder().role(UnifiedMessage.Role.USER).content("hi").build(),
                UnifiedMessage.builder()
                    .role(UnifiedMessage.Role.ASSISTANT)
                    .content("hello")
                    .build()
            ))
            .stream(false)
            .build();

        ChatCompletionCreateParams params = converter.convert(req, backendWithReasoning("deepseek"));

        var assistant = params.messages().get(1).asAssistant();
        assertThat(assistant._additionalProperties().get("reasoning_content")).isNull();
    }

    @Test
    void shouldInjectPlaceholderByModelNameVendorHintWithoutBackendConfig() {
        // 无 BackendConfig,但模型名含 "deepseek" -> 注入
        var req = UnifiedChatRequest.builder()
            .model("deepseek-r1")
            .messages(List.of(
                UnifiedMessage.builder().role(UnifiedMessage.Role.USER).content("hi").build(),
                assistantWithToolCall(),
                UnifiedMessage.builder().role(UnifiedMessage.Role.TOOL).content("result").toolCallId("call_1").build()
            ))
            .stream(false)
            .build();

        ChatCompletionCreateParams params = converter.convert(req);

        var assistant = params.messages().get(1).asAssistant();
        var rc = assistant._additionalProperties().get("reasoning_content");
        assertThat(rc).isNotNull();
        assertThat(rc.asString().orElse(null)).isEqualTo("tool call");
    }

    @Test
    void shouldInjectPlaceholderByBaseUrlVendorHint() {
        // effortMode=passthrough 但 baseUrl 含 "moonshot" -> 注入(vendor hints 优先于 mode)
        BackendConfig cfg = new BackendConfig(
            "openai", "sk-test", "https://api.moonshot.cn/v1",
            "custom-model", null,
            Duration.ofSeconds(5), Duration.ofSeconds(10), Duration.ofSeconds(5),
            new BackendConfig.PoolConfig(5, Duration.ofMinutes(1)),
            new BackendConfig.ReasoningConfig("passthrough", null, null, null));

        var req = UnifiedChatRequest.builder()
            .model("custom-model")
            .messages(List.of(
                UnifiedMessage.builder().role(UnifiedMessage.Role.USER).content("hi").build(),
                assistantWithToolCall(),
                UnifiedMessage.builder().role(UnifiedMessage.Role.TOOL).content("result").toolCallId("call_1").build()
            ))
            .stream(false)
            .build();

        ChatCompletionCreateParams params = converter.convert(req, cfg);

        var assistant = params.messages().get(1).asAssistant();
        var rc = assistant._additionalProperties().get("reasoning_content");
        assertThat(rc).isNotNull();
        assertThat(rc.asString().orElse(null)).isEqualTo("tool call");
    }
}
