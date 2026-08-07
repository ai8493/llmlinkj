package com.ai8493.llmproxy.adapter.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.ai8493.llmproxy.adapter.ProtocolAdapter;
import com.ai8493.llmproxy.exception.BackendApiException;
import com.ai8493.llmproxy.exception.TransformException;
import com.ai8493.llmproxy.model.*;
import com.ai8493.llmproxy.model.extensions.OpenAiExtensions;
import com.openai.core.JsonValue;
import com.openai.models.FunctionDefinition;
import com.openai.models.FunctionParameters;
import com.openai.models.chat.completions.*;
import com.openai.models.completions.CompletionUsage;
import com.openai.models.completions.CompletionUsage.PromptTokensDetails;
import com.openai.models.completions.CompletionUsage.CompletionTokensDetails;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Component
public class OpenAiProtocolAdapter implements ProtocolAdapter {

    private final ObjectMapper mapper = new ObjectMapper()
        .registerModule(new Jdk8Module())
        .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Override
    public String protocolName() { return "openai"; }

    @Override
    public UnifiedChatRequest toUnifiedRequest(byte[] rawRequest, Map<String, String> headers) {
        try {
            JsonNode root = mapper.readTree(rawRequest);
            boolean stream = root.has("stream") && root.get("stream").asBoolean(false);

            String model = root.has("model") ? root.get("model").asText() : "";

            List<UnifiedMessage> messages = parseMessages(root.get("messages"));
            messages = normalizeToolCallMessageOrder(messages);
            if (messages.isEmpty()) {
                throw new IllegalArgumentException("messages 不能为空");
            }

            UnifiedGenerationConfig config = parseConfig(root);

            List<UnifiedTool> tools = parseTools(root.get("tools"));

            UnifiedToolChoice toolChoice = parseToolChoice(root.get("tool_choice"));

            // 从 raw JSON 读 OpenAI 专属字段
            OpenAiExtensions openaiExt = null;
            if (root.has("logprobs") || root.has("top_logprobs") || root.has("seed")
                    || root.has("n") || root.has("response_format") || root.has("logit_bias")
                    || root.has("metadata") || root.has("store") || root.has("audio")
                    || root.has("modalities") || root.has("prediction")
                    || root.has("web_search_options")) {
                OpenAiExtensions.Builder extBuilder = OpenAiExtensions.builder();
                if (root.has("logprobs")) {
                    extBuilder.logprobs(root.get("logprobs").asBoolean());
                }
                if (root.has("top_logprobs")) {
                    extBuilder.topLogprobs(root.get("top_logprobs").asInt());
                }
                if (root.has("seed")) {
                    extBuilder.seed(root.get("seed").asLong());
                }
                if (root.has("n")) {
                    extBuilder.n(root.get("n").asInt());
                }
                if (root.has("response_format")) {
                    extBuilder.responseFormat(root.get("response_format"));
                }
                if (root.has("logit_bias")) {
                    extBuilder.logitBias(root.get("logit_bias"));
                }
                if (root.has("metadata")) {
                    extBuilder.metadata(root.get("metadata"));
                }
                if (root.has("store")) {
                    extBuilder.store(root.get("store").asBoolean());
                }
                if (root.has("audio")) {
                    extBuilder.audio(root.get("audio"));
                }
                if (root.has("modalities") && root.get("modalities").isArray()) {
                    List<String> modalities = new ArrayList<>();
                    for (JsonNode m : root.get("modalities")) modalities.add(m.asText());
                    extBuilder.modalities(modalities);
                }
                if (root.has("prediction")) {
                    extBuilder.prediction(root.get("prediction"));
                }
                if (root.has("web_search_options")) {
                    extBuilder.webSearchOptions(root.get("web_search_options"));
                }
                openaiExt = extBuilder.build();
            }

            return UnifiedChatRequest.builder()
                .model(model)
                .messages(messages)
                .config(config)
                .tools(tools)
                .toolChoice(toolChoice)
                .stream(stream)
                .openai(openaiExt)
                .build();
        } catch (Exception e) {
            throw new IllegalArgumentException("无法解析 OpenAI 请求: " + e.getMessage(), e);
        }
    }

    // ---- 入站 JSON 解析辅助方法 ----

    /** 解析 messages 数组 → List<UnifiedMessage> */
    private List<UnifiedMessage> parseMessages(JsonNode messagesNode) {
        if (messagesNode == null || !messagesNode.isArray()) return List.of();
        List<UnifiedMessage> result = new ArrayList<>();
        for (JsonNode msg : messagesNode) {
            String role = msg.has("role") ? msg.get("role").asText() : "user";
            JsonNode contentNode = msg.get("content");
            String content = null;
            List<UnifiedPart> parts = null;
            if (contentNode != null && !contentNode.isNull()) {
                if (contentNode.isTextual()) {
                    content = contentNode.asText();
                } else if (contentNode.isArray()) {
                    parts = parseContentParts(contentNode);
                    // 纯文本 parts 回填 content,方便下游走单文本分支
                    if (parts != null && parts.size() == 1
                            && parts.get(0) instanceof UnifiedPart.TextPart t) {
                        content = t.text();
                        parts = null;
                    }
                }
            }
            String name = msg.has("name") ? msg.get("name").asText() : null;
            String toolCallId = msg.has("tool_call_id") ? msg.get("tool_call_id").asText() : null;

            List<UnifiedToolCall> toolCalls = null;
            if (msg.has("tool_calls") && !msg.get("tool_calls").isNull()) {
                toolCalls = parseMessageToolCalls(msg.get("tool_calls"));
            }

            result.add(UnifiedMessage.builder()
                .role(switch (role) {
                    case "system", "developer" -> UnifiedMessage.Role.SYSTEM;
                    case "user" -> UnifiedMessage.Role.USER;
                    case "assistant" -> UnifiedMessage.Role.ASSISTANT;
                    case "tool" -> UnifiedMessage.Role.TOOL;
                    default -> UnifiedMessage.Role.USER;
                })
                .content(content)
                .parts(parts)
                .toolCalls(toolCalls)
                .toolCallId(toolCallId)
                .name(name)
                .build());
        }
        return result;
    }

    /** 解析 content 数组 -> List<UnifiedPart>,null/非数组返回 null */
    private List<UnifiedPart> parseContentParts(JsonNode contentNode) {
        if (contentNode == null || !contentNode.isArray()) return null;
        List<UnifiedPart> result = new ArrayList<>();
        for (JsonNode part : contentNode) {
            String type = part.has("type") ? part.get("type").asText() : "text";
            switch (type) {
                case "text" -> {
                    if (part.has("text")) {
                        result.add(new UnifiedPart.TextPart(part.get("text").asText()));
                    }
                }
                case "image_url" -> {
                    if (part.has("image_url")) {
                        JsonNode img = part.get("image_url");
                        var imageData = mapper.createObjectNode();
                        imageData.put("url", img.path("url").asText(""));
                        imageData.put("detail", img.has("detail") ? img.get("detail").asText("") : null);
                        result.add(new UnifiedPart.ImagePart(imageData));
                    }
                }
                default -> {
                    // 未知 part 类型保留为 TextPart(text 字段兜底)
                    if (part.has("text")) {
                        result.add(new UnifiedPart.TextPart(part.get("text").asText()));
                    }
                }
            }
        }
        return result.isEmpty() ? null : result;
    }

    /** 解析 assistant 消息中的 tool_calls 数组 */
    private List<UnifiedToolCall> parseMessageToolCalls(JsonNode toolCallsNode) {
        if (toolCallsNode == null || !toolCallsNode.isArray()) return null;
        List<UnifiedToolCall> result = new ArrayList<>();
        for (JsonNode tc : toolCallsNode) {
            String id = tc.has("id") ? tc.get("id").asText() : null;
            String type = tc.has("type") ? tc.get("type").asText() : "function";
            JsonNode function = tc.get("function");
            String fnName = null;
            JsonNode fnArgs = null;
            if (function != null) {
                fnName = function.has("name") ? function.get("name").asText() : null;
                if (function.has("arguments")) {
                    JsonNode argsNode = function.get("arguments");
                    if (argsNode.isTextual()) {
                        try { fnArgs = mapper.readTree(argsNode.asText()); }
                        catch (Exception __) { fnArgs = mapper.createObjectNode(); }
                    } else {
                        fnArgs = argsNode;
                    }
                }
            }
            result.add(UnifiedToolCall.builder()
                .id(id)
                .type(type)
                .function(UnifiedFunctionCall.builder()
                    .name(fnName)
                    .arguments(fnArgs)
                    .build())
                .build());
        }
        return result.isEmpty() ? null : result;
    }

    /** 解析 tools 定义 */
    private List<UnifiedTool> parseTools(JsonNode toolsNode) {
        if (toolsNode == null || !toolsNode.isArray()) return null;
        List<UnifiedTool> result = new ArrayList<>();
        for (JsonNode t : toolsNode) {
            String type = t.has("type") ? t.get("type").asText() : "function";
            JsonNode fnNode = t.get("function");
            if (fnNode != null) {
                String fnName = fnNode.has("name") ? fnNode.get("name").asText() : null;
                String fnDesc = fnNode.has("description") ? fnNode.get("description").asText() : null;
                JsonNode fnParams = fnNode.has("parameters") ? fnNode.get("parameters") : null;
                result.add(UnifiedTool.builder()
                    .type(type)
                    .function(UnifiedFunctionDefinition.builder()
                        .name(fnName)
                        .description(fnDesc)
                        .parameters(fnParams)
                        .build())
                    .build());
            }
        }
        return result.isEmpty() ? null : result;
    }

    /** 解析 tool_choice */
    private UnifiedToolChoice parseToolChoice(JsonNode tcNode) {
        if (tcNode == null || tcNode.isNull()) return null;
        if (tcNode.isTextual()) {
            return switch (tcNode.asText()) {
                case "none" -> UnifiedToolChoice.None.builder().build();
                case "required" -> UnifiedToolChoice.Any.builder().build();
                default -> UnifiedToolChoice.Auto.builder().build();
            };
        }
        if (tcNode.isObject() && tcNode.has("function")) {
            JsonNode fn = tcNode.get("function");
            if (fn.has("name")) {
                return UnifiedToolChoice.Required.builder()
                    .functionName(fn.get("name").asText())
                    .build();
            }
        }
        return UnifiedToolChoice.Auto.builder().build();
    }

    /** 解析 generation config */
    private UnifiedGenerationConfig parseConfig(JsonNode root) {
        Double temperature = root.has("temperature") ? root.get("temperature").asDouble() : null;
        Double topP = root.has("top_p") ? root.get("top_p").asDouble() : null;
        Integer maxTokens = root.has("max_tokens") ? root.get("max_tokens").asInt() : null;
        Integer maxCompletionTokens = root.has("max_completion_tokens")
            ? root.get("max_completion_tokens").asInt() : null;
        String reasoningEffort = root.has("reasoning_effort")
            ? root.get("reasoning_effort").asText() : null;
        Boolean parallelToolCalls = root.has("parallel_tool_calls")
            ? root.get("parallel_tool_calls").asBoolean() : null;
        Double presencePenalty = root.has("presence_penalty")
            ? root.get("presence_penalty").asDouble() : null;
        Double frequencyPenalty = root.has("frequency_penalty")
            ? root.get("frequency_penalty").asDouble() : null;
        Long seed = root.has("seed") ? root.get("seed").asLong() : null;
        List<String> stop = null;
        if (root.has("stop")) {
            JsonNode stopNode = root.get("stop");
            if (stopNode.isTextual()) {
                stop = List.of(stopNode.asText());
            } else if (stopNode.isArray()) {
                stop = new ArrayList<>();
                for (JsonNode s : stopNode) stop.add(s.asText());
            }
        }
        return UnifiedGenerationConfig.builder()
            .temperature(temperature)
            .topP(topP)
            .maxOutputTokens(maxTokens)
            .maxCompletionTokens(maxCompletionTokens)
            .reasoningEffort(reasoningEffort)
            .parallelToolCalls(parallelToolCalls)
            .presencePenalty(presencePenalty)
            .frequencyPenalty(frequencyPenalty)
            .seed(seed)
            .stopSequences(stop)
            .build();
    }

    /** Type-safe entry point for Controller */
    public UnifiedChatRequest toUnifiedRequest(ChatCompletionCreateParams req, boolean stream) {
        List<UnifiedMessage> messages = normalizeToolCallMessageOrder(
            req.messages().stream()
                .map(this::mapMessage)
                .toList()
        );

        UnifiedGenerationConfig config = UnifiedGenerationConfig.builder()
                .temperature(req.temperature().orElse(null))
                .topP(req.topP().orElse(null))
                .maxOutputTokens(req.maxTokens().map(Long::intValue).orElse(null))
                .stopSequences(req.stop().map(this::mapStop).orElse(null))
                .build();

        List<UnifiedTool> tools = null;
        if (req.tools().isPresent() && !req.tools().get().isEmpty()) {
            tools = req.tools().get().stream()
                    .map(this::mapTool)
                    .filter(t -> t != null)
                    .toList();
            if (tools.isEmpty()) tools = null;
        }

        UnifiedToolChoice toolChoice = null;
        if (req.toolChoice().isPresent()) {
            toolChoice = mapToolChoice(req.toolChoice().get());
        }

        return UnifiedChatRequest.builder()
                .model(req.model().toString())
                .messages(messages)
                .config(config)
                .tools(tools)
                .toolChoice(toolChoice)
                .stream(stream)
                .build();
    }

    @Override
    public byte[] fromUnifiedResponse(UnifiedChatResponse uResp) {
        try {
            ChatCompletion resp = mapToChatCompletion(uResp);
            return mapper.writeValueAsBytes(resp);
        } catch (Exception e) {
            throw new TransformException("序列化 OpenAI 响应失败", e);
        }
    }

    @Override
    public String fromUnifiedStreamChunk(UnifiedChatResponse chunk) {
        try {
            ChatCompletionChunk c = mapToChatCompletionChunk(chunk);
            return mapper.writeValueAsString(c);
        } catch (Exception e) {
            throw new TransformException("序列化流块失败: " + e.getMessage(), e);
        }
    }

    /**
     * 流式响应中途出错时，构造一条 OpenAI 风格的 error JSON 作为 SSE data 事件吐给客户端。
     * 用于 SSE 响应已 committed 后无法走 @ExceptionHandler 的场景。
     */
    public String errorStreamEvent(Throwable e) {
        int status = (e instanceof BackendApiException b) ? b.getStatusCode() : 502;
        String type = switch (status) {
            case 400 -> "invalid_request_error";
            case 401, 403 -> "authentication_error";
            case 429 -> "rate_limit_error";
            default -> "server_error";
        };
        try {
            var err = mapper.createObjectNode();
            var inner = mapper.createObjectNode();
            inner.put("message", e.getMessage() != null ? e.getMessage() : "上游后端调用失败");
            inner.put("type", type);
            inner.put("code", status);
            err.set("error", inner);
            return mapper.writeValueAsString(err);
        } catch (Exception ex) {
            return "{\"error\":{\"message\":\"上游后端调用失败\",\"type\":\"server_error\",\"code\":502}}";
        }
    }

    // ========================================================================
    // 请求侧映射：OpenAI SDK → IR
    // ========================================================================

    /** 将 OpenAI 的 stop 参数（string 或 string[]）转为 IR 的 List<String> */
    private List<String> mapStop(ChatCompletionCreateParams.Stop stop) {
        if (stop.isString()) {
            return List.of(stop.asString());
        }
        return stop.asStrings();
    }

    /** 将 ChatCompletionMessageParam（6 种变体）映射为 UnifiedMessage */
    private UnifiedMessage mapMessage(ChatCompletionMessageParam param) {
        if (param.isSystem()) {
            var sys = param.asSystem();
            return UnifiedMessage.builder()
                    .role(UnifiedMessage.Role.SYSTEM)
                    .content(sys.content().isText() ? sys.content().asText() : null)
                    .name(sys.name().orElse(null))
                    .build();
        } else if (param.isUser()) {
            var user = param.asUser();
            return UnifiedMessage.builder()
                    .role(UnifiedMessage.Role.USER)
                    .content(user.content().isText() ? user.content().asText() : null)
                    .name(user.name().orElse(null))
                    .build();
        } else if (param.isAssistant()) {
            var asst = param.asAssistant();
            List<UnifiedToolCall> toolCalls = null;
            if (asst.toolCalls().isPresent()) {
                toolCalls = asst.toolCalls().get().stream()
                        .map(this::mapToolCall)
                        .toList();
            }
            String content = asst.content()
                    .map(c -> c.isText() ? c.asText() : null)
                    .orElse(null);
            return UnifiedMessage.builder()
                    .role(UnifiedMessage.Role.ASSISTANT)
                    .content(content)
                    .toolCalls(toolCalls)
                    .name(asst.name().orElse(null))
                    .build();
        } else if (param.isTool()) {
            var tool = param.asTool();
            return UnifiedMessage.builder()
                    .role(UnifiedMessage.Role.TOOL)
                    .content(tool.content().isText() ? tool.content().asText() : null)
                    .toolCallId(tool.toolCallId())
                    .build();
        } else if (param.isDeveloper()) {
            // developer 消息降级为 SYSTEM(OpenAI developer 与 system 语义等价,都是系统指令)
            var dev = param.asDeveloper();
            return UnifiedMessage.builder()
                    .role(UnifiedMessage.Role.SYSTEM)
                    .content(dev.content().isText() ? dev.content().asText() : null)
                    .build();
        } else {
            // function（已弃用）— 降级为 user
            return UnifiedMessage.builder()
                    .role(UnifiedMessage.Role.USER)
                    .build();
        }
    }

    /** 将 ChatCompletionMessageToolCall 映射为 UnifiedToolCall */
    private UnifiedToolCall mapToolCall(ChatCompletionMessageToolCall tc) {
        if (tc.isFunction()) {
            var fn = tc.asFunction();
            JsonNode argsNode;
            try {
                argsNode = mapper.readTree(fn.function().arguments());
            } catch (Exception e) {
                argsNode = mapper.createObjectNode();
            }
            return UnifiedToolCall.builder()
                    .id(fn.id())
                    .type("function")
                    .function(UnifiedFunctionCall.builder()
                            .name(fn.function().name())
                            .arguments(argsNode)
                            .build())
                    .build();
        }
        // custom tool call — 降级为空 tool call
        return UnifiedToolCall.builder()
                .id("")
                .type("custom")
                .build();
    }

    /** 将 ChatCompletionTool 映射为 UnifiedTool */
    private UnifiedTool mapTool(ChatCompletionTool tool) {
        if (tool.isFunction()) {
            var fn = tool.asFunction();
            return UnifiedTool.builder()
                    .type("function")
                    .function(mapFunctionDefinition(fn.function()))
                    .build();
        }
        // custom tool — Phase 1 不支持
        return null;
    }

    /** 将 FunctionDefinition 映射为 UnifiedFunctionDefinition */
    private UnifiedFunctionDefinition mapFunctionDefinition(FunctionDefinition fn) {
        JsonNode paramsNode = null;
        if (fn.parameters().isPresent()) {
            paramsNode = functionParametersToJsonNode(fn.parameters().get());
        }
        return UnifiedFunctionDefinition.builder()
                .name(fn.name())
                .description(fn.description().orElse(null))
                .parameters(paramsNode)
                .build();
    }

    /** 将 SDK FunctionParameters 转为 Jackson JsonNode */
    private JsonNode functionParametersToJsonNode(FunctionParameters params) {
        try {
            return mapper.valueToTree(params);
        } catch (Exception e) {
            // 兜底：手动构建 ObjectNode
            var obj = mapper.createObjectNode();
            params._additionalProperties().forEach((key, value) -> {
                try {
                    JsonNode converted = value.convert(JsonNode.class);
                    obj.set(key, converted);
                } catch (Exception ignored) {
                    obj.put(key, value.toString());
                }
            });
            return obj;
        }
    }

    /** 将 OpenAI tool_choice 映射为 UnifiedToolChoice */
    private UnifiedToolChoice mapToolChoice(ChatCompletionToolChoiceOption tc) {
        if (tc.isAuto()) {
            var auto = tc.asAuto();
            if (auto == ChatCompletionToolChoiceOption.Auto.NONE) {
                return UnifiedToolChoice.None.builder().build();
            }
            // AUTO 或 REQUIRED → IR 统一为 Auto
            return UnifiedToolChoice.Auto.builder().build();
        }
        if (tc.isAllowedToolChoice()) {
            // "required"（不指定具体函数）→ Auto
            return UnifiedToolChoice.Auto.builder().build();
        }
        if (tc.isNamedToolChoice()) {
            var named = tc.asNamedToolChoice();
            return UnifiedToolChoice.Required.builder()
                .functionName(named.function().name())
                .build();
        }
        return UnifiedToolChoice.Auto.builder().build();
    }

    // ========================================================================
    // 响应侧映射：IR → OpenAI SDK
    // ========================================================================

    /** 将 UnifiedChatResponse 转为 ChatCompletion */
    private ChatCompletion mapToChatCompletion(UnifiedChatResponse uResp) {
        List<ChatCompletion.Choice> choices = uResp.choices().stream()
                .map(c -> {
                    var choiceBuilder = ChatCompletion.Choice.builder()
                        .index(c.index())
                        .message(mapToChatCompletionMessage(c.message()))
                        .finishReason(mapFinishReason(c.finishReason()));
                    if (c.logprobs() != null) {
                        try {
                            var lp = mapper.treeToValue(c.logprobs(),
                                ChatCompletion.Choice.Logprobs.class);
                            choiceBuilder.logprobs(Optional.of(lp));
                        } catch (Exception e) {
                            choiceBuilder.logprobs(Optional.empty());
                        }
                    } else {
                        choiceBuilder.logprobs(Optional.empty());
                    }
                    return choiceBuilder.build();
                })
                .toList();

        CompletionUsage usage = null;
        if (uResp.usage() != null) {
            var usageBuilder = CompletionUsage.builder()
                    .promptTokens(uResp.usage().promptTokens())
                    .completionTokens(uResp.usage().completionTokens())
                    .totalTokens(uResp.usage().totalTokens());
            if (uResp.usage().cachedTokens() > 0) {
                usageBuilder.promptTokensDetails(PromptTokensDetails.builder()
                    .cachedTokens(uResp.usage().cachedTokens())
                    .build());
            }
            if (uResp.usage().reasoningTokens() > 0) {
                usageBuilder.completionTokensDetails(CompletionTokensDetails.builder()
                    .reasoningTokens(uResp.usage().reasoningTokens())
                    .build());
            }
            usage = usageBuilder.build();
        }

        ChatCompletion.Builder builder = ChatCompletion.builder()
                .id(uResp.id() != null ? uResp.id() : "chatcmpl-" + UUID.randomUUID())
                .model(uResp.model())
                .object_(JsonValue.from("chat.completion"))
                .created(uResp.created() != 0 ? uResp.created() : Instant.now().getEpochSecond())
                .choices(choices);

        if (usage != null) {
            builder.usage(usage);
        }
        if (uResp.systemFingerprint() != null) {
            builder.systemFingerprint(uResp.systemFingerprint());
        }

        return builder.build();
    }

    /** 将 UnifiedMessage 转为 ChatCompletionMessage（响应体中的 message） */
    private ChatCompletionMessage mapToChatCompletionMessage(UnifiedMessage msg) {
        if (msg == null) return null;

        ChatCompletionMessage.Builder builder = ChatCompletionMessage.builder();

        // role
        builder.role(JsonValue.from("assistant"));

        // refusal(SDK 必填字段,从 IR 读,空时设 empty)
        builder.refusal(msg.refusal() != null ? Optional.of(msg.refusal()) : Optional.empty());

        // content
        if (msg.content() != null) {
            builder.content(Optional.of(msg.content()));
        } else {
            builder.content(Optional.empty());
        }

        // tool_calls
        if (msg.toolCalls() != null && !msg.toolCalls().isEmpty()) {
            for (UnifiedToolCall tc : msg.toolCalls()) {
                if (tc.type() != null && tc.type().equals("function") && tc.function() != null) {
                    String argsStr = tc.function().arguments() != null
                            ? tc.function().arguments().toString()
                            : "{}";
                    builder.addToolCall(ChatCompletionMessageFunctionToolCall.builder()
                            .id(tc.id() != null ? tc.id() : "")
                            .function(ChatCompletionMessageFunctionToolCall.Function.builder()
                                    .name(tc.function().name())
                                    .arguments(argsStr)
                                    .build())
                            .build());
                }
            }
        }

        return builder.build();
    }

    /** 将 IR finishReason(String)转为 SDK FinishReason,null 时默认为 STOP。
     * spec 第 5 节:同协议合法值直接用,跨协议按语义映射。 */
    private ChatCompletion.Choice.FinishReason mapFinishReason(String reason) {
        if (reason == null) return ChatCompletion.Choice.FinishReason.STOP;
        // OpenAI 合法值直接用(同协议零损失)
        switch (reason) {
            case "stop", "length", "tool_calls", "content_filter", "function_call":
                return ChatCompletion.Choice.FinishReason.of(reason);
        }
        // 跨协议:按语义映射
        return switch (reason) {
            case "STOP", "end_turn", "stop_sequence", "tool_use" ->
                ChatCompletion.Choice.FinishReason.STOP;
            case "MAX_TOKENS", "max_tokens" ->
                ChatCompletion.Choice.FinishReason.LENGTH;
            case "SAFETY", "RECITATION", "content_filter" ->
                ChatCompletion.Choice.FinishReason.CONTENT_FILTER;
            default -> ChatCompletion.Choice.FinishReason.STOP;
        };
    }

    /** 流式版本:将 IR finishReason(String)转为 Chunk FinishReason。
     * spec 第 5 节:同协议合法值直接用,跨协议按语义映射。 */
    private ChatCompletionChunk.Choice.FinishReason mapChunkFinishReason(String reason) {
        if (reason == null) return ChatCompletionChunk.Choice.FinishReason.STOP;
        // OpenAI 合法值直接用(同协议零损失)
        switch (reason) {
            case "stop", "length", "tool_calls", "content_filter", "function_call":
                return ChatCompletionChunk.Choice.FinishReason.of(reason);
        }
        // 跨协议:按语义映射
        return switch (reason) {
            case "STOP", "end_turn", "stop_sequence", "tool_use" ->
                ChatCompletionChunk.Choice.FinishReason.STOP;
            case "MAX_TOKENS", "max_tokens" ->
                ChatCompletionChunk.Choice.FinishReason.LENGTH;
            case "SAFETY", "RECITATION", "content_filter" ->
                ChatCompletionChunk.Choice.FinishReason.CONTENT_FILTER;
            default -> ChatCompletionChunk.Choice.FinishReason.STOP;
        };
    }

    /** 将 UnifiedChatResponse 转为 ChatCompletionChunk（SSE 流式块） */
    private ChatCompletionChunk mapToChatCompletionChunk(UnifiedChatResponse chunk) {
        List<ChatCompletionChunk.Choice> choices = chunk.choices().stream()
                .map(c -> {
                    var deltaBuilder = ChatCompletionChunk.Choice.Delta.builder();

                    if (c.delta() != null) {
                        if (c.delta().content() != null) {
                            deltaBuilder.content(Optional.of(c.delta().content()));
                        } else {
                            deltaBuilder.content(Optional.empty());
                        }
                        if (c.delta().role() != null) {
                            deltaBuilder.role(ChatCompletionChunk.Choice.Delta.Role.of(c.delta().role()));
                        }
                        // reasoning_content(通过 additionalProperty 透传,SDK 无原生字段)
                        if (c.delta().reasoningContent() != null) {
                            deltaBuilder.putAdditionalProperty("reasoning_content",
                                JsonValue.from(c.delta().reasoningContent()));
                        }

                        // 合并 toolCalls(start 信号,id+name) + toolCallArgumentDeltas(argument 增量,带 index)
                        List<ChatCompletionChunk.Choice.Delta.ToolCall> allToolCalls = new ArrayList<>();
                        if (c.delta().toolCalls() != null && !c.delta().toolCalls().isEmpty()) {
                            allToolCalls.addAll(mapDeltaToolCalls(c.delta().toolCalls()));
                        }
                        if (c.delta().toolCallArgumentDeltas() != null) {
                            for (IndexedArgumentDelta d : c.delta().toolCallArgumentDeltas()) {
                                allToolCalls.add(ChatCompletionChunk.Choice.Delta.ToolCall.builder()
                                    .index(d.index() != null ? d.index() : 0)
                                    .function(ChatCompletionChunk.Choice.Delta.ToolCall.Function.builder()
                                        .arguments(d.partialJson())
                                        .build())
                                    .build());
                            }
                        }
                        if (!allToolCalls.isEmpty()) {
                            deltaBuilder.toolCalls(allToolCalls);
                        }
                    } else {
                        deltaBuilder.content(Optional.empty());
                    }

                    Optional<ChatCompletionChunk.Choice.FinishReason> finishReason = Optional.empty();
                    if (c.finishReason() != null) {
                        finishReason = Optional.of(mapChunkFinishReason(c.finishReason()));
                    }

                    return ChatCompletionChunk.Choice.builder()
                            .index(c.index())
                            .delta(deltaBuilder.build())
                            .finishReason(finishReason)
                            .build();
                })
                .toList();

        var chunkBuilder = ChatCompletionChunk.builder()
                .id(chunk.id() != null ? chunk.id() : "chatcmpl-" + UUID.randomUUID())
                .object_(JsonValue.from("chat.completion.chunk"))
                .created(chunk.created() != 0 ? chunk.created() : Instant.now().getEpochSecond())
                .model(chunk.model())
                .choices(choices);

        // 流式 usage(最后 chunk 含 usage)
        if (chunk.usage() != null) {
            var usageBuilder = CompletionUsage.builder()
                    .promptTokens(chunk.usage().promptTokens())
                    .completionTokens(chunk.usage().completionTokens())
                    .totalTokens(chunk.usage().totalTokens());
            if (chunk.usage().cachedTokens() > 0) {
                usageBuilder.promptTokensDetails(PromptTokensDetails.builder()
                    .cachedTokens(chunk.usage().cachedTokens())
                    .build());
            }
            if (chunk.usage().reasoningTokens() > 0) {
                usageBuilder.completionTokensDetails(CompletionTokensDetails.builder()
                    .reasoningTokens(chunk.usage().reasoningTokens())
                    .build());
            }
            chunkBuilder.usage(usageBuilder.build());
        }

        return chunkBuilder.build();
    }

    /** 将 IR 的 UnifiedToolCall 列表转为 SDK 的 Delta.ToolCall 列表 */
    private List<ChatCompletionChunk.Choice.Delta.ToolCall> mapDeltaToolCalls(
            List<UnifiedToolCall> toolCalls) {
        var result = new ArrayList<ChatCompletionChunk.Choice.Delta.ToolCall>();
        for (int i = 0; i < toolCalls.size(); i++) {
            var tc = toolCalls.get(i);
            if (tc.type() != null && tc.type().equals("function") && tc.function() != null) {
                var fnBuilder = ChatCompletionChunk.Choice.Delta.ToolCall.Function.builder()
                    .name(tc.function().name());
                if (tc.function().arguments() != null) {
                    fnBuilder.arguments(tc.function().arguments().toString());
                }
                result.add(ChatCompletionChunk.Choice.Delta.ToolCall.builder()
                    .index(tc.index() != null ? tc.index() : i)
                    .id(tc.id() != null ? tc.id() : "")
                    .function(fnBuilder.build())
                    .build());
            }
        }
        return result;
    }

    /**
     * 将 tool 角色消息重排到对应 assistant(tool_calls) 消息之后。
     */
    private static List<UnifiedMessage> normalizeToolCallMessageOrder(List<UnifiedMessage> messages) {
        List<UnifiedMessage> result = new ArrayList<>(messages);

        for (int i = 0; i < result.size(); i++) {
            UnifiedMessage msg = result.get(i);
            if (msg.role() != UnifiedMessage.Role.ASSISTANT || msg.toolCalls() == null || msg.toolCalls().isEmpty()) {
                continue;
            }

            // 收集此 assistant 消息的 tool_call IDs
            Set<String> pendingIds = new LinkedHashSet<>();
            for (UnifiedToolCall tc : msg.toolCalls()) {
                if (tc.id() != null && !tc.id().isEmpty()) {
                    pendingIds.add(tc.id());
                }
            }
            if (pendingIds.isEmpty()) continue;

            // 扫描后续消息，收集匹配的 tool 消息和延迟消息
            List<UnifiedMessage> toolMessages = new ArrayList<>();
            List<UnifiedMessage> deferredMessages = new ArrayList<>();
            int endIdx = i;
            for (int j = i + 1; j < result.size() && !pendingIds.isEmpty(); j++) {
                UnifiedMessage next = result.get(j);
                if (next.role() == UnifiedMessage.Role.TOOL && next.toolCallId() != null && pendingIds.remove(next.toolCallId())) {
                    toolMessages.add(next);
                } else {
                    deferredMessages.add(next);
                }
                endIdx = j;
            }

            if (pendingIds.isEmpty() && !toolMessages.isEmpty()) {
                // 重排
                List<UnifiedMessage> reordered = new ArrayList<>();
                reordered.addAll(result.subList(0, i + 1));  // 包含当前 assistant
                reordered.addAll(toolMessages);                // tool 消息紧跟
                reordered.addAll(deferredMessages);            // 其他消息滞后
                reordered.addAll(result.subList(endIdx + 1, result.size()));
                result = reordered;
                i += toolMessages.size();
            }
        }

        return result;
    }
}
