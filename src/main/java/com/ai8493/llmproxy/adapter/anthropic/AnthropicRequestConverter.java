package com.ai8493.llmproxy.adapter.anthropic;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.ai8493.llmproxy.model.*;
import com.ai8493.llmproxy.model.extensions.ThinkingConfig;
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

    private int currentMessageIndex = -1;
    private com.fasterxml.jackson.databind.JsonNode cacheControlByBlock;
    // tool_choice 的 disable_parallel_tool_use 子字段,从 AnthropicExtensions 读取
    private Boolean disableParallelToolUse;

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
        currentMessageIndex = -1;
        cacheControlByBlock = req.anthropic() != null ? req.anthropic().cacheControlByBlock() : null;
        disableParallelToolUse = req.anthropic() != null
            ? req.anthropic().disableParallelToolUse() : null;
        MessageCreateParams.Builder builder = MessageCreateParams.builder();

        // Model
        builder.model(Model.of(req.model()));

        // System 提示（Anthropic 使用顶层 system 字段而非 messages）
        // 优先用 anthropicExt.rawSystemArray 重建 array 形态(保真);否则 fallback string 拼接
        boolean systemArrayRebuilt = false;
        if (req.anthropic() != null && req.anthropic().rawSystemArray() != null
                && req.anthropic().rawSystemArray().isArray()) {
            List<TextBlockParam> sysBlocks = new ArrayList<>();
            for (JsonNode block : req.anthropic().rawSystemArray()) {
                if ("text".equals(block.path("type").asText())) {
                    var tbBuilder = TextBlockParam.builder()
                        .text(com.ai8493.llmproxy.util.BillingHeaderStripper.strip(
                            block.path("text").asText()));
                    if (block.has("cache_control") && !block.get("cache_control").isNull()) {
                        tbBuilder.cacheControl(toCacheControl(block.get("cache_control")));
                    }
                    sysBlocks.add(tbBuilder.build());
                }
            }
            if (!sysBlocks.isEmpty()) {
                builder.system(MessageCreateParams.System
                    .ofTextBlockParams(sysBlocks));
                systemArrayRebuilt = true;
            }
        }
        if (!systemArrayRebuilt) {
            String systemText = req.messages().stream()
                    .filter(m -> m.role() == UnifiedMessage.Role.SYSTEM)
                    .map(m -> m.content() != null ? m.content() : "")
                    .map(com.ai8493.llmproxy.util.BillingHeaderStripper::strip)
                    .collect(Collectors.joining("\n"));
            if (!systemText.isEmpty()) {
                builder.system(systemText);
            }
        }

        // 非 system 消息转换（含连续 TOOL 消息合并为单条 user 消息）
        List<ContentBlockParam> pendingToolResults = null;
        int messageIndex = 0;
        for (UnifiedMessage msg : req.messages()) {
            if (msg.role() == UnifiedMessage.Role.SYSTEM) {
                continue;
            }
            currentMessageIndex = messageIndex++;
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
        boolean hasTools = req.tools() != null && !req.tools().isEmpty();
        if (hasTools) {
            List<ToolUnion> tools = req.tools().stream()
                    .map(this::toToolUnion)
                    .collect(Collectors.toList());
            builder.tools(tools);
        }

        // Tool choice (P3-12: 无 tools 时不发送,避免后端 400)
        if (hasTools && req.toolChoice() != null) {
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
            if (config.topK() != null) {
                builder.topK(config.topK().longValue());
            }
            if (config.stopSequences() != null && !config.stopSequences().isEmpty()) {
                builder.stopSequences(config.stopSequences());
            }
            if (config.parallelToolCalls() != null) {
                // Anthropic 无直接 parallel_tool_calls 字段，通过 metadata 传递
            }
            // metadata.user_id:优先 AnthropicExtensions.metadataUserId,次选 config.user
            String userId = req.anthropic() != null
                ? req.anthropic().metadataUserId() : null;
            if (userId == null && config.user() != null && !config.user().isEmpty()) {
                userId = config.user();
            }
            if (userId != null && !userId.isEmpty()) {
                builder.metadata(Metadata.builder()
                    .userId(userId)
                    .build());
            }
            if (config.thinkingConfig() != null) {
                builder.thinking(toThinkingConfigParam(config.thinkingConfig()));
            }
            if (config.serviceTier() != null && !config.serviceTier().isEmpty()) {
                builder.serviceTier(MessageCreateParams.ServiceTier
                    .of(config.serviceTier()));
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
            int blockIndex = 0;
            for (var part : msg.parts()) {
                if (part instanceof UnifiedPart.TextPart t && t.text() != null) {
                    var tbBuilder = TextBlockParam.builder().text(t.text());
                    applyCacheControl(tbBuilder, currentMessageIndex, blockIndex);
                    blocks.add(ContentBlockParam.ofText(tbBuilder.build()));
                } else if (part instanceof UnifiedPart.ImagePart i && i.imageData() != null) {
                    blocks.add(ContentBlockParam.ofImage(toImageBlockParam(i.imageData())));
                } else if (part instanceof UnifiedPart.DocumentPart d && d.documentData() != null) {
                    blocks.add(ContentBlockParam.ofDocument(toDocumentBlockParam(d.documentData())));
                }
                blockIndex++;
            }
            if (!blocks.isEmpty()) {
                builder.addUserMessageOfBlockParams(blocks);
            }
            return;
        }

        String text = msg.content();
        if (text != null && !text.isEmpty()) {
            JsonNode ccNode = lookupCacheControl(currentMessageIndex, 0);
            if (ccNode != null) {
                var tbBuilder = TextBlockParam.builder().text(text);
                tbBuilder.cacheControl(toCacheControl(ccNode));
                builder.addUserMessageOfBlockParams(List.of(
                    ContentBlockParam.ofText(tbBuilder.build())));
            } else {
                builder.addUserMessage(text);
            }
        }
        // 空消息跳过，避免 API 校验错误
    }

    /** 按 (消息索引, block 索引) 从 cacheControlByBlock 查找 cache_control,返回 null 表示无 */
    private JsonNode lookupCacheControl(int msgIdx, int blockIdx) {
        if (cacheControlByBlock == null) return null;
        JsonNode msgEntry = cacheControlByBlock.path(String.valueOf(msgIdx));
        if (msgEntry.isMissingNode() || !msgEntry.isArray()) return null;
        if (blockIdx >= msgEntry.size()) return null;
        JsonNode ccNode = msgEntry.get(blockIdx);
        if (ccNode == null || ccNode.isNull()) return null;
        return ccNode;
    }

    /** 按 (消息索引, block 索引) 从 cacheControlByBlock 查找并应用 cache_control */
    private void applyCacheControl(TextBlockParam.Builder tbBuilder, int msgIdx, int blockIdx) {
        JsonNode ccNode = lookupCacheControl(msgIdx, blockIdx);
        if (ccNode != null) {
            tbBuilder.cacheControl(toCacheControl(ccNode));
        }
    }

    /** 从 JsonNode 构建 CacheControlEphemeral(type 默认 ephemeral,可选 ttl) */
    private CacheControlEphemeral toCacheControl(JsonNode ccNode) {
        var ccBuilder = CacheControlEphemeral.builder();
        if (ccNode.has("type")) {
            ccBuilder.type(com.anthropic.core.JsonValue.from(ccNode.get("type").asText()));
        }
        if (ccNode.has("ttl")) {
            ccBuilder.ttl(CacheControlEphemeral.Ttl.of(ccNode.get("ttl").asText()));
        }
        return ccBuilder.build();
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
                                .signature(msg.thinkingSignature() != null ? msg.thinkingSignature() : "")
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
        // 内置工具(bash/text_editor/web_search 等):从 rawTool 重建
        if (tool.rawTool() != null) {
            return toBuiltinToolUnion(tool.rawTool());
        }
        // 自定义 function 工具
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

    /** 从 rawTool JsonNode 重建内置工具 ToolUnion。
     * SDK 2.33.0 的 messages.ToolUnion 不支持 computer(computer use 在 beta 包),
     * computer 降级为自定义 Tool(透传 type 作为 name)。 */
    private ToolUnion toBuiltinToolUnion(JsonNode rawTool) {
        String type = rawTool.path("type").asText("");
        return switch (type) {
            case "bash_20250124" -> ToolUnion.ofBash20250124(
                com.anthropic.models.messages.ToolBash20250124.builder().build());
            case "text_editor_20250124" -> ToolUnion.ofTextEditor20250124(
                com.anthropic.models.messages.ToolTextEditor20250124.builder().build());
            case "text_editor_20250429" -> ToolUnion.ofTextEditor20250429(
                com.anthropic.models.messages.ToolTextEditor20250429.builder().build());
            case "text_editor_20250728" -> ToolUnion.ofTextEditor20250728(
                com.anthropic.models.messages.ToolTextEditor20250728.builder().build());
            case "web_search_20250305" -> ToolUnion.ofWebSearchTool20250305(
                com.anthropic.models.messages.WebSearchTool20250305.builder().build());
            case "web_search_20260209" -> ToolUnion.ofWebSearchTool20260209(
                com.anthropic.models.messages.WebSearchTool20260209.builder().build());
            default -> {
                // 未知内置工具类型(含 computer_*,在 beta 包),降级为自定义 Tool
                yield ToolUnion.ofTool(Tool.builder()
                    .name(rawTool.has("name") ? rawTool.get("name").asText() : type)
                    .build());
            }
        };
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
            // 注意:ToolChoiceNone.Builder 无 disableParallelToolUse 方法(Anthropic API 规范:
            // none 表示不调用工具,该字段无意义),因此 None 分支不应用此字段
            case UnifiedToolChoice.None ignored ->
                    ToolChoice.ofNone(ToolChoiceNone.builder().build());
            case UnifiedToolChoice.Auto ignored -> {
                var b = ToolChoiceAuto.builder();
                if (disableParallelToolUse != null) b.disableParallelToolUse(disableParallelToolUse);
                yield ToolChoice.ofAuto(b.build());
            }
            case UnifiedToolChoice.Required r -> {
                var b = ToolChoiceTool.builder().name(r.functionName());
                if (disableParallelToolUse != null) b.disableParallelToolUse(disableParallelToolUse);
                yield ToolChoice.ofTool(b.build());
            }
            case UnifiedToolChoice.Any ignored -> {
                var b = ToolChoiceAny.builder();
                if (disableParallelToolUse != null) b.disableParallelToolUse(disableParallelToolUse);
                yield ToolChoice.ofAny(b.build());
            }
        };
    }

    private ThinkingConfigParam toThinkingConfigParam(ThinkingConfig tc) {
        return switch (tc.type()) {
            case "enabled" -> {
                if (tc.budgetTokens() == null) {
                    throw new IllegalArgumentException("thinking type=enabled 需要 budgetTokens");
                }
                yield ThinkingConfigParam.ofEnabled(
                    ThinkingConfigEnabled.builder()
                        .budgetTokens(tc.budgetTokens().longValue())
                        .build());
            }
            case "adaptive" -> ThinkingConfigParam.ofAdaptive(
                ThinkingConfigAdaptive.builder().build());
            case "disabled" -> ThinkingConfigParam.ofDisabled(
                ThinkingConfigDisabled.builder().build());
            default -> throw new IllegalArgumentException("不支持的 thinking type: " + tc.type());
        };
    }

    /** 将 IR 的 documentData JsonNode 转为 Anthropic DocumentBlockParam */
    private DocumentBlockParam toDocumentBlockParam(JsonNode docData) {
        DocumentBlockParam.Builder b = DocumentBlockParam.builder();
        String sourceType = docData.path("source_type").asText();
        switch (sourceType) {
            case "base64" -> b.source(DocumentBlockParam.Source.ofBase64(
                Base64PdfSource.builder()
                    .mediaType(JsonValue.from(
                        docData.path("media_type").asText("application/pdf")))
                    .data(docData.path("data").asText())
                    .build()));
            case "text" -> b.source(DocumentBlockParam.Source.ofText(
                PlainTextSource.builder()
                    .data(docData.path("data").asText())
                    .build()));
            case "url" -> b.source(DocumentBlockParam.Source.ofUrl(
                UrlPdfSource.builder()
                    .url(docData.path("url").asText())
                    .build()));
            default -> {
                // 未知 source_type,跳过 source 设置(SDK 会校验失败,但保留 title/context)
            }
        }
        if (docData.has("title")) b.title(docData.path("title").asText());
        if (docData.has("context")) b.context(docData.path("context").asText());
        return b.build();
    }
}
