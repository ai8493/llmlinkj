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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 将 IR（UnifiedChatRequest）转换为 Anthropic SDK MessageCreateParams。
 * 处理 system 分离、tool result 包装为 user message、thinking block 等协议差异。
 */
@SuppressWarnings("deprecation")
public class AnthropicRequestConverter {

    private static final Logger log = LoggerFactory.getLogger(AnthropicRequestConverter.class);

    // 单次请求内跟踪 tool_use ID，防止上游/客户端发重复 call_id 导致 API 400
    private final java.util.Set<String> seenToolUseIds = new java.util.HashSet<>();

    private int currentMessageIndex = -1;
    private com.fasterxml.jackson.databind.JsonNode cacheControlByBlock;
    // tool_choice 的 disable_parallel_tool_use 子字段,从 AnthropicExtensions 读取
    private Boolean disableParallelToolUse;
    // 当前请求(供 toToolResultBlock 访问 anthropic extensions;每次 convert 调用赋值)
    private UnifiedChatRequest req;

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
        this.req = req;
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
                    String stripped = com.ai8493.llmproxy.util.BillingHeaderStripper.strip(
                        block.path("text").asText());
                    // 剥离后为空则跳过该 block(避免输出空 text block)
                    if (stripped == null || stripped.isEmpty()) {
                        continue;
                    }
                    var tbBuilder = TextBlockParam.builder().text(stripped);
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
        // pendingUserMessage: 暂存 user 消息,等到遇到 assistant 或循环结束时合并 [pendingToolResults + user] 输出
        //   避免 [user(text), TOOL(tool_result)] 出站成连续两条 user(违反 anthropic 协议,minimax 400)
        UnifiedMessage pendingUserMessage = null;
        int pendingUserMessageIdx = -1;
        List<ContentBlockParam> pendingToolResults = null;
        int messageIndex = 0;
        for (UnifiedMessage msg : req.messages()) {
            if (msg.role() == UnifiedMessage.Role.SYSTEM) {
                continue;
            }
            // TOOL 消息是入站从 user 消息拆出来的,不占 bodyMsgIdx;
            // currentMessageIndex 保持前一个 USER/ASSISTANT 的值(用于 tool_result 查 toolUseId 不用 position)
            if (msg.role() != UnifiedMessage.Role.TOOL) {
                currentMessageIndex = messageIndex++;
            }
            // 连续 TOOL 消息合并：Anthropic 要求同一轮 assistant 的 tool_result 在同一条 user 消息中
            if (msg.role() == UnifiedMessage.Role.TOOL) {
                String toolCallId = msg.toolCallId();
                // 孤儿 tool_result：请求中有 tool_use 但引用的 ID 不在其中，转为文本避免 API 400
                if (toolCallId != null && !toolCallId.isEmpty()
                    && !validToolUseIds.isEmpty()
                    && !validToolUseIds.contains(toolCallId)) {
                    // 先 flush 暂存的 user
                    if (pendingUserMessage != null) {
                        flushPendingUser(builder, pendingUserMessage, pendingUserMessageIdx, pendingToolResults);
                        pendingUserMessage = null;
                        pendingUserMessageIdx = -1;
                        pendingToolResults = null;
                    }
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
            // USER 消息:暂存(等后续 TOOL/assistant 决定合并或单独输出)
            if (msg.role() == UnifiedMessage.Role.USER) {
                // 先 flush 旧 pendingUserMessage(连续 user 消息,防御性处理)
                if (pendingUserMessage != null) {
                    flushPendingUser(builder, pendingUserMessage, pendingUserMessageIdx, pendingToolResults);
                    pendingUserMessage = null;
                    pendingUserMessageIdx = -1;
                    pendingToolResults = null;
                }
                pendingUserMessage = msg;
                pendingUserMessageIdx = currentMessageIndex;
                continue;
            }
            // ASSISTANT 消息:有暂存 user 合并输出,否则 flush pendingToolResults
            if (pendingUserMessage != null) {
                flushPendingUser(builder, pendingUserMessage, pendingUserMessageIdx, pendingToolResults);
                pendingUserMessage = null;
                pendingUserMessageIdx = -1;
                pendingToolResults = null;
            } else {
                pendingToolResults = flushPending(builder, pendingToolResults);
            }
            if (msg.role() == UnifiedMessage.Role.ASSISTANT) {
                convertAssistantMessage(builder, msg);
            } else {
                throw new IllegalArgumentException("不支持的 role: " + msg.role());
            }
        }
        // flush 尾部:有暂存 user 合并输出;否则 flush pendingToolResults
        if (pendingUserMessage != null) {
            flushPendingUser(builder, pendingUserMessage, pendingUserMessageIdx, pendingToolResults);
        } else {
            flushPending(builder, pendingToolResults);
        }

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
            // output_config(AnthropicExtensions)
            if (req.anthropic() != null && req.anthropic().outputConfig() != null) {
                try {
                    builder.outputConfig(toOutputConfig(req.anthropic().outputConfig()));
                } catch (Exception e) {
                    log.warn("outputConfig 重建失败,跳过: {}", e.getMessage());
                }
            }
            if (config.serviceTier() != null && !config.serviceTier().isEmpty()) {
                builder.serviceTier(MessageCreateParams.ServiceTier
                    .of(config.serviceTier()));
            }
        } else {
            builder.maxTokens(defaultMaxTokens);
        }

        // context_management(SDK 无专属字段,走 additionalBodyProperties 透传)
        if (req.anthropic() != null && req.anthropic().contextManagement() != null) {
            try {
                builder.putAdditionalBodyProperty("context_management",
                    JsonValue.fromJsonNode(req.anthropic().contextManagement()));
            } catch (Exception e) {
                log.warn("contextManagement 透传失败,跳过: {}", e.getMessage());
            }
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

    /** 按 (消息索引, block 索引) 从 cacheControlByBlock 查找 cache_control,返回 null 表示无
     *  存储格式: {"msgIdx-blockIdx": ccNode} */
    private JsonNode lookupCacheControl(int msgIdx, int blockIdx) {
        if (cacheControlByBlock == null) return null;
        JsonNode ccNode = cacheControlByBlock.path(msgIdx + "-" + blockIdx);
        if (ccNode.isMissingNode() || ccNode.isNull()) return null;
        return ccNode;
    }

    /** 按 toolUseId 从 cacheControlByBlock 查找 tool_result 的 cache_control */
    private JsonNode lookupCacheControlByToolUseId(String toolUseId) {
        if (cacheControlByBlock == null || toolUseId == null) return null;
        JsonNode ccNode = cacheControlByBlock.path(toolUseId);
        if (ccNode.isMissingNode() || ccNode.isNull()) return null;
        return ccNode;
    }

    /** 按 (消息索引, block 索引) 从 cacheControlByBlock 查找并应用 cache_control */
    private void applyCacheControl(TextBlockParam.Builder tbBuilder, int msgIdx, int blockIdx) {
        JsonNode ccNode = lookupCacheControl(msgIdx, blockIdx);
        if (ccNode != null) {
            tbBuilder.cacheControl(toCacheControl(ccNode));
        }
    }

    /** 按 (消息索引, block 索引) 从 cacheControlByBlock 查找并应用 cache_control 到 ToolUseBlockParam */
    private void applyCacheControlToToolUse(ToolUseBlockParam.Builder tuBuilder, int msgIdx, int blockIdx) {
        JsonNode ccNode = lookupCacheControl(msgIdx, blockIdx);
        if (ccNode != null) {
            tuBuilder.cacheControl(toCacheControl(ccNode));
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

    /** 从 JsonNode 构造 OutputConfig(effort + format.json_schema) */
    private OutputConfig toOutputConfig(JsonNode node) {
        var builder = OutputConfig.builder();
        if (node.has("effort")) {
            builder.effort(OutputConfig.Effort.of(node.get("effort").asText()));
        }
        if (node.has("format") && node.get("format").isObject()) {
            JsonNode fmt = node.get("format");
            if ("json_schema".equals(fmt.path("type").asText()) && fmt.has("schema")) {
                var schemaBuilder = JsonOutputFormat.Schema.builder();
                JsonNode schemaNode = fmt.get("schema");
                if (schemaNode.isObject()) {
                    schemaNode.fields().forEachRemaining(entry ->
                        schemaBuilder.putAdditionalProperty(
                            entry.getKey(), JsonValue.fromJsonNode(entry.getValue())));
                }
                builder.format(JsonOutputFormat.builder()
                    .schema(schemaBuilder.build())
                    .build());
            }
        }
        return builder.build();
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
        // hasReasoning:只要有 reasoningContent 或 thinkingSignature 就重建 thinking block
        // 注意:不能用 !reasoningContent.isEmpty(),否则 thinking=""(后端返回空 thinking)时
        // thinking block 不重建,signature 丢失,影响 thinking 续接
        boolean hasReasoning = reasoningContent != null || msg.thinkingSignature() != null;
        String text = msg.content();
        boolean hasText = text != null && !text.isEmpty();

        if (hasToolCalls || hasReasoning) {
            // 需要按块（block）构建（thinking + text + tool_use）
            List<ContentBlockParam> blocks = new ArrayList<>();
            int blockIndex = 0;

            // Thinking 块（如果有 reasoningContent 或 signature）-- 无 cache_control(ThinkingBlockParam 无此字段)
            if (hasReasoning) {
                // reasoningContent 可能为 null（SDK 把空字符串解析成 Optional.empty）,
                // 此时 thinking 设为 ""（与入站 thinking="" 一致）
                var tbBuilder = ThinkingBlockParam.builder()
                        .thinking(reasoningContent != null ? reasoningContent : "");
                // signature: 入站有则透传;无则用 JsonMissing(SDK 序列化时 @ExcludeMissing 忽略,不出现在出站 body)
                if (msg.thinkingSignature() != null) {
                    tbBuilder.signature(msg.thinkingSignature());
                } else {
                    tbBuilder.signature(com.anthropic.core.JsonMissing.of());
                }
                blocks.add(ContentBlockParam.ofThinking(tbBuilder.build()));
                blockIndex++;
            }

            // Text 块
            if (hasText) {
                var tbBuilder = TextBlockParam.builder().text(text);
                applyCacheControl(tbBuilder, currentMessageIndex, blockIndex);
                blocks.add(ContentBlockParam.ofText(tbBuilder.build()));
                blockIndex++;
            }

            // Tool use 块
            if (hasToolCalls) {
                for (UnifiedToolCall tc : toolCalls) {
                    String uniqueId = uniquifyToolUseId(tc.id());
                    var tuBuilder = ToolUseBlockParam.builder()
                            .id(uniqueId)
                            .name(tc.function().name())
                            .input(toToolUseInput(tc.function().arguments()));
                    applyCacheControlToToolUse(tuBuilder, currentMessageIndex, blockIndex);
                    blocks.add(ContentBlockParam.ofToolUse(tuBuilder.build()));
                    blockIndex++;
                }
            }

            builder.addAssistantMessageOfBlockParams(blocks);
        } else if (hasText) {
            // 纯文本 assistant 消息(无 thinking/tool_use,text 是 block 0)
            var tbBuilder = TextBlockParam.builder().text(text);
            applyCacheControl(tbBuilder, currentMessageIndex, 0);
            builder.addAssistantMessageOfBlockParams(List.of(
                ContentBlockParam.ofText(tbBuilder.build())));
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

    /** flush 暂存的 user 消息,合并 [toolResults, user 内容] 成一条 user message
     *  避免 [user(text), user(tool_result)] 连续两条 user(违反 anthropic 协议)
     *  currentMessageIndex 临时恢复到 user 的索引,用于 cache_control 查询
     *  无 toolResults 且无 parts 且无 cache_control 时走 string 路径(保持原行为) */
    private void flushPendingUser(MessageCreateParams.Builder builder, UnifiedMessage userMsg,
                                  int userMsgIdx, List<ContentBlockParam> toolResults) {
        int savedIdx = currentMessageIndex;
        currentMessageIndex = userMsgIdx;

        boolean hasToolResults = toolResults != null && !toolResults.isEmpty();
        boolean hasParts = userMsg.parts() != null && !userMsg.parts().isEmpty();
        String text = userMsg.content();
        boolean hasText = text != null && !text.isEmpty();

        // 无 toolResults 且无 parts: 简单 string 路径(保持原行为)
        if (!hasToolResults && !hasParts && hasText) {
            JsonNode ccNode = lookupCacheControl(currentMessageIndex, 0);
            if (ccNode == null) {
                builder.addUserMessage(text);
            } else {
                var tbBuilder = TextBlockParam.builder().text(text);
                tbBuilder.cacheControl(toCacheControl(ccNode));
                builder.addUserMessageOfBlockParams(List.of(
                    ContentBlockParam.ofText(tbBuilder.build())));
            }
            currentMessageIndex = savedIdx;
            return;
        }

        // 有 toolResults 或 parts: 走 blockParams 路径
        List<ContentBlockParam> blocks = new ArrayList<>();
        if (hasToolResults) {
            blocks.addAll(toolResults);
        }
        if (hasParts) {
            int blockIndex = blocks.size();
            for (var part : userMsg.parts()) {
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
        } else if (hasText) {
            int blockIdx = blocks.size();
            JsonNode ccNode = lookupCacheControl(currentMessageIndex, blockIdx);
            var tbBuilder = TextBlockParam.builder().text(text);
            if (ccNode != null) {
                tbBuilder.cacheControl(toCacheControl(ccNode));
            }
            blocks.add(ContentBlockParam.ofText(tbBuilder.build()));
        }
        currentMessageIndex = savedIdx;
        if (!blocks.isEmpty()) {
            builder.addUserMessageOfBlockParams(blocks);
        }
    }

    private ContentBlockParam toToolResultBlock(UnifiedMessage msg) {
        var tbBuilder = ToolResultBlockParam.builder()
                .toolUseId(msg.toolCallId());
        String toolUseId = msg.toolCallId();

        // content:优先 rawToolResultBlocks(array 含 image),fallback string
        JsonNode rawBlocks = null;
        if (req.anthropic() != null && req.anthropic().rawToolResultBlocks() != null
                && toolUseId != null) {
            rawBlocks = req.anthropic().rawToolResultBlocks().get(toolUseId);
        }
        if (rawBlocks != null) {
            try {
                tbBuilder.contentOfBlocks(toContentBlocks(rawBlocks));
            } catch (Exception e) {
                log.warn("rawToolResultBlocks 重建失败,fallback string: toolUseId={} {}", toolUseId, e.getMessage());
                tbBuilder.content(msg.content() != null ? msg.content() : "");
            }
        } else {
            tbBuilder.content(msg.content() != null ? msg.content() : "");
        }

        // is_error
        if (req.anthropic() != null && req.anthropic().toolResultIsError() != null && toolUseId != null) {
            Boolean ie = req.anthropic().toolResultIsError().get(toolUseId);
            if (ie != null) {
                tbBuilder.isError(ie);
            }
        }

        // cache_control(tool_result 用 toolUseId 查)
        JsonNode ccNode = lookupCacheControlByToolUseId(toolUseId);
        if (ccNode != null) {
            tbBuilder.cacheControl(toCacheControl(ccNode));
        }

        return ContentBlockParam.ofToolResult(tbBuilder.build());
    }

    /** 从 JsonNode(array of content block) 构造 List<ToolResultBlockParam.Content.Block> */
    private List<ToolResultBlockParam.Content.Block> toContentBlocks(JsonNode rawBlocks) {
        List<ToolResultBlockParam.Content.Block> result = new ArrayList<>();
        for (JsonNode block : rawBlocks) {
            String type = block.path("type").asText();
            switch (type) {
                case "text" -> {
                    var tb = TextBlockParam.builder().text(block.path("text").asText());
                    if (block.has("cache_control") && !block.get("cache_control").isNull()) {
                        tb.cacheControl(toCacheControl(block.get("cache_control")));
                    }
                    result.add(ToolResultBlockParam.Content.Block.ofText(tb.build()));
                }
                case "image" -> {
                    result.add(ToolResultBlockParam.Content.Block.ofImage(toImageBlockParamFromBlock(block)));
                }
                default -> log.warn("rawToolResultBlocks 含未支持的 block 类型: {}", type);
            }
        }
        return result;
    }

    /** 从 image block({type:"image", source:{...}}) 构造 ImageBlockParam
     *  注意:与 toImageBlockParam(JsonNode imageData) 不同,后者接收 IR 的 {url:...} 格式 */
    private ImageBlockParam toImageBlockParamFromBlock(JsonNode imageBlock) {
        JsonNode source = imageBlock.path("source");
        if (source.path("type").asText().equals("base64")) {
            return ImageBlockParam.builder()
                .source(ImageBlockParam.Source.ofBase64(
                    Base64ImageSource.builder()
                        .mediaType(Base64ImageSource.MediaType.of(source.path("media_type").asText("image/png")))
                        .data(source.path("data").asText())
                        .build()))
                .build();
        } else if (source.path("type").asText().equals("url")) {
            return ImageBlockParam.builder()
                .urlSource(source.path("url").asText())
                .build();
        }
        throw new IllegalArgumentException("不支持的 image source type: " + source.path("type").asText());
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

        // $schema 字段(JSON Schema 规范字段,非 SDK 内置,走 additionalProperties 透传)
        if (parameters.has("$schema")) {
            schemaBuilder.putAdditionalProperty("$schema",
                JsonValue.fromJsonNode(parameters.get("$schema")));
        }

        // additionalProperties 字段(JSON Schema 规范字段,非 SDK 内置,走 additionalProperties 透传)
        if (parameters.has("additionalProperties")) {
            schemaBuilder.putAdditionalProperty("additionalProperties",
                JsonValue.fromJsonNode(parameters.get("additionalProperties")));
        }

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
