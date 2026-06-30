package com.ai8493.llmproxy.adapter.anthropic;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.ai8493.llmproxy.model.*;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 将 IR（UnifiedChatRequest）转换为 Anthropic SDK MessageCreateParams。
 * 处理 system 分离、tool result 包装为 user message、thinking block 等协议差异。
 */
@SuppressWarnings("deprecation")
public class AnthropicRequestConverter {

    // 单次请求内跟踪 tool_use ID，防止上游/客户端发重复 call_id 导致 API 400
    private final java.util.Set<String> seenToolUseIds = new java.util.HashSet<>();

    private final long defaultMaxTokens;

    public AnthropicRequestConverter() {
        this.defaultMaxTokens = 4096L;
    }

    public AnthropicRequestConverter(long defaultMaxTokens) {
        this.defaultMaxTokens = defaultMaxTokens;
    }

    /**
     * 转换 UnifiedChatRequest → MessageCreateParams。
     */
    public MessageCreateParams convert(UnifiedChatRequest req) {
        // 预扫描：收集所有 ASSISTANT 消息中的 tool_use ID，用于后续孤儿检测
        java.util.Set<String> validToolUseIds = new java.util.HashSet<>();
        for (UnifiedMessage msg : req.messages()) {
            if (msg.role() == UnifiedMessage.Role.ASSISTANT && msg.toolCalls() != null) {
                for (UnifiedToolCall tc : msg.toolCalls()) {
                    if (tc.id() != null && !tc.id().isEmpty()) {
                        validToolUseIds.add(tc.id());
                    }
                }
            }
        }

        seenToolUseIds.clear();
        MessageCreateParams.Builder builder = MessageCreateParams.builder();

        // Model
        builder.model(Model.of(req.model()));

        // System 提示（Anthropic 使用顶层 system 字段而非 messages）
        String systemText = req.messages().stream()
                .filter(m -> m.role() == UnifiedMessage.Role.SYSTEM)
                .map(m -> m.content() != null ? m.content() : "")
                .collect(Collectors.joining("\n"));
        if (!systemText.isEmpty()) {
            builder.system(systemText);
        }

        // 非 system 消息转换（含连续 TOOL 消息合并为单条 user 消息）
        List<ContentBlockParam> pendingToolResults = null;
        for (UnifiedMessage msg : req.messages()) {
            if (msg.role() == UnifiedMessage.Role.SYSTEM) {
                continue;
            }
            // 连续 TOOL 消息合并：Anthropic 要求同一轮 assistant 的 tool_result 在同一条 user 消息中
            if (msg.role() == UnifiedMessage.Role.TOOL) {
                String toolCallId = msg.toolCallId();
                // 孤儿 tool_result：请求中有 tool_use 但引用的 ID 不在其中，转为文本避免 API 400
                if (toolCallId != null && !toolCallId.isEmpty()
                    && !validToolUseIds.isEmpty()
                    && !validToolUseIds.contains(toolCallId)) {
                    pendingToolResults = flushPending(builder, pendingToolResults);
                    String label = msg.name() != null ? msg.name() : "tool";
                    String text = msg.content() != null ? msg.content() : "";
                    builder.addUserMessage("[tool_result: " + label + " id=" + toolCallId + "] " + text);
                    continue;
                }
                if (pendingToolResults == null) {
                    pendingToolResults = new ArrayList<>();
                }
                pendingToolResults.add(toToolResultBlock(msg));
                continue;
            }
            // 非 TOOL 消息：先 flush 累积的 tool_results
            pendingToolResults = flushPending(builder, pendingToolResults);
            switch (msg.role()) {
                case USER -> convertUserMessage(builder, msg);
                case ASSISTANT -> convertAssistantMessage(builder, msg);
                default -> throw new IllegalArgumentException("不支持的 role: " + msg.role());
            }
        }
        // flush 尾部 tool_results
        flushPending(builder, pendingToolResults);

        // Tools
        if (req.tools() != null && !req.tools().isEmpty()) {
            List<ToolUnion> tools = req.tools().stream()
                    .map(this::toToolUnion)
                    .collect(Collectors.toList());
            builder.tools(tools);
        }

        // Tool choice
        if (req.toolChoice() != null) {
            builder.toolChoice(toToolChoice(req.toolChoice()));
        }

        // 生成参数
        UnifiedGenerationConfig config = req.config();
        if (config != null) {
            builder.maxTokens(
                    config.maxOutputTokens() != null
                            ? config.maxOutputTokens().longValue()
                            : defaultMaxTokens);
            if (config.temperature() != null) {
                builder.temperature(config.temperature());
            }
            if (config.topP() != null) {
                builder.topP(config.topP());
            }
            if (config.stopSequences() != null && !config.stopSequences().isEmpty()) {
                builder.stopSequences(config.stopSequences());
            }
            if (config.parallelToolCalls() != null) {
                // Anthropic 无直接 parallel_tool_calls 字段，通过 metadata 传递
            }
            if (config.user() != null && !config.user().isEmpty()) {
                builder.metadata(Metadata.builder()
                    .userId(config.user())
                    .build());
            }
        } else {
            builder.maxTokens(defaultMaxTokens);
        }

        return builder.build();
    }

    // ====== 消息转换 ======

    private void convertUserMessage(MessageCreateParams.Builder builder, UnifiedMessage msg) {
        // 多模态（含图片）→ 按 parts 构建 ContentBlockParam 列表
        if (msg.parts() != null && !msg.parts().isEmpty()) {
            List<ContentBlockParam> blocks = new ArrayList<>();
            for (var part : msg.parts()) {
                if ("text".equals(part.type()) && part.text() != null) {
                    blocks.add(ContentBlockParam.ofText(
                        TextBlockParam.builder().text(part.text()).build()));
                } else if ("image_url".equals(part.type()) && part.imageData() != null) {
                    blocks.add(ContentBlockParam.ofImage(toImageBlockParam(part.imageData())));
                }
            }
            if (!blocks.isEmpty()) {
                builder.addUserMessageOfBlockParams(blocks);
            }
            return;
        }

        String text = msg.content();
        if (text != null && !text.isEmpty()) {
            builder.addUserMessage(text);
        }
        // 空消息跳过，避免 API 校验错误
    }

    /** 将 IR 的 imageData JsonNode 转为 Anthropic ImageBlockParam */
    private ImageBlockParam toImageBlockParam(JsonNode imageData) {
        String url = imageData.has("url") ? imageData.get("url").asText() : "";

        if (url.startsWith("data:")) {
            int commaIdx = url.indexOf(',');
            String header = commaIdx >= 0 ? url.substring(5, commaIdx) : url.substring(5);
            String data = commaIdx >= 0 ? url.substring(commaIdx + 1) : "";
            int semiIdx = header.indexOf(';');
            String mediaType = semiIdx >= 0 ? header.substring(0, semiIdx) : "image/png";
            return ImageBlockParam.builder()
                .source(ImageBlockParam.Source.ofBase64(
                    Base64ImageSource.builder()
                        .mediaType(Base64ImageSource.MediaType.of(mediaType))
                        .data(data)
                        .build()))
                .build();
        }
        return ImageBlockParam.builder()
            .urlSource(url)
            .build();
    }

    private void convertAssistantMessage(MessageCreateParams.Builder builder, UnifiedMessage msg) {
        List<UnifiedToolCall> toolCalls = msg.toolCalls();
        String reasoningContent = msg.reasoningContent();
        boolean hasToolCalls = toolCalls != null && !toolCalls.isEmpty();
        boolean hasReasoning = reasoningContent != null && !reasoningContent.isEmpty();
        String text = msg.content();
        boolean hasText = text != null && !text.isEmpty();

        if (hasToolCalls || hasReasoning) {
            // 需要按块（block）构建（thinking + text + tool_use）
            List<ContentBlockParam> blocks = new ArrayList<>();

            // Thinking 块（如果有 reasoningContent）
            if (hasReasoning) {
                blocks.add(ContentBlockParam.ofThinking(
                        ThinkingBlockParam.builder()
                                .thinking(reasoningContent)
                                .signature("")
                                .build()));
            }

            // Text 块
            if (hasText) {
                blocks.add(ContentBlockParam.ofText(
                        TextBlockParam.builder().text(text).build()));
            }

            // Tool use 块
            if (hasToolCalls) {
                for (UnifiedToolCall tc : toolCalls) {
                    String uniqueId = uniquifyToolUseId(tc.id());
                    blocks.add(ContentBlockParam.ofToolUse(
                            ToolUseBlockParam.builder()
                                    .id(uniqueId)
                                    .name(tc.function().name())
                                    .input(toToolUseInput(tc.function().arguments()))
                                    .build()));
                }
            }

            builder.addAssistantMessageOfBlockParams(blocks);
        } else if (hasText) {
            // 纯文本 assistant 消息
            builder.addAssistantMessage(text);
        }
    }

    /** flush 累积的 tool_result blocks，返回 null */
    private static List<ContentBlockParam> flushPending(
            MessageCreateParams.Builder builder, List<ContentBlockParam> pending) {
        if (pending != null && !pending.isEmpty()) {
            builder.addUserMessageOfBlockParams(pending);
        }
        return null;
    }

    private ContentBlockParam toToolResultBlock(UnifiedMessage msg) {
        return ContentBlockParam.ofToolResult(
                ToolResultBlockParam.builder()
                        .toolUseId(msg.toolCallId())
                        .content(msg.content() != null ? msg.content() : "")
                        .build());
    }

    // ====== Tool 转换 ======

    private ToolUnion toToolUnion(UnifiedTool tool) {
        UnifiedFunctionDefinition func = tool.function();
        Tool.Builder toolBuilder = Tool.builder()
                .name(func.name());

        if (func.description() != null && !func.description().isEmpty()) {
            toolBuilder.description(func.description());
        }

        if (func.parameters() != null) {
            toolBuilder.inputSchema(toInputSchema(func.parameters()));
        }

        return ToolUnion.ofTool(toolBuilder.build());
    }

    private Tool.InputSchema toInputSchema(JsonNode parameters) {
        Tool.InputSchema.Builder schemaBuilder = Tool.InputSchema.builder()
                .type(JsonValue.from("object"));

        if (parameters.has("properties") && parameters.get("properties").isObject()) {
            JsonNode properties = parameters.get("properties");
            Tool.InputSchema.Properties.Builder propsBuilder =
                    Tool.InputSchema.Properties.builder();
            properties.fieldNames().forEachRemaining(name ->
                    propsBuilder.putAdditionalProperty(
                            name, JsonValue.fromJsonNode(properties.get(name))));
            schemaBuilder.properties(propsBuilder.build());
        }

        if (parameters.has("required") && parameters.get("required").isArray()) {
            for (JsonNode n : parameters.get("required")) {
                schemaBuilder.addRequired(n.asText());
            }
        }

        return schemaBuilder.build();
    }

    /** 确保 tool_use ID 在单次请求内唯一，防止上游发重复 call_id 导致 API 400 */
    private String uniquifyToolUseId(String id) {
        if (id == null || id.isEmpty()) return "call_" + UUID.randomUUID().toString().substring(0, 8);
        if (seenToolUseIds.add(id)) return id;
        String unique = id + "_" + seenToolUseIds.size();
        seenToolUseIds.add(unique);
        return unique;
    }

    private ToolUseBlockParam.Input toToolUseInput(JsonNode arguments) {
        ToolUseBlockParam.Input.Builder inputBuilder = ToolUseBlockParam.Input.builder();
        if (arguments != null && arguments.isObject()) {
            arguments.fieldNames().forEachRemaining(name ->
                    inputBuilder.putAdditionalProperty(
                            name, JsonValue.fromJsonNode(arguments.get(name))));
        }
        return inputBuilder.build();
    }

    // ====== Tool Choice 转换 ======

    private ToolChoice toToolChoice(UnifiedToolChoice choice) {
        return switch (choice) {
            case UnifiedToolChoice.None ignored ->
                    ToolChoice.ofNone(ToolChoiceNone.builder().build());
            case UnifiedToolChoice.Auto ignored ->
                    ToolChoice.ofAuto(ToolChoiceAuto.builder().build());
            case UnifiedToolChoice.Required r ->
                    ToolChoice.ofTool(
                            ToolChoiceTool.builder().name(r.functionName()).build());
        };
    }
}
