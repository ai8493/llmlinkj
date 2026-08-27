package com.ai8493.llmproxy.adapter.anthropic;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import com.ai8493.llmproxy.adapter.ProtocolAdapter;
import com.ai8493.llmproxy.exception.BackendApiException;
import com.ai8493.llmproxy.exception.TransformException;
import com.ai8493.llmproxy.model.IndexedArgumentDelta;
import com.ai8493.llmproxy.model.UnifiedChatRequest;
import com.ai8493.llmproxy.model.UnifiedChatResponse;
import com.ai8493.llmproxy.model.UnifiedChoice;
import com.ai8493.llmproxy.model.UnifiedDelta;
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
import com.ai8493.llmproxy.model.extensions.ThinkingConfig;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.DocumentBlockParam;
import com.anthropic.models.messages.ImageBlockParam;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolChoice;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.anthropic.models.messages.ToolUnion;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Anthropic 原生协议适配器:转换 Anthropic MessageCreateParams ↔ IR (UnifiedChatRequest/Response)。
 * 入站用 SDK MessageCreateParams 反序列化,出站用 SDK Message / RawMessageStreamEvent 序列化。
 */
@Component
public class AnthropicProtocolAdapter implements ProtocolAdapter {

    private static final Logger log = LoggerFactory.getLogger(AnthropicProtocolAdapter.class);

    private final ObjectMapper mapper = new ObjectMapper()
        .registerModule(new Jdk8Module())
        .configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false);

    @Override
    public String protocolName() {
        return "anthropic";
    }

    @Override
    public UnifiedChatRequest toUnifiedRequest(byte[] rawRequest, java.util.Map<String, String> headers) {
        try {
            JsonNode root = mapper.readTree(rawRequest);

            // 1. 提取 stream(SDK 不保留)
            boolean stream = root.path("stream").asBoolean(false);

            // 2. readValue 成 SDK 类型(MessageCreateParams 本身无 @JsonCreator,用 Body 反序列化)
            MessageCreateParams.Body body = mapper.readValue(mapper.writeValueAsBytes(root), MessageCreateParams.Body.class);

            // 3. 构造 IR messages
            List<UnifiedMessage> messages = new ArrayList<>();

            // AnthropicExtensions 累积所有 anthropic 专属字段,最后统一 build
            AnthropicExtensions.Builder extBuilder = AnthropicExtensions.builder();

            // 3.1 system 字段(union: string 或 array)
            JsonNode sysNode = root.path("system");
            List<UnifiedPart> systemBlocks = null;
            JsonNode rawSystemArray = null;
            String sysText = null;

            if (sysNode.isArray()) {
                rawSystemArray = sysNode;
                systemBlocks = new ArrayList<>();
                StringBuilder sb = new StringBuilder();
                for (JsonNode block : sysNode) {
                    if ("text".equals(block.path("type").asText())) {
                        String text = block.path("text").asText();
                        systemBlocks.add(new UnifiedPart.TextPart(text));
                        if (sb.length() > 0) sb.append("\n");
                        sb.append(text);
                    }
                }
                sysText = sb.toString();
            } else if (sysNode.isTextual()) {
                sysText = sysNode.asText();
            }

            if (sysText != null && !sysText.isEmpty()) {
                UnifiedMessage.Builder msgBuilder = UnifiedMessage.builder()
                    .role(UnifiedMessage.Role.SYSTEM)
                    .content(sysText);
                if (systemBlocks != null) {
                    msgBuilder.systemBlocks(systemBlocks);
                }
                messages.add(msgBuilder.build());
            }

            if (rawSystemArray != null) {
                extBuilder.rawSystemArray(rawSystemArray);
            }

            // 3.2 messages(parseMessage 把 block 级字段累积到 collector)
            // mainMsgIdx 是"主消息索引"(非 TOOL 消息的顺序索引),与出站 convert 的 currentMessageIndex 一致
            // 纯 tool_result 消息(无 text/thinking/tool_use)不生成主消息,不递增 mainMsgIdx
            // cache_control 的 key 用 mainMsgIdx,确保出站能查到
            BlockLevelCollector collector = new BlockLevelCollector();
            int mainMsgIdx = 0;
            for (MessageParam msgParam : body.messages()) {
                List<UnifiedMessage> parsed = parseMessage(msgParam, mainMsgIdx, collector);
                // 主消息存在(列表首元素非 TOOL)时递增 mainMsgIdx
                if (!parsed.isEmpty() && parsed.get(0).role() != UnifiedMessage.Role.TOOL) {
                    mainMsgIdx++;
                }
                messages.addAll(parsed);
            }

            // 4. tools
            List<UnifiedTool> tools = body.tools()
                .map(list -> list.stream()
                    .filter(ToolUnion::isTool)
                    .map(ToolUnion::asTool)
                    .map(t -> UnifiedTool.builder()
                        .type("function")
                        .function(UnifiedFunctionDefinition.builder()
                            .name(t.name())
                            .description(t.description().orElse(""))
                            .parameters(toJsonNode(t.inputSchema()))
                            .build())
                        .build())
                    .toList())
                .orElse(List.of());

            // 5. tool_choice(union: Auto/Any/Tool/None)
            UnifiedToolChoice toolChoice = body.toolChoice().map(this::parseToolChoice).orElse(null);

            // 6. generation config
            ThinkingConfig thinkingConfig = body.thinking().map(tc -> {
                if (tc.isEnabled()) {
                    return ThinkingConfig.builder()
                        .type("enabled")
                        .budgetTokens((int) tc.asEnabled().budgetTokens())
                        .build();
                } else if (tc.isAdaptive()) {
                    return ThinkingConfig.builder()
                        .type("adaptive")
                        .build();
                } else if (tc.isDisabled()) {
                    return ThinkingConfig.builder()
                        .type("disabled")
                        .build();
                }
                return null;
            }).orElse(null);

            // top_k(@Deprecated 但可用) + service_tier(透传原始字符串,不校验合法值)
            Integer topK = body.topK().map(Long::intValue).orElse(null);
            String serviceTier = body.serviceTier().map(MessageCreateParams.ServiceTier::asString).orElse(null);

            UnifiedGenerationConfig config = UnifiedGenerationConfig.builder()
                .maxOutputTokens((int) body.maxTokens())
                .temperature(body.temperature().orElse(null))
                .topP(body.topP().orElse(null))
                .stopSequences(body.stopSequences().orElse(List.of()))
                .thinkingConfig(thinkingConfig)
                .topK(topK)
                .serviceTier(serviceTier)
                .build();

            // 7. Anthropic Extensions 累积:beta header + metadata + output_config + context_management
            if (headers != null) {
                String betaHeader = headers.get("anthropic-beta");
                if (betaHeader != null && !betaHeader.isEmpty()) {
                    List<String> betaHeaders = java.util.Arrays.stream(betaHeader.split(","))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .toList();
                    extBuilder.betaHeaders(betaHeaders);
                }
            }

            // metadata.user_id
            String metadataUserId = body.metadata()
                .flatMap(m -> m.userId())
                .orElse(null);
            if (metadataUserId != null) {
                extBuilder.metadataUserId(metadataUserId);
            }

            // output_config(SDK 有专属字段,转 JsonNode 透传)
            JsonNode outputConfig = body.outputConfig()
                .map(oc -> toJsonNode(oc))
                .orElse(null);
            if (outputConfig != null && !outputConfig.isNull()) {
                extBuilder.outputConfig(outputConfig);
            }

            // context_management(SDK 无专属字段,从 raw JSON 取)
            JsonNode contextManagement = root.path("context_management");
            if (contextManagement.isMissingNode() || contextManagement.isNull()) {
                contextManagement = null;
            }
            if (contextManagement != null) {
                extBuilder.contextManagement(contextManagement);
            }

            // 把 collector 的 block 级字段 set 到 extBuilder
            if (collector.cacheControlByBlock != null) {
                extBuilder.cacheControlByBlock(collector.cacheControlByBlock);
            }
            if (collector.toolResultIsError != null) {
                extBuilder.toolResultIsError(collector.toolResultIsError);
            }
            if (collector.rawToolResultBlocks != null) {
                extBuilder.rawToolResultBlocks(collector.rawToolResultBlocks);
            }

            AnthropicExtensions anthropicExt = extBuilder.build();

            return UnifiedChatRequest.builder()
                .model(body.model().asString())
                .messages(messages)
                .tools(tools)
                .toolChoice(toolChoice)
                .config(config)
                .stream(stream)
                .anthropic(anthropicExt)
                .build();
        } catch (Exception e) {
            throw new IllegalArgumentException("无法解析 Anthropic 请求", e);
        }
    }

    /** 收集 parseMessage 遍历的 block 级字段,最后 set 到 AnthropicExtensions.Builder */
    private static class BlockLevelCollector {
        com.fasterxml.jackson.databind.node.ObjectNode cacheControlByBlock = null;
        java.util.Map<String, Boolean> toolResultIsError = null;
        java.util.Map<String, com.fasterxml.jackson.databind.JsonNode> rawToolResultBlocks = null;
    }

    /** 解析单个 MessageParam -> List<UnifiedMessage>(tool_result 拆为独立 TOOL 消息,排在主消息之后)
     *  block 级字段(cache_control/is_error/rawToolResultBlocks)按 key 累积到 extBuilder:
     *  - text/tool_use 的 cache_control: key = "mainMsgIdx-blockIdx"
     *  - tool_result 的 cache_control/is_error/rawBlocks: key = toolUseId
     *  mainMsgIdx 是主消息顺序索引(与出站 convert 的 currentMessageIndex 一致),
     *  纯 tool_result 消息不生成主消息,不消耗 mainMsgIdx(由调用方判断是否递增) */
    private List<UnifiedMessage> parseMessage(MessageParam msgParam, int mainMsgIdx,
                                              BlockLevelCollector collector) {
        String role = msgParam.role().asString();
        List<UnifiedMessage> result = new ArrayList<>();

        // content 可能是 string 或 array(block params)
        if (msgParam.content().isString()) {
            UnifiedMessage.Role irRole = "assistant".equals(role) ? UnifiedMessage.Role.ASSISTANT : UnifiedMessage.Role.USER;
            result.add(UnifiedMessage.builder()
                .role(irRole)
                .content(msgParam.content().asString())
                .build());
            return result;
        }

        // array 形态:遍历 content blocks
        StringBuilder textBuf = new StringBuilder();
        // 收集 text blocks(保留 block 边界,用于 cache_control)
        List<UnifiedPart.TextPart> textParts = new ArrayList<>();
        boolean anyTextHasCacheControl = false;
        String reasoningContent = null;
        String thinkingSignature = null;
        List<UnifiedToolCall> toolCalls = new ArrayList<>();
        List<UnifiedMessage> toolResults = new ArrayList<>();
        List<UnifiedPart> parts = new ArrayList<>();

        int blockIndex = 0;
        for (ContentBlockParam block : msgParam.content().asBlockParams()) {
            String positionKey = mainMsgIdx + "-" + blockIndex;

            if (block.isText()) {
                // text block 的 cache_control
                JsonNode ccNode = extractBlockCacheControl(block);
                if (ccNode != null) {
                    putCacheControl(collector, positionKey, ccNode);
                    anyTextHasCacheControl = true;
                }
                textParts.add(new UnifiedPart.TextPart(block.asText().text()));
            } else if (block.isThinking()) {
                // 第三方后端可能不返回 thinking/signature 字段,用 _xxx().asKnown() 安全访问避免 SDK 抛 AnthropicInvalidDataException
                reasoningContent = block.asThinking()._thinking().asKnown().orElse(null);
                thinkingSignature = block.asThinking()._signature().asKnown().orElse(null);
            } else if (block.isToolUse()) {
                // tool_use block 的 cache_control
                JsonNode ccNode = extractBlockCacheControl(block);
                if (ccNode != null) {
                    putCacheControl(collector, positionKey, ccNode);
                }
                var tu = block.asToolUse();
                toolCalls.add(UnifiedToolCall.builder()
                    .id(tu.id())
                    .type("function")
                    .function(UnifiedFunctionCall.builder()
                        .name(tu.name())
                        .arguments(toJsonNode(tu.input()))
                        .build())
                    .build());
            } else if (block.isToolResult()) {
                // tool_result 拆为独立 TOOL 消息(暂存,主消息之后追加)
                var tr = block.asToolResult();
                String trContent = extractToolResultContent(tr);
                toolResults.add(UnifiedMessage.builder()
                    .role(UnifiedMessage.Role.TOOL)
                    .toolCallId(tr.toolUseId())
                    .content(trContent)
                    .build());

                // tool_result 的 block 级字段用 toolUseId 作 key
                String toolUseId = tr.toolUseId();
                if (toolUseId != null && !toolUseId.isEmpty()) {
                    // is_error
                    tr.isError().ifPresent(ie -> {
                        if (collector.toolResultIsError == null) {
                            collector.toolResultIsError = new java.util.HashMap<>();
                        }
                        collector.toolResultIsError.put(toolUseId, ie);
                    });
                    // cache_control
                    JsonNode ccNode = extractBlockCacheControl(block);
                    if (ccNode != null) {
                        putCacheControl(collector, toolUseId, ccNode);
                    }
                    // rawToolResultBlocks:content 是 array 时存(string 走 IR.content)
                    if (tr.content().isPresent() && tr.content().get().isBlocks()) {
                        JsonNode rawBlocks = toJsonNode(tr.content().get().asBlocks());
                        if (collector.rawToolResultBlocks == null) {
                            collector.rawToolResultBlocks = new java.util.HashMap<>();
                        }
                        collector.rawToolResultBlocks.put(toolUseId, rawBlocks);
                    }
                }
            } else if (block.isImage()) {
                // image block → ImagePart,转换为统一 data-URL 格式(避免 SDK 污染 + 跨后端兼容)
                parts.add(new UnifiedPart.ImagePart(convertImageToDataUrl(block.asImage())));
            } else if (block.isRedactedThinking()) {
                // redacted_thinking block → RedactedThinkingPart(仅 data 字段)
                var rt = block.asRedactedThinking();
                parts.add(new UnifiedPart.RedactedThinkingPart(
                    mapper.createObjectNode().put("data", rt.data())));
            } else if (block.isDocument()) {
                // document block → DocumentPart,手动提取核心字段(避免 SDK 内部字段污染)
                parts.add(new UnifiedPart.DocumentPart(convertDocumentToJson(block.asDocument())));
            }
            blockIndex++;
        }

        // 处理 text:
        // - 有 cache_control: textParts 放到 parts 开头(保留 block 边界,convert 走 parts 路径按 blockIndex 应用 cache_control)
        // - 有其他 parts(image 等): textParts 拼接到 textBuf,再放到 parts 开头(原行为)
        // - 否则: textParts 拼接到 textBuf(原行为,content 是 string)
        if (!textParts.isEmpty()) {
            if (anyTextHasCacheControl) {
                parts.addAll(0, textParts);
            } else if (!parts.isEmpty()) {
                for (var tp : textParts) {
                    textBuf.append(tp.text());
                }
                parts.add(0, new UnifiedPart.TextPart(textBuf.toString()));
            } else {
                for (var tp : textParts) {
                    textBuf.append(tp.text());
                }
            }
        }

        // 主消息(text + thinking + tool_use 合并)
        UnifiedMessage.Role irRole = "assistant".equals(role) ? UnifiedMessage.Role.ASSISTANT : UnifiedMessage.Role.USER;
        if (textBuf.length() > 0 || reasoningContent != null || !toolCalls.isEmpty() || !parts.isEmpty()) {
            result.add(UnifiedMessage.builder()
                .role(irRole)
                .content(textBuf.length() > 0 ? textBuf.toString() : null)
                .toolCalls(toolCalls.isEmpty() ? null : toolCalls)
                .reasoningContent(reasoningContent)
                .thinkingSignature(thinkingSignature)
                .parts(parts.isEmpty() ? null : parts)
                .build());
        }

        // 追加 tool_result 消息
        result.addAll(toolResults);
        return result;
    }

    /** 从 ContentBlockParam 提取 cache_control,返回 {type:ephemeral[, ttl:...]} JsonNode 或 null
     *  SDK 的 ContentBlockParam 是 sealed interface,各子类有 cacheControl() 方法返回 Optional<CacheControlEphemeral> */
    private JsonNode extractBlockCacheControl(ContentBlockParam block) {
        java.util.Optional<com.anthropic.models.messages.CacheControlEphemeral> ccOpt = java.util.Optional.empty();
        if (block.isText()) {
            ccOpt = block.asText().cacheControl();
        } else if (block.isToolUse()) {
            ccOpt = block.asToolUse().cacheControl();
        } else if (block.isToolResult()) {
            ccOpt = block.asToolResult().cacheControl();
        } else if (block.isImage()) {
            ccOpt = block.asImage().cacheControl();
        } else if (block.isDocument()) {
            ccOpt = block.asDocument().cacheControl();
        }
        // 注:ThinkingBlockParam 无 cacheControl 字段(规范不需要)
        if (ccOpt.isEmpty()) return null;
        var cc = ccOpt.get();
        ObjectNode node = mapper.createObjectNode();
        // _type() 返回 JsonValue(继承 JsonField,Java 里是 raw type),用 asString() 取字符串,默认 ephemeral
        String typeStr = (String) cc._type().asString().orElse("ephemeral");
        node.put("type", typeStr);
        cc.ttl().ifPresent(ttl -> node.put("ttl", ttl.asString()));
        return node;
    }

    /** 把 cache_control 按 key 写入 collector.cacheControlByBlock(JsonNode 累积) */
    private void putCacheControl(BlockLevelCollector collector, String key, JsonNode ccNode) {
        if (collector.cacheControlByBlock == null) {
            collector.cacheControlByBlock = mapper.createObjectNode();
        }
        collector.cacheControlByBlock.set(key, ccNode);
    }

    /** 提取 tool_result 的 content 文本(string 直接取,array 拼接 text 块) */
    private String extractToolResultContent(ToolResultBlockParam tr) {
        if (tr.content().isEmpty()) return "";
        var c = tr.content().get();
        if (c.isString()) return c.asString();
        // array 形态(List<Block>)
        StringBuilder sb = new StringBuilder();
        try {
            for (var b : c.asBlocks()) {
                if (b.isText()) {
                    sb.append(b.asText().text());
                }
            }
        } catch (Exception ignored) {}
        return sb.toString();
    }

    /**
     * 把 Anthropic image block 转换为统一 data-URL 格式。
     * 输入: {"source":{"type":"base64","media_type":"image/png","data":"..."}}
     * 输出: {"url":"data:image/png;base64,...","detail":null}
     * 跨后端契约(OpenAiRequestConverter 读 url/detail 字段)。
     */
    private JsonNode convertImageToDataUrl(ImageBlockParam img) {
        ObjectNode result = mapper.createObjectNode();
        ImageBlockParam.Source source = img.source();
        if (source.isBase64()) {
            var base64 = source.asBase64();
            String mediaType = base64.mediaType().asString();
            String data = base64.data();
            result.put("url", "data:" + mediaType + ";base64," + data);
        } else if (source.isUrl()) {
            result.put("url", source.asUrl().url());
        }
        result.putNull("detail");
        return result;
    }

    /**
     * 把 Anthropic document block 转换为干净 JsonNode(手动提取核心字段,避免 SDK 内部字段污染)。
     * source 是 union(base64/text/content/url),按分支提取 data/media_type 或 url;
     * 顶层 title/context 也一并保留。
     */
    private JsonNode convertDocumentToJson(DocumentBlockParam doc) {
        ObjectNode result = mapper.createObjectNode();
        DocumentBlockParam.Source source = doc.source();
        if (source.isBase64()) {
            result.put("source_type", "base64");
            result.put("media_type", "application/pdf");
            result.put("data", source.asBase64().data());
        } else if (source.isText()) {
            result.put("source_type", "text");
            result.put("media_type", "text/plain");
            result.put("data", source.asText().data());
        } else if (source.isUrl()) {
            result.put("source_type", "url");
            result.put("url", source.asUrl().url());
        } else if (source.isContent()) {
            // content source 是嵌套 block 数组,暂不深入提取,留待后续支持
            result.put("source_type", "content");
        }
        doc.title().ifPresent(t -> result.put("title", t));
        doc.context().ifPresent(c -> result.put("context", c));
        return result;
    }

    /** ToolChoice union → IR UnifiedToolChoice(any 保留为 Any,不降级 Auto) */
    private UnifiedToolChoice parseToolChoice(ToolChoice tc) {
        if (tc.isAuto()) return UnifiedToolChoice.Auto.builder().build();
        if (tc.isAny()) return UnifiedToolChoice.Any.builder().build();
        if (tc.isTool()) {
            return UnifiedToolChoice.Required.builder()
                .functionName(tc.asTool().name())
                .build();
        }
        if (tc.isNone()) return UnifiedToolChoice.None.builder().build();
        return UnifiedToolChoice.Auto.builder().build();
    }

    /** SDK 对象 → JsonNode(用于 input_schema / tool_use input 等任意 JSON 字段) */
    private JsonNode toJsonNode(Object sdkObject) {
        try {
            return mapper.readTree(mapper.writeValueAsString(sdkObject));
        } catch (Exception e) {
            return mapper.createObjectNode();
        }
    }

    @Override
    public byte[] fromUnifiedResponse(UnifiedChatResponse uResp) {
        try {
            // 优先用原始响应 JSON(anthropic->anthropic 同协议字段零损失)
            if (uResp.anthropic() != null && uResp.anthropic().responseRawMessage() != null) {
                JsonNode raw = uResp.anthropic().responseRawMessage();
                ObjectNode root = raw.deepCopy();
                // SDK NON_NULL 序列化会跳过 null/0 字段,需补全协议规范要求必须存在的字段
                if (!root.has("stop_sequence")) {
                    root.putNull("stop_sequence");
                }
                JsonNode usageNode = root.get("usage");
                if (usageNode != null && usageNode.isObject()) {
                    ObjectNode usage = (ObjectNode) usageNode;
                    if (!usage.has("cache_creation_input_tokens")) {
                        usage.put("cache_creation_input_tokens", 0);
                    }
                    if (!usage.has("cache_read_input_tokens")) {
                        usage.put("cache_read_input_tokens", 0);
                    }
                }
                return mapper.writeValueAsBytes(root);
            }

            var root = mapper.createObjectNode();
            root.put("id", uResp.id());
            root.put("type", "message");
            root.put("role", "assistant");
            root.put("model", uResp.model());

            // content blocks
            var contentArr = mapper.createArrayNode();
            if (uResp.choices() != null && !uResp.choices().isEmpty()) {
                UnifiedChoice choice = uResp.choices().get(0);
                UnifiedMessage msg = choice.message();

                if (msg != null) {
                    // thinking 块(reasoningContent + signature)
                    if (msg.reasoningContent() != null && !msg.reasoningContent().isEmpty()) {
                        var thinkingBlock = mapper.createObjectNode();
                        thinkingBlock.put("type", "thinking");
                        thinkingBlock.put("thinking", msg.reasoningContent());
                        if (msg.thinkingSignature() != null) {
                            thinkingBlock.put("signature", msg.thinkingSignature());
                        }
                        contentArr.add(thinkingBlock);
                    }
                    // text 块
                    if (msg.content() != null && !msg.content().isEmpty()) {
                        var textBlock = mapper.createObjectNode();
                        textBlock.put("type", "text");
                        textBlock.put("text", msg.content());
                        contentArr.add(textBlock);
                    }
                    // tool_use 块
                    if (msg.toolCalls() != null) {
                        for (UnifiedToolCall tc : msg.toolCalls()) {
                            var toolBlock = mapper.createObjectNode();
                            toolBlock.put("type", "tool_use");
                            toolBlock.put("id", tc.id());
                            toolBlock.put("name", tc.function().name());
                            // arguments 已是 JsonNode,直接 set;null 时放空 object
                            JsonNode input = tc.function().arguments();
                            toolBlock.set("input", input != null ? input : mapper.createObjectNode());
                            contentArr.add(toolBlock);
                        }
                    }
                }

                // stop_reason / stop_message(规范要求同发)
                root.put("stop_reason", mapStopReason(choice.finishReason()));
                // stop_sequence: 优先用 extensions.matchedStopSequence,否则 null
                String stopSeq = uResp.anthropic() != null
                    ? uResp.anthropic().matchedStopSequence() : null;
                if (stopSeq != null) {
                    root.put("stop_sequence", stopSeq);
                } else {
                    root.putNull("stop_sequence");
                }
            }

            root.set("content", contentArr);

            // usage
            if (uResp.usage() != null) {
                var usage = mapper.createObjectNode();
                usage.put("input_tokens", uResp.usage().promptTokens());
                usage.put("output_tokens", uResp.usage().completionTokens());
                // cache 三桶(计费恒等式: input_tokens + cache_read_input_tokens + cache_creation_input_tokens == 原 prompt_tokens)
                // Anthropic 协议规范要求 cache 字段即使为 0 也输出
                usage.put("cache_read_input_tokens", uResp.usage().cachedTokens());
                usage.put("cache_creation_input_tokens", uResp.usage().cacheCreationTokens());
                root.set("usage", usage);
            }

            return mapper.writeValueAsBytes(root);
        } catch (Exception e) {
            throw new TransformException("序列化 Anthropic 响应失败", e);
        }
    }

    /** IR finishReason → Anthropic stop_reason 映射 */
    private String mapStopReason(String finishReason) {
        if (finishReason == null) return "end_turn";
        // spec 第 5 节:同协议时 IR 存 Anthropic 原值(小写),直接用
        try {
            com.anthropic.models.messages.StopReason.Known.valueOf(finishReason.toUpperCase());
            return finishReason;
        } catch (IllegalArgumentException e) {
            // 跨协议:按语义映射 OpenAI 归一化值
            return switch (finishReason) {
                case "stop" -> "end_turn";
                case "length" -> "max_tokens";
                case "tool_calls" -> "tool_use";
                case "content_filter" -> "refusal";
                default -> "end_turn";
            };
        }
    }

    @Override
    public String fromUnifiedStreamChunk(UnifiedChatResponse chunk) {
        // 不直接用此方法(走 toStreamEvents),保留接口实现返回空串
        return "{}";
    }

    /** 流式状态,跨 chunk 累积事件序列 */
    public static class StreamState {
        private boolean messageStarted = false;
        private int nextBlockIndex = 0;
        private Integer currentBlockIndex = null;  // 当前打开的 block index
        private String currentBlockType = null;    // 当前打开的 block type
        private String currentToolUseId = null;    // 当前 tool_use block 绑定的 id(content_block_start 时不可变)
        private String currentToolUseName = null;  // 当前 tool_use block 绑定的 name
        private String model;
        private String messageId;
        private int inputTokens = 0;
        private int outputTokens = 0;
        private String stopReason = null;
        // message_delta 延迟发送相关(修复点 23):暂存 finishReason + outputTokens,延迟到 finalizeStream 发
        private boolean hasEmittedMessageDelta = false;
        private String pendingStopReason = null;
        private Integer pendingOutputTokens = null;
        private int cachedTokens = 0;
        private int cacheCreationTokens = 0;
        private int reasoningTokens = 0;

        public boolean isMessageStarted() { return messageStarted; }
        public void markMessageStarted() { this.messageStarted = true; }
        public int allocateBlockIndex(String type) {
            return nextBlockIndex++;
        }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public String getMessageId() { return messageId; }
        public void setMessageId(String messageId) { this.messageId = messageId; }
        public int getInputTokens() { return inputTokens; }
        public void setInputTokens(int t) { this.inputTokens = t; }
        public int getOutputTokens() { return outputTokens; }
        public void setOutputTokens(int t) { this.outputTokens = t; }
        public String getStopReason() { return stopReason; }
        public void setStopReason(String r) { this.stopReason = r; }
        public int getCachedTokens() { return cachedTokens; }
        public void setCachedTokens(int t) { this.cachedTokens = t; }
        public int getCacheCreationTokens() { return cacheCreationTokens; }
        public void setCacheCreationTokens(int t) { this.cacheCreationTokens = t; }
        public int getReasoningTokens() { return reasoningTokens; }
        public void setReasoningTokens(int t) { this.reasoningTokens = t; }
        public boolean hasEmittedMessageDelta() { return hasEmittedMessageDelta; }
        public void setHasEmittedMessageDelta(boolean v) { this.hasEmittedMessageDelta = v; }
        public String getPendingStopReason() { return pendingStopReason; }
        public void setPendingStopReason(String r) { this.pendingStopReason = r; }
        public Integer getPendingOutputTokens() { return pendingOutputTokens; }
        public void setPendingOutputTokens(Integer t) { this.pendingOutputTokens = t; }
        public Integer getCurrentBlockIndex() { return currentBlockIndex; }
        public void setCurrentBlockIndex(Integer idx) { this.currentBlockIndex = idx; }
        public String getCurrentBlockType() { return currentBlockType; }
        public void setCurrentBlockType(String type) { this.currentBlockType = type; }
        public String getCurrentToolUseId() { return currentToolUseId; }
        public void setCurrentToolUseId(String id) { this.currentToolUseId = id; }
        public String getCurrentToolUseName() { return currentToolUseName; }
        public void setCurrentToolUseName(String name) { this.currentToolUseName = name; }
        /** 清理 currentBlockIndex/Type/ToolUseId/ToolUseName 等状态,stop 事件由 closeBlockEvent 发 */
        public void closeCurrentBlock() {
            if (currentBlockIndex != null) {
                currentBlockIndex = null;
                currentBlockType = null;
                currentToolUseId = null;
                currentToolUseName = null;
            }
        }
    }

    /**
     * 把 IR chunk 转成 Anthropic SSE 事件 JSON 字符串列表(一个 chunk 可能产生多个事件)。
     */
    public java.util.List<String> toStreamEvents(UnifiedChatResponse chunk, StreamState state) {
        java.util.List<String> events = new java.util.ArrayList<>();

        // 1. message_start(首个 chunk)
        if (!state.isMessageStarted()) {
            if (chunk.id() != null) state.setMessageId(chunk.id());
            if (chunk.model() != null) state.setModel(chunk.model());
            if (chunk.usage() != null) {
                if (chunk.usage().promptTokens() > 0) {
                    state.setInputTokens(chunk.usage().promptTokens());
                }
                state.setCachedTokens(chunk.usage().cachedTokens());
                state.setCacheCreationTokens(chunk.usage().cacheCreationTokens());
                state.setReasoningTokens(chunk.usage().reasoningTokens());
            }
            var start = mapper.createObjectNode();
            start.put("type", "message_start");
            var message = mapper.createObjectNode();
            message.put("id", state.getMessageId() != null ? state.getMessageId() : "");
            message.put("type", "message");
            message.put("role", "assistant");
            message.put("model", state.getModel() != null ? state.getModel() : "");
            // Anthropic 规范要求 message_start.message 包含 content/stop_reason/stop_sequence
            message.set("content", mapper.createArrayNode());
            message.putNull("stop_reason");
            message.putNull("stop_sequence");
            var usage = mapper.createObjectNode();
            usage.put("input_tokens", state.getInputTokens());
            usage.put("output_tokens", state.getOutputTokens());
            // cache 三桶(计费恒等式: input_tokens + cache_read_input_tokens + cache_creation_input_tokens == 原 prompt_tokens)
            // Anthropic 协议规范要求 cache 字段即使为 0 也输出
            usage.put("cache_read_input_tokens", state.getCachedTokens());
            usage.put("cache_creation_input_tokens", state.getCacheCreationTokens());
            message.set("usage", usage);
            start.set("message", message);
            events.add(writeJson(start));
            state.markMessageStarted();
        }

        if (chunk.choices() == null || chunk.choices().isEmpty()) {
            // 仅 usage 更新的 chunk
            if (chunk.usage() != null && chunk.usage().completionTokens() > 0) {
                state.setOutputTokens(chunk.usage().completionTokens());
                // 若已暂存 message_delta,同步更新 pendingOutputTokens(最终值可能延后到流末尾)
                if (state.hasEmittedMessageDelta()) {
                    state.setPendingOutputTokens(state.getOutputTokens());
                }
            }
            return events;
        }

        UnifiedChoice choice = chunk.choices().get(0);
        UnifiedDelta delta = choice.delta();
        UnifiedMessage message = choice.message();

        // 提取 delta 内容(delta 优先,回退到 message)
        String reasoningContent = null;
        String thinkingSignature = null;
        String textContent = null;
        java.util.List<UnifiedToolCall> toolCalls = null;

        if (delta != null) {
            reasoningContent = delta.reasoningContent();
            thinkingSignature = delta.thinkingSignature();
            textContent = delta.content();
            toolCalls = delta.toolCalls();
        } else if (message != null) {
            reasoningContent = message.reasoningContent();
            thinkingSignature = message.thinkingSignature();
            textContent = message.content();
            toolCalls = message.toolCalls();
        }

        // 2. thinking block(content_block_start + thinking_delta,signature 不放这里)
        if (reasoningContent != null && !reasoningContent.isEmpty()) {
            events.addAll(emitContentBlock(state, "thinking", reasoningContent, null));
        }

        // 2.5 thinking signature 单独发 signature_delta 事件(Anthropic 流式规范)
        if (thinkingSignature != null && !thinkingSignature.isEmpty()) {
            events.add(emitSignatureDelta(state, thinkingSignature));
        }

        // 3. text block
        if (textContent != null && !textContent.isEmpty()) {
            events.addAll(emitContentBlock(state, "text", textContent, null));
        }

        // 4. tool_use block
        if (toolCalls != null && !toolCalls.isEmpty()) {
            for (UnifiedToolCall tc : toolCalls) {
                events.addAll(emitToolUseBlock(state, tc));
            }
        }

        // 4.5 tool_call arguments 增量(真流式,按 index 发 input_json_delta,支持多 block 并行)
        if (delta != null && delta.toolCallArgumentDeltas() != null) {
            for (IndexedArgumentDelta d : delta.toolCallArgumentDeltas()) {
                if (d.index() == null) {
                    log.warn("Anthropic 出站 IndexedArgumentDelta index 为 null,跳过: partial={}", d.partialJson());
                    continue;
                }
                events.add(emitInputJsonDelta(d.index(), d.partialJson()));
            }
        }

        // 5. message_delta 延迟发送(finishReason 出现时暂存,不立即发;finalizeStream 时发)
        if (choice.finishReason() != null) {
            state.setStopReason(mapStopReason(choice.finishReason()));
        }
        if (chunk.usage() != null && chunk.usage().completionTokens() > 0) {
            state.setOutputTokens(chunk.usage().completionTokens());
        }

        if (state.getStopReason() != null) {
            // 关闭当前打开的 block(若有)
            if (state.getCurrentBlockIndex() != null) {
                events.add(closeBlockEvent(state));
            }

            // 暂存 stopReason + outputTokens,不立即发 message_delta(延迟到 finalizeStream)
            if (!state.hasEmittedMessageDelta()) {
                state.setHasEmittedMessageDelta(true);
                state.setPendingStopReason(state.getStopReason());
                state.setPendingOutputTokens(state.getOutputTokens());
            } else {
                // 已缓存,更新 output_tokens(若有更完整值)
                state.setPendingOutputTokens(state.getOutputTokens());
            }
        }

        return events;
    }

    /**
     * 流末尾发送 pending message_delta + message_stop。
     * 由 ProxyController 在 Flux 完成时通过 concatWith 调用。
     * 若 state 无 pendingStopReason(异常情况,无 finishReason),返回空列表。
     */
    public java.util.List<String> finalizeStream(StreamState state) {
        java.util.List<String> events = new java.util.ArrayList<>();
        if (state.getPendingStopReason() != null) {
            // message_delta
            var msgDelta = mapper.createObjectNode();
            msgDelta.put("type", "message_delta");
            var d = mapper.createObjectNode();
            d.put("stop_reason", state.getPendingStopReason());
            d.putNull("stop_sequence");
            msgDelta.set("delta", d);
            var u = mapper.createObjectNode();
            u.put("output_tokens", state.getPendingOutputTokens() != null ? state.getPendingOutputTokens() : 0);
            // Anthropic 规范要求 message_delta.usage 也包含 input_tokens(最终值)
            u.put("input_tokens", state.getInputTokens());
            msgDelta.set("usage", u);
            events.add(writeJson(msgDelta));

            // message_stop
            var msgStop = mapper.createObjectNode();
            msgStop.put("type", "message_stop");
            events.add(writeJson(msgStop));
        }
        return events;
    }

    /** 生成 content_block_stop 事件并关闭当前 block */
    private String closeBlockEvent(StreamState state) {
        int idx = state.getCurrentBlockIndex();
        var stop = mapper.createObjectNode();
        stop.put("type", "content_block_stop");
        stop.put("index", idx);
        state.closeCurrentBlock();
        return writeJson(stop);
    }

    private java.util.List<String> emitContentBlock(StreamState state, String type, String content, String signature) {
        java.util.List<String> events = new java.util.ArrayList<>();

        // 若当前 block 类型不同,先关闭旧的再开新的
        if (state.getCurrentBlockType() != null && !state.getCurrentBlockType().equals(type)) {
            events.add(closeBlockEvent(state));
        }

        // 若无打开的 block,开新的
        if (state.getCurrentBlockIndex() == null) {
            int idx = state.allocateBlockIndex(type);
            state.setCurrentBlockIndex(idx);
            state.setCurrentBlockType(type);

            // content_block_start(signature 不放这里,通过 signature_delta 事件单独发送)
            var start = mapper.createObjectNode();
            start.put("type", "content_block_start");
            start.put("index", idx);
            var block = mapper.createObjectNode();
            block.put("type", type);
            start.set("content_block", block);
            events.add(writeJson(start));
        }

        // content_block_delta
        int idx = state.getCurrentBlockIndex();
        var deltaEvt = mapper.createObjectNode();
        deltaEvt.put("type", "content_block_delta");
        deltaEvt.put("index", idx);
        var delta = mapper.createObjectNode();
        if ("text".equals(type)) {
            delta.put("type", "text_delta");
            delta.put("text", content);
        } else if ("thinking".equals(type)) {
            delta.put("type", "thinking_delta");
            delta.put("thinking", content);
        }
        deltaEvt.set("delta", delta);
        events.add(writeJson(deltaEvt));

        return events;
    }

    /** 发送 signature_delta 事件(thinking block 的 signature 单独发送,Anthropic 流式规范) */
    private String emitSignatureDelta(StreamState state, String signature) {
        Integer idx = state.getCurrentBlockIndex();
        var deltaEvt = mapper.createObjectNode();
        deltaEvt.put("type", "content_block_delta");
        deltaEvt.put("index", idx != null ? idx : 0);
        var delta = mapper.createObjectNode();
        delta.put("type", "signature_delta");
        delta.put("signature", signature);
        deltaEvt.set("delta", delta);
        return writeJson(deltaEvt);
    }

    private java.util.List<String> emitToolUseBlock(StreamState state, UnifiedToolCall tc) {
        java.util.List<String> events = new java.util.ArrayList<>();

        // 判断是否需要开新 block:当前不是 tool_use,或 id/name 不同
        boolean needNewBlock = !"tool_use".equals(state.getCurrentBlockType())
            || !java.util.Objects.equals(state.getCurrentToolUseId(), tc.id())
            || !java.util.Objects.equals(state.getCurrentToolUseName(), tc.function().name());

        if (needNewBlock) {
            // 关闭当前 block(若有)
            if (state.getCurrentBlockIndex() != null) {
                events.add(closeBlockEvent(state));
            }

            // 开新 tool_use block
            int idx = state.allocateBlockIndex("tool_use");
            state.setCurrentBlockIndex(idx);
            state.setCurrentBlockType("tool_use");
            state.setCurrentToolUseId(tc.id());
            state.setCurrentToolUseName(tc.function().name());

            var start = mapper.createObjectNode();
            start.put("type", "content_block_start");
            start.put("index", idx);
            var block = mapper.createObjectNode();
            block.put("type", "tool_use");
            block.put("id", tc.id());
            block.put("name", tc.function().name());
            block.set("input", mapper.createObjectNode());
            start.set("content_block", block);
            events.add(writeJson(start));
        }

        // input_json_delta 切分(按 20 字符)
        // 非流式后端 fallback:tc.function().arguments() 不为 null 时,按 20 字符切片发送(伪流式)
        // 流式后端:arguments 为 null(content_block_start 信号),不切片(增量通过 delta.toolCallArgumentDeltas 单独处理)
        if (tc.function().arguments() != null) {
            String fullJson;
            try {
                fullJson = mapper.writeValueAsString(tc.function().arguments());
            } catch (Exception e) {
                fullJson = "{}";
            }
            int chunkSize = 20;
            int idx = state.getCurrentBlockIndex();
            for (int i = 0; i < fullJson.length(); i += chunkSize) {
                int end = Math.min(i + chunkSize, fullJson.length());
                String partial = fullJson.substring(i, end);

                var deltaEvt = mapper.createObjectNode();
                deltaEvt.put("type", "content_block_delta");
                deltaEvt.put("index", idx);
                var delta = mapper.createObjectNode();
                delta.put("type", "input_json_delta");
                delta.put("partial_json", partial);
                deltaEvt.set("delta", delta);
                events.add(writeJson(deltaEvt));
            }
        }

        return events;
    }

    private String emitInputJsonDelta(int index, String partialJson) {
        var deltaEvt = mapper.createObjectNode();
        deltaEvt.put("type", "content_block_delta");
        deltaEvt.put("index", index);
        var delta = mapper.createObjectNode();
        delta.put("type", "input_json_delta");
        delta.put("partial_json", partialJson);
        deltaEvt.set("delta", delta);
        return writeJson(deltaEvt);
    }

    private String writeJson(JsonNode node) {
        try {
            return mapper.writeValueAsString(node);
        } catch (Exception e) {
            return "{}";
        }
    }

    /** SSE 已 committed 时的错误事件,返回 Anthropic 风格 error JSON 字符串 */
    public String errorStreamEvent(Throwable e) {
        int status = (e instanceof BackendApiException b) ? b.getStatusCode() : 502;
        String message = e.getMessage() != null ? e.getMessage() : "上游后端调用失败";
        return errorJsonString(status, message);
    }

    /** 非流式错误响应,返回 Anthropic 风格 error JSON 字节 */
    public byte[] errorResponse(Throwable e) {
        int status = (e instanceof BackendApiException b) ? b.getStatusCode() : 502;
        String message = e.getMessage() != null ? e.getMessage() : "上游后端调用失败";
        return errorJsonBytes(status, message);
    }

    /** 错误响应对应的 HTTP 状态码 */
    public int errorStatusCode(Throwable e) {
        if (e instanceof BackendApiException b) return b.getStatusCode();
        return 502;
    }

    private String errorJsonString(int status, String message) {
        try {
            return mapper.writeValueAsString(errorNode(status, message));
        } catch (Exception ex) {
            return "{\"type\":\"error\",\"error\":{\"type\":\"api_error\",\"message\":\"序列化错误失败\"}}";
        }
    }

    private byte[] errorJsonBytes(int status, String message) {
        try {
            return mapper.writeValueAsBytes(errorNode(status, message));
        } catch (Exception ex) {
            return "{\"type\":\"error\",\"error\":{\"type\":\"api_error\",\"message\":\"序列化错误失败\"}}".getBytes(StandardCharsets.UTF_8);
        }
    }

    private com.fasterxml.jackson.databind.node.ObjectNode errorNode(int status, String message) {
        var root = mapper.createObjectNode();
        root.put("type", "error");
        var err = mapper.createObjectNode();
        err.put("type", mapErrorType(status));
        err.put("message", message);
        root.set("error", err);
        return root;
    }

    private String mapErrorType(int status) {
        return switch (status) {
            case 400 -> "invalid_request_error";
            case 401 -> "authentication_error";
            case 403 -> "permission_error";
            case 404 -> "not_found_error";
            case 429 -> "rate_limit_error";
            case 503 -> "overloaded_error";
            default -> "api_error";
        };
    }
}
