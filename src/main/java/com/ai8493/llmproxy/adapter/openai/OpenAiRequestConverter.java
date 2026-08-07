package com.ai8493.llmproxy.adapter.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.core.JsonValue;
import com.openai.models.ChatModel;
import com.openai.models.FunctionDefinition;
import com.openai.models.FunctionParameters;
import com.openai.models.ReasoningEffort;
import com.openai.models.ResponseFormatJsonSchema;
import com.openai.models.chat.completions.*;
import com.ai8493.llmproxy.config.BackendConfig;
import com.ai8493.llmproxy.model.*;
import com.ai8493.llmproxy.model.extensions.OpenAiExtensions;
import com.ai8493.llmproxy.model.extensions.ThinkingConfig;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class OpenAiRequestConverter {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // 需要 reasoning 占位符的厂商标识
    // kimi/Moonshot、DeepSeek、MiMo 等 thinking 模型要求 assistant tool_call 消息必须有 reasoning_content
    private static final List<String> REASONING_VENDOR_HINTS =
        List.of("moonshot", "kimi", "deepseek", "mimo", "xiaomimimo");

    // 兼容旧调用:无 BackendConfig 时仅按 IR 字段处理,不做后端能力映射
    public ChatCompletionCreateParams convert(UnifiedChatRequest req) {
        return convert(req, null);
    }

    public ChatCompletionCreateParams convert(UnifiedChatRequest req, BackendConfig backendConfig) {
        // 预扫描：收集所有有效 tool_use ID，用于孤儿 tool_result 检测
        java.util.Set<String> validToolUseIds = new java.util.HashSet<>();
        if (req.messages() != null) {
            for (UnifiedMessage msg : req.messages()) {
                if (msg.role() == UnifiedMessage.Role.ASSISTANT && msg.toolCalls() != null) {
                    for (UnifiedToolCall tc : msg.toolCalls()) {
                        if (tc.id() != null && !tc.id().isEmpty()) {
                            validToolUseIds.add(tc.id());
                        }
                    }
                }
            }
        }

        // 检测后端是否属于 reasoning vendor(kimi/DeepSeek/MiMo 等),决定 assistant tool_call 是否需要占位 reasoning
        boolean needsReasoningPlaceholder = needsReasoningPlaceholder(req, backendConfig);

        var builder = ChatCompletionCreateParams.builder()
            .model(ChatModel.of(req.model()));

        if (req.messages() != null) {
            builder.messages(req.messages().stream()
                .map(msg -> toMessageParam(msg, validToolUseIds, needsReasoningPlaceholder))
                .toList());
        }

        if (req.tools() != null && !req.tools().isEmpty()) {
            builder.tools(req.tools().stream()
                .map(this::toTool)
                .toList());
        }

        // P3-12: 无 tools 时不发送 tool_choice,避免后端 400
        boolean hasTools = req.tools() != null && !req.tools().isEmpty();
        if (hasTools && req.toolChoice() != null) {
            builder.toolChoice(toToolChoice(req.toolChoice()));
        }

        if (req.config() != null) {
            UnifiedGenerationConfig cfg = req.config();
            if (cfg.temperature() != null) builder.temperature(cfg.temperature());
            if (cfg.topP() != null) builder.topP(cfg.topP());
            if (cfg.maxOutputTokens() != null) {
                // o-series 只支持 max_completion_tokens;其他模型仍用(已废弃的)max_tokens
                if (isOSeries(req.model())) {
                    builder.maxCompletionTokens(cfg.maxOutputTokens().longValue());
                } else {
                    builder.maxTokens(cfg.maxOutputTokens().longValue());
                }
            }
            if (cfg.stopSequences() != null && !cfg.stopSequences().isEmpty())
                builder.stop(ChatCompletionCreateParams.Stop.ofStrings(cfg.stopSequences()));
            // 新增字段映射
            if (cfg.user() != null) {
                builder.user(cfg.user());
            }
            // P3-12: 无 tools 时不发送 parallel_tool_calls
            if (hasTools && cfg.parallelToolCalls() != null) {
                builder.parallelToolCalls(cfg.parallelToolCalls());
            }

            // reasoning_effort 仅对支持模型(o-series / gpt-5+)注入,避免其他模型 400
            if (supportsReasoningEffort(req.model())) {
                String effort = resolveReasoningEffort(cfg, backendConfig);
                if (effort != null) {
                    builder.reasoningEffort(ReasoningEffort.of(effort));
                }
            }
        }

        // 从 OpenAiExtensions 重建 OpenAI 专属字段
        if (req.openai() != null) {
            OpenAiExtensions ext = req.openai();
            if (ext.logprobs() != null) builder.logprobs(ext.logprobs());
            if (ext.topLogprobs() != null) builder.topLogprobs(ext.topLogprobs().longValue());
            if (ext.seed() != null) builder.seed(ext.seed());
            if (ext.n() != null) builder.n(ext.n().longValue());
            if (ext.responseFormat() != null) {
                builder.responseFormat(toResponseFormat(ext.responseFormat()));
            }
            if (ext.store() != null) builder.store(ext.store());
            if (ext.modalities() != null && !ext.modalities().isEmpty()) {
                builder.modalities(ext.modalities().stream()
                    .map(com.openai.models.chat.completions.ChatCompletionCreateParams.Modality::of)
                    .toList());
            }
            if (ext.logitBias() != null && ext.logitBias().isObject()) {
                Map<String, JsonValue> logitBiasProps = new HashMap<>();
                ext.logitBias().fields().forEachRemaining(e ->
                    logitBiasProps.put(e.getKey(), JsonValue.fromJsonNode(e.getValue())));
                builder.logitBias(com.openai.models.chat.completions.ChatCompletionCreateParams.LogitBias.builder()
                    .putAllAdditionalProperties(logitBiasProps)
                    .build());
            }
            if (ext.metadata() != null && ext.metadata().isObject()) {
                Map<String, JsonValue> metadataProps = new HashMap<>();
                ext.metadata().fields().forEachRemaining(e ->
                    metadataProps.put(e.getKey(), JsonValue.fromJsonNode(e.getValue())));
                builder.metadata(com.openai.models.chat.completions.ChatCompletionCreateParams.Metadata.builder()
                    .putAllAdditionalProperties(metadataProps)
                    .build());
            }
            if (ext.prediction() != null && ext.prediction().has("content")) {
                var predBuilder = ChatCompletionPredictionContent.builder();
                if (ext.prediction().has("type")) {
                    predBuilder.type(JsonValue.fromJsonNode(ext.prediction().get("type")));
                }
                JsonNode contentNode = ext.prediction().get("content");
                if (contentNode.isTextual()) {
                    predBuilder.content(contentNode.asText());
                }
                builder.prediction(predBuilder.build());
            }
            if (ext.webSearchOptions() != null) {
                Map<String, JsonValue> wsProps = new HashMap<>();
                ext.webSearchOptions().fields().forEachRemaining(e ->
                    wsProps.put(e.getKey(), JsonValue.fromJsonNode(e.getValue())));
                builder.webSearchOptions(
                    com.openai.models.chat.completions.ChatCompletionCreateParams.WebSearchOptions.builder()
                        .putAllAdditionalProperties(wsProps)
                        .build());
            }
            if (ext.audio() != null && ext.audio().has("voice") && ext.audio().has("format")) {
                builder.audio(ChatCompletionAudioParam.builder()
                    .voice(ext.audio().get("voice").asText())
                    .format(ChatCompletionAudioParam.Format.of(ext.audio().get("format").asText()))
                    .build());
            }
        }

        return builder.build();
    }

    private ChatCompletionMessageParam toMessageParam(UnifiedMessage msg,
            java.util.Set<String> validToolUseIds, boolean needsReasoningPlaceholder) {
        return switch (msg.role()) {
            case SYSTEM -> {
                var b = ChatCompletionSystemMessageParam.builder()
                    .content(ChatCompletionSystemMessageParam.Content.ofText(
                        com.ai8493.llmproxy.util.BillingHeaderStripper.strip(msg.content())));
                if (msg.name() != null) b.name(msg.name());
                yield ChatCompletionMessageParam.ofSystem(b.build());
            }
            case USER -> {
                var b = ChatCompletionUserMessageParam.builder();
                if (msg.name() != null) b.name(msg.name());
                if (msg.parts() != null && !msg.parts().isEmpty()) {
                    // 多模态：从 parts 构建 content 数组
                    List<ChatCompletionContentPart> contentList = new ArrayList<>();
                    for (var part : msg.parts()) {
                        if (part instanceof UnifiedPart.TextPart t && t.text() != null) {
                            contentList.add(ChatCompletionContentPart.ofText(
                                ChatCompletionContentPartText.builder()
                                    .text(t.text())
                                    .build()));
                        } else if (part instanceof UnifiedPart.ImagePart i && i.imageData() != null) {
                            var imageData = i.imageData();
                            var imageUrlBuilder = ChatCompletionContentPartImage.ImageUrl.builder()
                                .url(imageData.get("url").asText());
                            if (imageData.has("detail") && !imageData.get("detail").asText().isEmpty()) {
                                imageUrlBuilder.detail(
                                    ChatCompletionContentPartImage.ImageUrl.Detail.of(
                                        imageData.get("detail").asText()));
                            }
                            contentList.add(ChatCompletionContentPart.ofImageUrl(
                                ChatCompletionContentPartImage.builder()
                                    .imageUrl(imageUrlBuilder.build())
                                    .build()));
                        }
                    }
                    b.content(ChatCompletionUserMessageParam.Content.ofArrayOfContentParts(contentList));
                } else {
                    b.content(ChatCompletionUserMessageParam.Content.ofText(msg.content()));
                }
                yield ChatCompletionMessageParam.ofUser(b.build());
            }
            case ASSISTANT -> {
                var b = ChatCompletionAssistantMessageParam.builder();
                if (msg.name() != null) b.name(msg.name());
                if (msg.parts() != null && !msg.parts().isEmpty()) {
                    List<ChatCompletionAssistantMessageParam.Content.ChatCompletionRequestAssistantMessageContentPart> contentList = new ArrayList<>();
                    for (var part : msg.parts()) {
                        if (part instanceof UnifiedPart.TextPart t && t.text() != null) {
                            contentList.add(ChatCompletionAssistantMessageParam.Content.ChatCompletionRequestAssistantMessageContentPart.ofText(
                                ChatCompletionContentPartText.builder()
                                    .text(t.text())
                                    .build()));
                        }
                    }
                    if (!contentList.isEmpty()) {
                        b.content(ChatCompletionAssistantMessageParam.Content.ofArrayOfContentParts(contentList));
                    }
                } else if (msg.content() != null) {
                    b.content(ChatCompletionAssistantMessageParam.Content.ofText(msg.content()));
                }
                if (msg.toolCalls() != null && !msg.toolCalls().isEmpty()) {
                    b.toolCalls(msg.toolCalls().stream()
                        .map(this::toMessageToolCall)
                        .toList());
                }
                if (msg.reasoningContent() != null) {
                    b.putAdditionalProperty("reasoning_content",
                        com.openai.core.JsonValue.from(msg.reasoningContent()));
                } else if (needsReasoningPlaceholder
                    && msg.toolCalls() != null && !msg.toolCalls().isEmpty()) {
                    // kimi/DeepSeek/MiMo 等 thinking 模型要求 assistant tool_call 消息必须有 reasoning_content
                    // 跨轮历史恢复 miss 时补占位,避免上游 400
                    b.putAdditionalProperty("reasoning_content",
                        com.openai.core.JsonValue.from("tool call"));
                }
                // DeepSeek 要求 assistant 消息必须有 content 或 tool_calls
                if (msg.content() == null
                    && (msg.parts() == null || msg.parts().isEmpty())
                    && (msg.toolCalls() == null || msg.toolCalls().isEmpty())) {
                    b.content(ChatCompletionAssistantMessageParam.Content.ofText(""));
                }
                yield ChatCompletionMessageParam.ofAssistant(b.build());
            }
            case TOOL -> {
                String toolCallId = msg.toolCallId();
                // 孤儿 tool_result：引用的 tool_use 不在本次请求中，转为 user 文本避免 API 400
                if (toolCallId != null && !toolCallId.isEmpty()
                    && !validToolUseIds.isEmpty()
                    && !validToolUseIds.contains(toolCallId)) {
                    String label = msg.name() != null ? msg.name() : "tool";
                    String text = msg.content() != null ? msg.content() : "";
                    yield ChatCompletionMessageParam.ofUser(
                        ChatCompletionUserMessageParam.builder()
                            .content(ChatCompletionUserMessageParam.Content.ofText(
                                "[tool_result: " + label + " id=" + toolCallId + "] " + text))
                            .build());
                }
                yield ChatCompletionMessageParam.ofTool(
                    ChatCompletionToolMessageParam.builder()
                        .content(ChatCompletionToolMessageParam.Content.ofText(msg.content()))
                        .toolCallId(toolCallId)
                        .build()
                );
            }
        };
    }

    private ChatCompletionMessageToolCall toMessageToolCall(UnifiedToolCall tc) {
        return ChatCompletionMessageToolCall.ofFunction(
            ChatCompletionMessageFunctionToolCall.builder()
                .id(tc.id())
                .function(ChatCompletionMessageFunctionToolCall.Function.builder()
                    .name(tc.function().name())
                    .arguments(tc.function().arguments() != null
                        ? tc.function().arguments().toString() : "{}")
                    .build())
                .build()
        );
    }

    private ChatCompletionTool toTool(UnifiedTool tool) {
        if (tool.function() == null) {
            throw new IllegalArgumentException("仅支持 function 类型的 tool");
        }
        var fnBuilder = FunctionDefinition.builder()
            .name(tool.function().name())
            .description(tool.function().description());
        if (tool.function().parameters() != null) {
            fnBuilder.parameters(toFunctionParameters(tool.function().parameters()));
        }
        return ChatCompletionTool.ofFunction(
            ChatCompletionFunctionTool.builder()
                .function(fnBuilder.build())
                .build()
        );
    }

    private FunctionParameters toFunctionParameters(JsonNode node) {
        Map<String, JsonValue> props = new HashMap<>();
        if (node.isObject()) {
            node.fieldNames().forEachRemaining(fieldName ->
                props.put(fieldName, JsonValue.fromJsonNode(node.get(fieldName)))
            );
        }
        return FunctionParameters.builder()
            .additionalProperties(props)
            .build();
    }

    private ChatCompletionToolChoiceOption toToolChoice(UnifiedToolChoice tc) {
        return switch (tc) {
            case UnifiedToolChoice.None __ ->
                ChatCompletionToolChoiceOption.ofAuto(
                    ChatCompletionToolChoiceOption.Auto.NONE);
            case UnifiedToolChoice.Auto __ ->
                ChatCompletionToolChoiceOption.ofAuto(
                    ChatCompletionToolChoiceOption.Auto.AUTO);
            case UnifiedToolChoice.Required r ->
                ChatCompletionToolChoiceOption.ofNamedToolChoice(
                    ChatCompletionNamedToolChoice.builder()
                        .function(ChatCompletionNamedToolChoice.Function.builder()
                            .name(r.functionName())
                            .build())
                        .build());
            case UnifiedToolChoice.Any __ ->
                ChatCompletionToolChoiceOption.ofAuto(
                    ChatCompletionToolChoiceOption.Auto.REQUIRED);
        };
    }

    // 从 Extensions.responseFormat(JsonNode) 重建 SDK ResponseFormat union
    private ChatCompletionCreateParams.ResponseFormat toResponseFormat(JsonNode rfNode) {
        String type = rfNode.path("type").asText("text");
        return switch (type) {
            case "json_object" -> ChatCompletionCreateParams.ResponseFormat.ofJsonObject(
                com.openai.models.ResponseFormatJsonObject.builder().build());
            case "json_schema" -> {
                JsonNode schemaNode = rfNode.path("json_schema");
                ResponseFormatJsonSchema.JsonSchema.Builder jsb =
                    ResponseFormatJsonSchema.JsonSchema.builder();
                if (schemaNode.has("name")) {
                    jsb.name(schemaNode.get("name").asText());
                }
                if (schemaNode.has("description")) {
                    jsb.description(schemaNode.get("description").asText());
                }
                if (schemaNode.has("strict")) {
                    jsb.strict(schemaNode.get("strict").asBoolean());
                }
                if (schemaNode.has("schema")) {
                    jsb.schema(toResponseFormatSchema(schemaNode.get("schema")));
                }
                yield ChatCompletionCreateParams.ResponseFormat.ofJsonSchema(
                    ResponseFormatJsonSchema.builder()
                        .jsonSchema(jsb.build())
                        .build());
            }
            default -> ChatCompletionCreateParams.ResponseFormat.ofText(
                com.openai.models.ResponseFormatText.builder().build());
        };
    }

    // 把 JSON Schema 节点转为 SDK 的 ResponseFormatJsonSchema.JsonSchema.Schema
    // Schema 类只接受 additionalProperties,把所有字段透传
    private ResponseFormatJsonSchema.JsonSchema.Schema toResponseFormatSchema(JsonNode node) {
        Map<String, JsonValue> props = new HashMap<>();
        if (node.isObject()) {
            node.fieldNames().forEachRemaining(fieldName ->
                props.put(fieldName, JsonValue.fromJsonNode(node.get(fieldName))));
        }
        return ResponseFormatJsonSchema.JsonSchema.Schema.builder()
            .putAllAdditionalProperties(props)
            .build();
    }

    // o-series 模型检测:o1/o3/o4-mini 等以 'o' + 数字开头的模型
    private static boolean isOSeries(String model) {
        if (model == null || model.length() < 2) return false;
        if (model.charAt(0) != 'o') return false;
        char c = model.charAt(1);
        return c >= '0' && c <= '9';
    }

    // 检测后端是否属于 reasoning vendor(kimi/DeepSeek/MiMo 等),需要为 assistant tool_call 补 reasoning 占位
    // 判定规则:
    //   1. BackendConfig.ReasoningConfig.effortMode 为 deepseek/low_high(明确配置的 reasoning 厂商模式)
    //   2. 模型名/defaultModel/baseUrl 匹配 REASONING_VENDOR_HINTS
    private static boolean needsReasoningPlaceholder(UnifiedChatRequest req, BackendConfig backendConfig) {
        String mode = backendConfig == null || backendConfig.reasoning() == null
            ? null : backendConfig.reasoning().effortMode();
        if ("deepseek".equals(mode) || "low_high".equals(mode)) {
            return true;
        }
        if (isReasoningVendor(req.model())) return true;
        if (backendConfig != null) {
            if (isReasoningVendor(backendConfig.defaultModel())) return true;
            if (isReasoningVendor(backendConfig.baseUrl())) return true;
        }
        return false;
    }

    // 检查字符串是否包含任一 reasoning vendor hint(大小写不敏感)
    private static boolean isReasoningVendor(String value) {
        if (value == null || value.isEmpty()) return false;
        String lower = value.toLowerCase();
        return REASONING_VENDOR_HINTS.stream().anyMatch(lower::contains);
    }

    // 支持 reasoning_effort 的模型:o-series 或 gpt-5+
    private static boolean supportsReasoningEffort(String model) {
        if (isOSeries(model)) return true;
        if (model == null) return false;
        String lower = model.toLowerCase();
        if (!lower.startsWith("gpt-")) return false;
        if (lower.length() <= 4) return false;
        char c = lower.charAt(4);
        return c >= '5' && c <= '9';
    }

    // 解析 reasoning_effort:
    // 1. 优先显式 cfg.reasoningEffort(Responses 入站已写入)
    // 2. 次选 cfg.thinkingConfig 映射(Anthropic 入站)
    // 3. 兜底 BackendConfig.ReasoningConfig 的 default 字段
    // 4. 按 effortMode 映射到后端实际接受的 effort(passthrough/low_high/openrouter/deepseek)
    // 返回 null 表示不注入
    private static String resolveReasoningEffort(UnifiedGenerationConfig cfg, BackendConfig backendConfig) {
        BackendConfig.ReasoningConfig rc = backendConfig == null ? null : backendConfig.reasoning();

        // Step 1+2+3: 解析 effective effort
        String effort = pickEffectiveEffort(cfg, rc);
        if (effort == null) return null;

        // none/off/disabled 不注入
        String lower = effort.trim().toLowerCase();
        if (lower.equals("none") || lower.equals("off") || lower.equals("disabled")) {
            return null;
        }

        // Step 4: 按 mode 映射
        String mode = rc == null || rc.effortMode() == null ? "passthrough" : rc.effortMode();
        return mapEffortByMode(lower, mode);
    }

    // 解析客户端意图 + 配置默认值的 effective effort(未做 mode 映射)
    private static String pickEffectiveEffort(UnifiedGenerationConfig cfg, BackendConfig.ReasoningConfig rc) {
        // Priority 1: 显式 reasoningEffort
        String explicit = cfg.reasoningEffort();
        if (explicit != null && !explicit.isEmpty() && !explicit.equalsIgnoreCase("auto")) {
            return explicit;
        }
        // Priority 2: thinking fallback
        ThinkingConfig tc = cfg.thinkingConfig();
        if (tc != null && tc.type() != null) {
            String fromThinking = thinkingToEffort(tc);
            if (fromThinking != null) return fromThinking;
        }
        // Priority 3: 配置默认 effort
        if (rc != null) {
            if (rc.effortDefault() != null && !rc.effortDefault().isEmpty()) {
                return rc.effortDefault();
            }
            if (rc.thinkingDefaultType() != null) {
                ThinkingConfig defaultTc = ThinkingConfig.builder()
                    .type(rc.thinkingDefaultType())
                    .budgetTokens(rc.thinkingDefaultBudget())
                    .build();
                return thinkingToEffort(defaultTc);
            }
        }
        return null;
    }

    // thinking.type + budget_tokens -> effort
    private static String thinkingToEffort(ThinkingConfig tc) {
        return switch (tc.type()) {
            case "adaptive" -> "xhigh";
            case "enabled" -> {
                Integer budget = tc.budgetTokens();
                if (budget == null) yield "high";
                if (budget < 4000) yield "low";
                if (budget < 16000) yield "medium";
                yield "high";
            }
            default -> null; // disabled 等
        };
    }

    // 按 effortMode 把 effort 映射到后端实际接受值
    private static String mapEffortByMode(String effort, String mode) {
        return switch (mode) {
            case "deepseek" -> switch (effort) {
                case "max", "xhigh" -> "max";
                default -> "high";
            };
            case "low_high" -> switch (effort) {
                case "minimal", "low" -> "low";
                default -> "high";
            };
            case "openrouter" -> switch (effort) {
                case "max", "xhigh" -> "xhigh";
                case "high", "medium", "low", "minimal" -> effort;
                default -> null;
            };
            default -> // passthrough
                switch (effort) {
                    case "minimal", "low", "medium", "high", "xhigh", "max" -> effort;
                    default -> null;
                };
        };
    }
}
