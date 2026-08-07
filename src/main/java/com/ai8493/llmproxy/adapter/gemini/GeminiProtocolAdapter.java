package com.ai8493.llmproxy.adapter.gemini;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.google.genai.types.*;
import com.ai8493.llmproxy.adapter.ProtocolAdapter;
import com.ai8493.llmproxy.converter.ToolMapper;
import com.ai8493.llmproxy.exception.BackendApiException;
import com.ai8493.llmproxy.exception.TransformException;
import com.ai8493.llmproxy.model.*;
import com.ai8493.llmproxy.model.extensions.GeminiExtensions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import com.ai8493.llmproxy.cache.ReasoningStore;

/**
 * Gemini 协议适配器：转换 Gemini GenerateContentParameters ↔ IR (UnifiedChatRequest/Response)。
 */
@Component
public class GeminiProtocolAdapter implements ProtocolAdapter {

    private static final Logger log = LoggerFactory.getLogger(GeminiProtocolAdapter.class);

    private final ObjectMapper mapper = new ObjectMapper()
        .registerModule(new Jdk8Module())
        .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        .setSerializationInclusion(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL);

    private final ToolMapper toolMapper = new ToolMapper();

    private final ReasoningStore reasoningStore;

    /** 无参构造器，供测试使用（自动创建默认 ReasoningStore） */
    public GeminiProtocolAdapter() {
        this.reasoningStore = new ReasoningStore();
    }

    public GeminiProtocolAdapter(ReasoningStore reasoningStore) {
        this.reasoningStore = reasoningStore;
    }

    @Override
    public String protocolName() { return "gemini"; }

    @Override
    public UnifiedChatRequest toUnifiedRequest(byte[] rawRequest, Map<String, String> headers) {
        try {
            // SDK 反序列化可能丢失 JSON 顶层字段（systemInstruction/tools/generationConfig）
            // 先从 raw JSON 提取这些字段
            JsonNode root = mapper.readTree(rawRequest);

            // thoughtSignature 原为 byte[] 类型,Gemini CLI 注入字符串形式会导致 SDK 反序列化失败。
            // 先从 raw JSON 提取 thoughtSignature 到独立映射,再从 parts 移除该字段(避免 SDK 反序列化失败),
            // 后续在 mapContentToMessages 里填入 UnifiedMessage.thinkingSignature。
            Map<Integer, String> thoughtSignatures = extractThoughtSignatures(root);
            removeThoughtSignatureFields(root);

            String extraSysInstruction = extractSystemInstruction(root);
            List<UnifiedTool> extraTools = extractTools(root);
            UnifiedGenerationConfig extraConfig = extractGenerationConfig(root);

            GenerateContentParameters req = mapper.readValue(
                mapper.writeValueAsBytes(root), GenerateContentParameters.class);
            UnifiedChatRequest unifiedReq = toUnifiedRequest(req, extraSysInstruction, extraTools, extraConfig, thoughtSignatures);

            // ---- 从缓存注入 reasoning_content ----
            String sessionKey = extractSessionKey(headers, root);
            if (sessionKey != null) {
                String cachedReasoning = reasoningStore.get(sessionKey);
                boolean isDeepseek = req.model().isPresent()
                    && req.model().get().toLowerCase().contains("deepseek");
                List<UnifiedMessage> msgs = unifiedReq.messages();
                for (int i = 0; i < msgs.size(); i++) {
                    UnifiedMessage m = msgs.get(i);
                    if (m.role() == UnifiedMessage.Role.ASSISTANT
                            && (m.reasoningContent() == null || m.reasoningContent().isEmpty())) {
                        if (cachedReasoning != null) {
                            msgs.set(i, UnifiedMessage.builder()
                                .role(m.role())
                                .content(m.content())
                                .parts(m.parts())
                                .toolCalls(m.toolCalls())
                                .toolCallId(m.toolCallId())
                                .name(m.name())
                                .reasoningContent(cachedReasoning)
                                .build());
                        } else if (isDeepseek) {
                            msgs.set(i, UnifiedMessage.builder()
                                .role(m.role())
                                .content(m.content())
                                .parts(m.parts())
                                .toolCalls(m.toolCalls())
                                .toolCallId(m.toolCallId())
                                .name(m.name())
                                .reasoningContent("")
                                .build());
                        } else {
                            msgs.set(i, UnifiedMessage.builder()
                                .role(m.role())
                                .content(m.content())
                                .parts(m.parts())
                                .toolCalls(m.toolCalls())
                                .toolCallId(m.toolCallId())
                                .name(m.name())
                                .reasoningContent("")
                                .build());
                        }
                    }
                }
            }

            // ---- 从 raw JSON 读 Gemini 专属字段,填 GeminiExtensions ----
            GeminiExtensions geminiExt = extractGeminiExtensions(root);
            if (geminiExt != null) {
                unifiedReq = UnifiedChatRequest.builder()
                    .model(unifiedReq.model())
                    .messages(unifiedReq.messages())
                    .config(unifiedReq.config())
                    .tools(unifiedReq.tools())
                    .toolChoice(unifiedReq.toolChoice())
                    .stream(unifiedReq.stream())
                    .anthropic(unifiedReq.anthropic())
                    .openai(unifiedReq.openai())
                    .gemini(geminiExt)
                    .build();
            }

            return unifiedReq;
        } catch (Exception e) {
            throw new IllegalArgumentException("无法解析 Gemini 请求", e);
        }
    }

    /** 提取所有 thought part 的 thoughtSignature,按 content 索引聚合(同 content 多 signature 用换行拼接) */
    private Map<Integer, String> extractThoughtSignatures(JsonNode root) {
        Map<Integer, String> result = new HashMap<>();
        if (!root.has("contents") || !root.get("contents").isArray()) return result;
        for (int ci = 0; ci < root.get("contents").size(); ci++) {
            JsonNode content = root.get("contents").get(ci);
            if (!content.has("parts") || !content.get("parts").isArray()) continue;
            StringBuilder sb = new StringBuilder();
            for (JsonNode part : content.get("parts")) {
                if (part.isObject() && part.has("thoughtSignature")) {
                    if (!sb.isEmpty()) sb.append("\n");
                    sb.append(part.get("thoughtSignature").asText());
                }
            }
            if (!sb.isEmpty()) result.put(ci, sb.toString());
        }
        return result;
    }

    /** 移除所有 parts 中的 thoughtSignature 字段(SDK 反序列化需要) */
    private void removeThoughtSignatureFields(JsonNode root) {
        if (!root.has("contents") || !root.get("contents").isArray()) return;
        for (JsonNode content : root.get("contents")) {
            if (content.has("parts") && content.get("parts").isArray()) {
                for (JsonNode part : content.get("parts")) {
                    if (part.isObject() && part.has("thoughtSignature")) {
                        ((ObjectNode) part).remove("thoughtSignature");
                    }
                }
            }
        }
    }

    private String extractSystemInstruction(JsonNode root) {
        if (!root.has("systemInstruction")) return null;
        JsonNode si = root.get("systemInstruction");
        if (!si.has("parts") || !si.get("parts").isArray()) return null;
        StringBuilder sb = new StringBuilder();
        for (JsonNode p : si.get("parts")) {
            if (p.has("text")) sb.append(p.get("text").asText());
        }
        return sb.toString();
    }

    private List<UnifiedTool> extractTools(JsonNode root) {
        if (!root.has("tools") || !root.get("tools").isArray()) return null;
        return toolMapper.mapToolsFromGeminiJson(root.get("tools"));
    }

    private UnifiedGenerationConfig extractGenerationConfig(JsonNode root) {
        if (!root.has("generationConfig")) return null;
        JsonNode gc = root.get("generationConfig");
        Double temperature = gc.has("temperature") ? gc.get("temperature").asDouble() : null;
        Double topP = gc.has("topP") ? gc.get("topP").asDouble() : null;
        Integer maxOutputTokens = gc.has("maxOutputTokens") ? gc.get("maxOutputTokens").asInt() : null;
        List<String> stopSequences = null;
        if (gc.has("stopSequences") && gc.get("stopSequences").isArray()) {
            stopSequences = new ArrayList<>();
            for (JsonNode s : gc.get("stopSequences")) stopSequences.add(s.asText());
        }
        return UnifiedGenerationConfig.builder()
            .temperature(temperature)
            .topP(topP)
            .maxOutputTokens(maxOutputTokens)
            .stopSequences(stopSequences)
            .build();
    }

    /** 从 raw JSON 提取 Gemini 专属字段（responseMimeType/responseSchema/candidateCount/safetySettings） */
    private GeminiExtensions extractGeminiExtensions(JsonNode root) {
        JsonNode gcNode = root.path("generationConfig");
        boolean hasGeminiExt = gcNode.has("responseMimeType")
            || gcNode.has("responseSchema")
            || gcNode.has("candidateCount")
            || root.has("safetySettings");
        if (!hasGeminiExt) return null;
        GeminiExtensions.Builder extBuilder = GeminiExtensions.builder();
        if (gcNode.has("responseMimeType")) {
            extBuilder.responseMimeType(gcNode.get("responseMimeType").asText());
        }
        if (gcNode.has("responseSchema")) {
            extBuilder.responseSchema(gcNode.get("responseSchema"));
        }
        if (gcNode.has("candidateCount")) {
            extBuilder.candidateCount(gcNode.get("candidateCount").asInt());
        }
        if (root.has("safetySettings")) {
            extBuilder.safetySettings(root.get("safetySettings"));
        }
        return extBuilder.build();
    }

    /** Type-safe entry point: Google GenAI SDK GenerateContentParameters → IR */
    public UnifiedChatRequest toUnifiedRequest(GenerateContentParameters req,
                                                String extraSysInstruction,
                                                List<UnifiedTool> extraTools,
                                                UnifiedGenerationConfig extraConfig,
                                                Map<Integer, String> thoughtSignatures) {
        // ---- messages ----
        List<Content> contents = req.contents().orElse(List.of());
        List<UnifiedMessage> messages = new ArrayList<>();

        // 预计算每个 Content 的 functionResponse ID（null/空 → UUID 兜底）
        List<List<String>> allFnRespIds = new ArrayList<>();
        for (Content c : contents) {
            List<String> ids = c.parts().orElse(List.of()).stream()
                .flatMap(p -> p.functionResponse().stream())
                .map(fr -> {
                    String id = fr.id().orElse(null);
                    return (id != null && !id.isEmpty()) ? id
                        : "call_" + UUID.randomUUID().toString().substring(0, 8);
                })
                .toList();
            allFnRespIds.add(ids);
        }

        // 跨 Content 追踪 reasoning：Gemini CLI 可能将 thought 和 functionCall
        // 作为独立 Content 发送，需将前一个 Content 的 reasoning 带到后续消息中
        List<String> pendingReasoning = new ArrayList<>();
        boolean reasoningMerged = false;
        for (int i = 0; i < contents.size(); i++) {
            Content content = contents.get(i);
            String geminiRole = content.role().orElse("user");
            List<String> nextFnRespIds = (i + 1 < contents.size())
                ? allFnRespIds.get(i + 1) : null;
            List<UnifiedMessage> msgs = mapContentToMessages(content, nextFnRespIds,
                allFnRespIds.get(i));

            // 检测 thought-only Content（仅含 reasoning，无 text/functionCall）
            boolean thoughtOnly = msgs.size() == 1
                && msgs.get(0).role() == UnifiedMessage.Role.ASSISTANT
                && msgs.get(0).reasoningContent() != null
                && msgs.get(0).content() == null
                && msgs.get(0).toolCalls() == null;

            if (thoughtOnly) {
                // 新 thought 到达：若前一段 reasoning 已合并过，先清空再追加
                if (reasoningMerged) {
                    pendingReasoning.clear();
                    reasoningMerged = false;
                }
                pendingReasoning.add(msgs.get(0).reasoningContent());
                continue;
            }

            // 非 model Content：flush 残留 reasoning，重置状态
            if (!"model".equals(geminiRole)) {
                if (!pendingReasoning.isEmpty() && !reasoningMerged) {
                    messages.add(UnifiedMessage.builder()
                        .role(UnifiedMessage.Role.ASSISTANT)
                        .reasoningContent(String.join("\n", pendingReasoning))
                        .build());
                }
                pendingReasoning.clear();
                reasoningMerged = false;
                messages.addAll(msgs);
                continue;
            }

            // model Content（含 text/functionCall）：将 pending reasoning 合入
            // 自身没有 reasoning 的 assistant 消息（不清空 pending，让后续 Content 也能携带）
            String signature = thoughtSignatures != null ? thoughtSignatures.get(i) : null;
            if (!pendingReasoning.isEmpty()) {
                String rc = String.join("\n", pendingReasoning);
                for (int j = 0; j < msgs.size(); j++) {
                    UnifiedMessage m = msgs.get(j);
                    if (m.role() == UnifiedMessage.Role.ASSISTANT
                            && m.reasoningContent() == null) {
                        msgs.set(j, UnifiedMessage.builder()
                            .role(m.role())
                            .content(m.content())
                            .parts(m.parts())
                            .toolCalls(m.toolCalls())
                            .toolCallId(m.toolCallId())
                            .name(m.name())
                            .reasoningContent(rc)
                            .thinkingSignature(signature != null ? signature : m.thinkingSignature())
                            .build());
                        reasoningMerged = true;
                    } else if (m.role() == UnifiedMessage.Role.ASSISTANT && signature != null) {
                        msgs.set(j, UnifiedMessage.builder()
                            .role(m.role())
                            .content(m.content())
                            .parts(m.parts())
                            .toolCalls(m.toolCalls())
                            .toolCallId(m.toolCallId())
                            .name(m.name())
                            .reasoningContent(m.reasoningContent())
                            .thinkingSignature(signature)
                            .build());
                    }
                }
            } else if (signature != null) {
                for (int j = 0; j < msgs.size(); j++) {
                    UnifiedMessage m = msgs.get(j);
                    if (m.role() == UnifiedMessage.Role.ASSISTANT && m.thinkingSignature() == null) {
                        msgs.set(j, UnifiedMessage.builder()
                            .role(m.role())
                            .content(m.content())
                            .parts(m.parts())
                            .toolCalls(m.toolCalls())
                            .toolCallId(m.toolCallId())
                            .name(m.name())
                            .reasoningContent(m.reasoningContent())
                            .thinkingSignature(signature)
                            .build());
                    }
                }
            }

            messages.addAll(msgs);
        }
        // 末尾 flush 残留 reasoning（如对话以 thought-only 结尾）
        if (!pendingReasoning.isEmpty() && !reasoningMerged) {
            messages.add(UnifiedMessage.builder()
                .role(UnifiedMessage.Role.ASSISTANT)
                .reasoningContent(String.join("\n", pendingReasoning))
                .build());
        }

        // ---- systemInstruction 作为 SYSTEM 消息插入头部 ----
        String sysText = extraSysInstruction;
        if (sysText == null || sysText.isEmpty()) {
            sysText = req.config().flatMap(cfg -> cfg.systemInstruction().map(Content::text)).orElse(null);
        }
        if (sysText != null && !sysText.isEmpty()) {
            messages.add(0, UnifiedMessage.builder()
                .role(UnifiedMessage.Role.SYSTEM)
                .content(sysText)
                .build());
        }

        // ---- config（优先 SDK 解析，回退 raw JSON 提取） ----
        UnifiedGenerationConfig config = null;
        List<UnifiedTool> tools = extraTools;
        UnifiedToolChoice toolChoice = null;

        if (req.config().isPresent()) {
            var cfg = req.config().get();
            config = UnifiedGenerationConfig.builder()
                .temperature(cfg.temperature().map(Float::doubleValue).orElse(null))
                .topP(cfg.topP().map(Float::doubleValue).orElse(null))
                .maxOutputTokens(cfg.maxOutputTokens().orElse(null))
                .stopSequences(cfg.stopSequences().orElse(null))
                .build();
            if (tools == null) {
                tools = toolMapper.mapToolsFromGemini(cfg.tools().orElse(null));
            }
            toolChoice = toolMapper.mapToolChoiceFromGemini(cfg.toolConfig().orElse(null));
        }
        if (config == null) {
            config = extraConfig;
        }

        return UnifiedChatRequest.builder()
            .model(req.model().orElse(null))
            .messages(messages)
            .config(config)
            .tools(tools)
            .toolChoice(toolChoice)
            .stream(false)   // 非流式
            .build();
    }

    @Override
    public byte[] fromUnifiedResponse(UnifiedChatResponse uResp) {
        return fromUnifiedResponse(uResp, null);
    }

    public byte[] fromUnifiedResponse(UnifiedChatResponse uResp, GeminiRequestContext ctx) {
        try {
            GenerateContentResponse resp = mapToGeminiResponse(uResp, ctx);
            String json = mapper.writeValueAsString(resp);
            rememberReasoning(uResp, ctx != null ? ctx.sessionKey() : null);
            return mapper.writeValueAsBytes(stripNulls(mapper.readTree(json)));
        } catch (Exception e) {
            throw new TransformException("序列化 Gemini 响应失败", e);
        }
    }

    @Override
    public String fromUnifiedStreamChunk(UnifiedChatResponse chunk) {
        return fromUnifiedStreamChunk(chunk, null);
    }

    public String fromUnifiedStreamChunk(UnifiedChatResponse chunk, GeminiRequestContext ctx) {
        try {
            GenerateContentResponse gChunk = mapToGeminiResponse(chunk, ctx);
            String json = mapper.writeValueAsString(gChunk);
            rememberReasoning(chunk, ctx != null ? ctx.sessionKey() : null);
            return mapper.writeValueAsString(stripNulls(mapper.readTree(json)));
        } catch (Exception e) {
            throw new TransformException("序列化 Gemini 流块失败", e);
        }
    }

    /**
     * 流式响应中途出错时，构造一条 Gemini 风格的 error JSON 作为 SSE data 事件吐给客户端。
     * 用于 SSE 响应已 committed 后无法走 @ExceptionHandler 的场景。
     */
    public String errorStreamEvent(Throwable e) {
        int status = (e instanceof BackendApiException b) ? b.getStatusCode() : 502;
        String geminiStatus = switch (status) {
            case 400 -> "INVALID_ARGUMENT";
            case 401, 403 -> "PERMISSION_DENIED";
            case 429 -> "RESOURCE_EXHAUSTED";
            case 503 -> "UNAVAILABLE";
            default -> "INTERNAL";
        };
        try {
            var err = mapper.createObjectNode();
            var inner = mapper.createObjectNode();
            inner.put("code", status);
            inner.put("message", e.getMessage() != null ? e.getMessage() : "上游后端调用失败");
            inner.put("status", geminiStatus);
            err.set("error", inner);
            return mapper.writeValueAsString(err);
        } catch (Exception ex) {
            return "{\"error\":{\"code\":502,\"message\":\"上游后端调用失败\",\"status\":\"INTERNAL\"}}";
        }
    }

    /** 递归移除 JsonNode 中所有 null 字段 */
    private JsonNode stripNulls(JsonNode node) {
        if (node.isObject()) {
            var obj = mapper.createObjectNode();
            node.fields().forEachRemaining(e -> {
                if (!e.getValue().isNull()) {
                    obj.set(e.getKey(), stripNulls(e.getValue()));
                }
            });
            return obj;
        } else if (node.isArray()) {
            var arr = mapper.createArrayNode();
            node.forEach(e -> arr.add(stripNulls(e)));
            return arr;
        }
        return node;
    }

    // ===== Private mapping methods =====

    /**
     * 将 Gemini Content 转换为一条或多条 UnifiedMessage。
     * 多个 functionResponse part 会拆分为各自独立的 TOOL 消息。
     */
    private List<UnifiedMessage> mapContentToMessages(Content content, List<String> nextFnRespIds,
                                                       List<String> generatedFnRespIds) {
        String geminiRole = content.role().orElse("user");
        List<Part> parts = content.parts().orElse(List.of());

        // ---- 第一遍：分类收集各 part ----
        StringBuilder textBuf = new StringBuilder();
        StringBuilder thoughtBuf = new StringBuilder();
        List<UnifiedToolCall> toolCalls = new ArrayList<>();
        // 图片 part(inlineData)单独收集,与文本一起组成多模态 parts
        List<UnifiedPart> irParts = new ArrayList<>();
        // 每个 functionResponse 独立保存，不再合并
        List<String> frIds = new ArrayList<>();
        List<String> frNames = new ArrayList<>();
        List<String> frContents = new ArrayList<>();

        int fcIdx = 0;
        for (Part part : parts) {
            boolean isThought = part.thought().isPresent() && part.thought().get();
            if (isThought) {
                part.text().ifPresent(thoughtBuf::append);
            } else {
                part.text().ifPresent(textBuf::append);
            }
            // inlineData(图片等) -> ImagePart,转 data URL 格式(与 Anthropic 入站一致)
            if (part.inlineData().isPresent()) {
                irParts.add(convertInlineDataToImagePart(part.inlineData().get()));
            }
            if (part.functionCall().isPresent()) {
                FunctionCall fc = part.functionCall().get();
                String fnName = fc.name().orElse("");
                JsonNode argsNode = null;
                if (fc.args().isPresent()) {
                    try {
                        argsNode = mapper.valueToTree(fc.args().get());
                    } catch (Exception ignored) {}
                }
                String tcId;
                if (nextFnRespIds != null && fcIdx < nextFnRespIds.size()
                        && nextFnRespIds.get(fcIdx) != null
                        && !nextFnRespIds.get(fcIdx).isEmpty()) {
                    tcId = nextFnRespIds.get(fcIdx);
                } else {
                    tcId = "call_" + UUID.randomUUID().toString().substring(0, 8);
                }
                fcIdx++;
                toolCalls.add(UnifiedToolCall.builder()
                    .id(tcId)
                    .type("function")
                    .function(UnifiedFunctionCall.builder()
                        .name(fnName)
                        .arguments(argsNode)
                        .build())
                    .build());
            }
            if (part.functionResponse().isPresent()) {
                FunctionResponse fr = part.functionResponse().get();
                // 优先用预生成的 ID（来自 toUnifiedRequest 的 UUID 兜底），回退到 fr.id()
                int frIdx = frIds.size();
                String id = (generatedFnRespIds != null && frIdx < generatedFnRespIds.size())
                    ? generatedFnRespIds.get(frIdx)
                    : fr.id().orElse(null);
                frIds.add(id);
                frNames.add(fr.name().orElse(null));
                if (fr.response().isPresent()) {
                    try {
                        frContents.add(mapper.writeValueAsString(fr.response().get()));
                    } catch (Exception e) {
                        frContents.add(fr.response().get().toString());
                    }
                } else {
                    frContents.add(null);
                }
            }
        }

        String text = !textBuf.isEmpty() ? textBuf.toString() : null;
        String reasoningContent = !thoughtBuf.isEmpty() ? thoughtBuf.toString() : null;
        List<UnifiedToolCall> finalToolCalls = !toolCalls.isEmpty() ? toolCalls : null;

        // 有图片时,文本也并入 parts(放开头,保持 text+image 顺序),下游走多模态分支
        if (!irParts.isEmpty() && text != null) {
            irParts.add(0, new UnifiedPart.TextPart(text));
        }
        List<UnifiedPart> finalParts = irParts.isEmpty() ? null : irParts;

        List<UnifiedMessage> messages = new ArrayList<>();

        // ---- 第二遍：按角色构建消息 ----
        boolean hasFunctionResponses = !frIds.isEmpty();

        if (hasFunctionResponses) {
            // 每个 functionResponse → 独立 TOOL 消息
            for (int i = 0; i < frIds.size(); i++) {
                messages.add(UnifiedMessage.builder()
                    .role(UnifiedMessage.Role.TOOL)
                    .content(frContents.get(i))
                    .toolCallId(frIds.get(i))
                    .name(frNames.get(i))
                    .build());
            }
            // 若有附带文本，作为单独的 USER 消息
            if (text != null) {
                messages.add(UnifiedMessage.builder()
                    .role(UnifiedMessage.Role.USER)
                    .content(text)
                    .parts(finalParts)
                    .build());
            }
        } else {
            UnifiedMessage.Role irRole = switch (geminiRole) {
                case "user" -> UnifiedMessage.Role.USER;
                case "model" -> UnifiedMessage.Role.ASSISTANT;
                case "function" -> UnifiedMessage.Role.TOOL;
                default -> UnifiedMessage.Role.USER;
            };
            messages.add(UnifiedMessage.builder()
                .role(irRole)
                .content(text)
                .toolCalls(finalToolCalls)
                .reasoningContent(reasoningContent)
                .parts(finalParts)
                .build());
        }

        return messages;
    }

    // 把 Gemini Blob(inlineData)转为统一 ImagePart(data URL 格式)
    // 与 AnthropicProtocolAdapter.convertImageToDataUrl 保持一致,下游 OpenAiRequestConverter 读 url/detail
    private UnifiedPart convertInlineDataToImagePart(Blob blob) {
        String mimeType = blob.mimeType().orElse("image/png");
        byte[] bytes = blob.data().orElse(new byte[0]);
        String base64 = Base64.getEncoder().encodeToString(bytes);
        ObjectNode imageData = mapper.createObjectNode();
        imageData.put("url", "data:" + mimeType + ";base64," + base64);
        imageData.putNull("detail");
        return new UnifiedPart.ImagePart(imageData);
    }

    // 出站:把统一 ImagePart(data URL)转回 Gemini Blob(inlineData)
    private Part convertImagePartToInlineData(UnifiedPart.ImagePart img) {
        try {
            String url = img.imageData().path("url").asText("");
            if (!url.startsWith("data:")) return null;
            String[] segs = url.substring(5).split(",", 2);
            if (segs.length != 2) return null;
            String mimeType = segs[0].split(";")[0];
            byte[] bytes = Base64.getDecoder().decode(segs[1]);
            return Part.builder().inlineData(Blob.builder()
                .mimeType(mimeType)
                .data(bytes)
                .build()).build();
        } catch (Exception e) {
            return null;
        }
    }

    private GenerateContentResponse mapToGeminiResponse(UnifiedChatResponse uResp, GeminiRequestContext ctx) {
        List<Candidate> candidates = uResp.choices().stream()
            .map(c -> {
                // 构建 parts：thought(来自reasoningContent) → functionCall → text 兜底
                List<Part> parts = new ArrayList<>();
                UnifiedMessage msg = c.message();

                // 1) 处理结构化 parts（thought/image/fileData/executableCode/codeExecutionResult）
                if (msg != null && msg.parts() != null) {
                    for (UnifiedPart up : msg.parts()) {
                        if (up instanceof UnifiedPart.ThinkingPart t && t.thinking() != null) {
                            parts.add(Part.builder().thought(true).text(t.thinking()).build());
                        } else if (up instanceof UnifiedPart.ImagePart img) {
                            Part inlinePart = convertImagePartToInlineData(img);
                            if (inlinePart != null) parts.add(inlinePart);
                        } else if (up instanceof UnifiedPart.FileDataPart fd) {
                            parts.add(Part.builder().fileData(FileData.builder()
                                .fileUri(fd.fileUri())
                                .mimeType(fd.mimeType())
                                .build()).build());
                        } else if (up instanceof UnifiedPart.ExecutableCodePart ec) {
                            parts.add(Part.builder().executableCode(ExecutableCode.builder()
                                .language(ec.language())
                                .code(ec.code())
                                .build()).build());
                        } else if (up instanceof UnifiedPart.CodeExecutionResultPart cer) {
                            parts.add(Part.builder().codeExecutionResult(CodeExecutionResult.builder()
                                .outcome(cer.outcome())
                                .output(cer.output())
                                .build()).build());
                        }
                    }
                }

                // 3) 处理 toolCalls → functionCall parts（message 或 delta）
                List<UnifiedToolCall> toolCalls = msg != null ? msg.toolCalls()
                    : c.delta() != null ? c.delta().toolCalls() : null;
                if (toolCalls != null && !toolCalls.isEmpty()) {
                    for (UnifiedToolCall tc : toolCalls) {
                        if (tc.function() != null) {
                            // 流式 content_block_start 会发送 args=null 的中间态 tool_use，
                            // 此时跳过不生成 functionCall，等 message_stop 时 args 完整再发送，
                            // 防止 Gemini CLI 收到空参数 {} 后立即执行导致报错
                            if (tc.function().arguments() == null) {
                                log.debug("Gemini 出站跳过空参数 tool_use: name={} id={}",
                                    tc.function().name(), tc.id());
                                continue;
                            }
                            Map<String, Object> args = new HashMap<>();
                            try {
                                args = mapper.readValue(
                                    mapper.writeValueAsString(tc.function().arguments()),
                                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
                                log.debug("Gemini 出站 functionCall: name={} args={}", tc.function().name(), args);
                            } catch (Exception e) {
                                log.warn("FunctionCall args 解析失败, 降级为空对象: name={} args={}",
                                    tc.function().name(), tc.function().arguments(), e);
                            }
                            var fcBuilder = FunctionCall.builder()
                                .name(tc.function().name())
                                .args(args);
                            if (tc.id() != null) {
                                fcBuilder.id(tc.id());
                            }
                            parts.add(Part.builder()
                                .functionCall(fcBuilder.build())
                                .build());
                        }
                    }
                }

                // 3.5) 真流式:按 index 累积 toolCallArgumentDeltas,finishReason 时组装完整 functionCall
                //     多 toolCall 支持通过 ctx.toolCallAccs() 的 Map<Integer, ToolCallAcc> 实现
                if (ctx != null && c.delta() != null) {
                    if (c.delta().toolCalls() != null) {
                        for (UnifiedToolCall tc : c.delta().toolCalls()) {
                            int tcIndex = tc.index() != null ? tc.index() : 0;
                            GeminiRequestContext.ToolCallAcc acc = ctx.toolCallAccs()
                                .computeIfAbsent(tcIndex, k -> new GeminiRequestContext.ToolCallAcc());
                            if (tc.id() != null) acc.id = tc.id();
                            if (tc.function() != null && tc.function().name() != null) {
                                acc.fnName = tc.function().name();
                            }
                        }
                    }
                    // 新(按 index 累积):每个 arguments 增量严格归属对应 acc
                    if (c.delta().toolCallArgumentDeltas() != null) {
                        for (IndexedArgumentDelta d : c.delta().toolCallArgumentDeltas()) {
                            if (d.index() == null) {
                                log.warn("Gemini 出站 IndexedArgumentDelta index 为 null,跳过: partial={}",
                                    d.partialJson());
                                continue;
                            }
                            GeminiRequestContext.ToolCallAcc acc = ctx.toolCallAccs()
                                .computeIfAbsent(d.index(), k -> new GeminiRequestContext.ToolCallAcc());
                            acc.argsBuilder.append(d.partialJson());
                        }
                    }
                }
                if (ctx != null && c.finishReason() != null) {
                    for (var entry : ctx.toolCallAccs().entrySet()) {
                        GeminiRequestContext.ToolCallAcc acc = entry.getValue();
                        if (acc.fnName != null && acc.argsBuilder.length() > 0) {
                            Map<String, Object> args = new HashMap<>();
                            try {
                                args = mapper.readValue(acc.argsBuilder.toString(),
                                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
                            } catch (Exception e) {
                                log.warn("Gemini 出站累积 args 解析失败: name={} args={}",
                                    acc.fnName, acc.argsBuilder, e);
                            }
                            var fcBuilder = FunctionCall.builder()
                                .name(acc.fnName)
                                .args(args);
                            if (acc.id != null) fcBuilder.id(acc.id);
                            parts.add(Part.builder().functionCall(fcBuilder.build()).build());
                        }
                        acc.reset();
                    }
                }

                // 4) reasoningContent（Anthropic thinking / OpenAI reasoning）→ thought part
                //    放在 toolCalls 之后，避免打乱已有测试对 parts[0] 的断言
                String reasoningContent = msg != null && msg.reasoningContent() != null
                    ? msg.reasoningContent()
                    : c.delta() != null && c.delta().reasoningContent() != null
                    ? c.delta().reasoningContent() : null;
                if (reasoningContent != null && !reasoningContent.isEmpty()) {
                    parts.add(Part.builder().thought(true).text(reasoningContent).build());
                }

                // 5) 兜底：纯文本
                if (parts.isEmpty()) {
                    String text = msg != null && msg.content() != null
                        ? msg.content()
                        : c.delta() != null && c.delta().content() != null
                        ? c.delta().content() : "";
                    parts.add(Part.builder().text(text).build());
                }
                var builder = Candidate.builder()
                    .index(c.index())
                    .content(Content.builder()
                        .role("model")
                        .parts(parts)
                        .build());
                if (c.finishReason() != null) {
                    builder.finishReason(mapFinishReason(c.finishReason()));
                }
                return builder.build();
            })
            .toList();

        GenerateContentResponseUsageMetadata usage = uResp.usage() != null
            ? GenerateContentResponseUsageMetadata.builder()
                .promptTokenCount(uResp.usage().promptTokens())
                .candidatesTokenCount(uResp.usage().completionTokens())
                .totalTokenCount(uResp.usage().totalTokens())
                .build()
            : null;

        var respBuilder = GenerateContentResponse.builder()
            .modelVersion(uResp.model())
            .candidates(candidates);
        if (usage != null) {
            respBuilder.usageMetadata(usage);
        }
        return respBuilder.build();
    }

    private FinishReason mapFinishReason(String reason) {
        if (reason == null) return null;
        // spec 第 5 节:如果是 Gemini 合法值直接用(同协议零损失)
        try {
            return new FinishReason(FinishReason.Known.valueOf(reason));
        } catch (IllegalArgumentException e) {
            // 跨协议:按语义映射
            return switch (reason) {
                case "stop" -> new FinishReason(FinishReason.Known.STOP);
                case "length" -> new FinishReason(FinishReason.Known.MAX_TOKENS);
                case "content_filter" -> new FinishReason(FinishReason.Known.SAFETY);
                case "end_turn", "stop_sequence", "tool_use" ->
                    new FinishReason(FinishReason.Known.STOP);
                default -> new FinishReason(FinishReason.Known.STOP);
            };
        }
    }

    // ===== Reasoning 缓存辅助方法 =====

    /** 从 headers 和 rawBody 提取会话键 */
    private String extractSessionKey(Map<String, String> headers, JsonNode root) {
        // 取 API key 后4位
        String apiKeySuffix = "anon";
        if (headers != null) {
            String apiKey = headers.getOrDefault("x-goog-api-key",
                headers.getOrDefault("authorization", ""));
            if (apiKey != null && apiKey.length() >= 4) {
                apiKeySuffix = apiKey.substring(apiKey.length() - 4);
            }
        }
        // 取首条 user 消息的 hash
        String firstUserHash = "";
        if (root != null && root.has("contents")) {
            for (JsonNode content : root.get("contents")) {
                if ("user".equals(content.path("role").asText("")) && content.has("parts")) {
                    JsonNode firstPart = content.get("parts").get(0);
                    if (firstPart != null && firstPart.has("text")) {
                        firstUserHash = shortSha256(firstPart.get("text").asText());
                    }
                    break;
                }
            }
        }
        return apiKeySuffix + ":" + firstUserHash;
    }

    /** 将出站 chunk 中的 reasoningContent 写入缓存 */
    private void rememberReasoning(UnifiedChatResponse chunk, String sessionKey) {
        if (sessionKey == null || chunk.choices() == null) return;
        for (var choice : chunk.choices()) {
            String rc = null;
            if (choice.message() != null) rc = choice.message().reasoningContent();
            else if (choice.delta() != null) rc = choice.delta().reasoningContent();
            if (rc != null && !rc.isEmpty()) {
                reasoningStore.remember(sessionKey, rc);
            }
        }
    }

    /** 公开的 sessionKey 提取方法，供 ProxyController 调用 */
    public String extractSessionKeyForController(Map<String, String> headers, byte[] rawBody) {
        try {
            JsonNode root = mapper.readTree(rawBody);
            return extractSessionKey(headers, root);
        } catch (Exception e) {
            return "anon:";
        }
    }

    private static String shortSha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 4; i++) {
                sb.append(String.format("%02x", hash[i]));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(input.hashCode());
        }
    }
}
