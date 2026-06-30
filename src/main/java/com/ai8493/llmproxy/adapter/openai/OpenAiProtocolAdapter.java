package com.ai8493.llmproxy.adapter.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.ai8493.llmproxy.adapter.ProtocolAdapter;
import com.ai8493.llmproxy.exception.BackendApiException;
import com.ai8493.llmproxy.model.*;
import com.openai.core.JsonValue;
import com.openai.models.FunctionDefinition;
import com.openai.models.FunctionParameters;
import com.openai.models.chat.completions.*;
import com.openai.models.completions.CompletionUsage;
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

            return new UnifiedChatRequest(model, messages, config, tools, toolChoice, stream);
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
            String content = parseContent(msg.get("content"));
            String name = msg.has("name") ? msg.get("name").asText() : null;
            String toolCallId = msg.has("tool_call_id") ? msg.get("tool_call_id").asText() : null;

            List<UnifiedToolCall> toolCalls = null;
            if (msg.has("tool_calls") && !msg.get("tool_calls").isNull()) {
                toolCalls = parseMessageToolCalls(msg.get("tool_calls"));
            }

            result.add(new UnifiedMessage(
                switch (role) {
                    case "system" -> UnifiedMessage.Role.SYSTEM;
                    case "user" -> UnifiedMessage.Role.USER;
                    case "assistant" -> UnifiedMessage.Role.ASSISTANT;
                    case "tool" -> UnifiedMessage.Role.TOOL;
                    default -> UnifiedMessage.Role.USER;
                },
                content,
                null,  // parts — 多模态 content 保留为 JSON 字符串在 content 中
                toolCalls,
                toolCallId,
                name,
                null  // reasoningContent
            ));
        }
        return result;
    }

    /** 解析 content 字段：字符串直接返回，数组序列化为 JSON 字符串，null 返回 null */
    private String parseContent(JsonNode contentNode) {
        if (contentNode == null || contentNode.isNull()) return null;
        if (contentNode.isTextual()) return contentNode.asText();
        if (contentNode.isArray()) return contentNode.toString();
        return contentNode.asText();
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
            result.add(new UnifiedToolCall(id, type, new UnifiedFunctionCall(fnName, fnArgs)));
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
                result.add(new UnifiedTool(type, new UnifiedFunctionDefinition(fnName, fnDesc, fnParams)));
            }
        }
        return result.isEmpty() ? null : result;
    }

    /** 解析 tool_choice */
    private UnifiedToolChoice parseToolChoice(JsonNode tcNode) {
        if (tcNode == null || tcNode.isNull()) return null;
        if (tcNode.isTextual()) {
            return switch (tcNode.asText()) {
                case "none" -> new UnifiedToolChoice.None();
                case "required" -> new UnifiedToolChoice.Auto();
                default -> new UnifiedToolChoice.Auto();
            };
        }
        if (tcNode.isObject() && tcNode.has("function")) {
            JsonNode fn = tcNode.get("function");
            if (fn.has("name")) {
                return new UnifiedToolChoice.Required(fn.get("name").asText());
            }
        }
        return new UnifiedToolChoice.Auto();
    }

    /** 解析 generation config */
    private UnifiedGenerationConfig parseConfig(JsonNode root) {
        Double temperature = root.has("temperature") ? root.get("temperature").asDouble() : null;
        Double topP = root.has("top_p") ? root.get("top_p").asDouble() : null;
        Integer maxTokens = root.has("max_tokens") ? root.get("max_tokens").asInt() : null;
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
        return new UnifiedGenerationConfig(temperature, topP, maxTokens, stop, null, null, null, null);
    }

    /** Type-safe entry point for Controller */
    public UnifiedChatRequest toUnifiedRequest(ChatCompletionCreateParams req, boolean stream) {
        List<UnifiedMessage> messages = normalizeToolCallMessageOrder(
            req.messages().stream()
                .map(this::mapMessage)
                .toList()
        );

        UnifiedGenerationConfig config = new UnifiedGenerationConfig(
                req.temperature().orElse(null),
                req.topP().orElse(null),
                req.maxTokens().map(Long::intValue).orElse(null),
                req.stop().map(this::mapStop).orElse(null),
                null, null, null, null
        );

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

        return new UnifiedChatRequest(
                req.model().toString(),
                messages,
                config,
                tools,
                toolChoice,
                stream
        );
    }

    @Override
    public byte[] fromUnifiedResponse(UnifiedChatResponse uResp) {
        try {
            ChatCompletion resp = mapToChatCompletion(uResp);
            return mapper.writeValueAsBytes(resp);
        } catch (Exception e) {
            throw new RuntimeException("序列化 OpenAI 响应失败", e);
        }
    }

    @Override
    public String fromUnifiedStreamChunk(UnifiedChatResponse chunk) {
        try {
            ChatCompletionChunk c = mapToChatCompletionChunk(chunk);
            return mapper.writeValueAsString(c);
        } catch (Exception e) {
            throw new RuntimeException("序列化流块失败: " + e.getMessage(), e);
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
            return new UnifiedMessage(
                    UnifiedMessage.Role.SYSTEM,
                    sys.content().isText() ? sys.content().asText() : null,
                    null, // parts
                    null, // toolCalls
                    null, // toolCallId
                    sys.name().orElse(null), // name
                    null // reasoningContent
            );
        } else if (param.isUser()) {
            var user = param.asUser();
            return new UnifiedMessage(
                    UnifiedMessage.Role.USER,
                    user.content().isText() ? user.content().asText() : null,
                    null, // parts
                    null, // toolCalls
                    null, // toolCallId
                    user.name().orElse(null), // name
                    null // reasoningContent
            );
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
            return new UnifiedMessage(
                    UnifiedMessage.Role.ASSISTANT,
                    content,
                    null, // parts
                    toolCalls,
                    null, // toolCallId
                    asst.name().orElse(null), // name
                    null // reasoningContent
            );
        } else if (param.isTool()) {
            var tool = param.asTool();
            return new UnifiedMessage(
                    UnifiedMessage.Role.TOOL,
                    tool.content().isText() ? tool.content().asText() : null,
                    null, // parts
                    null, // toolCalls
                    tool.toolCallId(),
                    null, // name
                    null // reasoningContent
            );
        } else if (param.isDeveloper()) {
            // developer 消息降级为 user
            var dev = param.asDeveloper();
            return new UnifiedMessage(
                    UnifiedMessage.Role.USER,
                    dev.content().isText() ? dev.content().asText() : null,
                    null, null, null, null, null
            );
        } else {
            // function（已弃用）— 降级为 user
            return new UnifiedMessage(
                    UnifiedMessage.Role.USER, null, null, null, null, null, null
            );
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
            return new UnifiedToolCall(
                    fn.id(),
                    "function",
                    new UnifiedFunctionCall(
                            fn.function().name(),
                            argsNode
                    )
            );
        }
        // custom tool call — 降级为空 tool call
        return new UnifiedToolCall("", "custom", null);
    }

    /** 将 ChatCompletionTool 映射为 UnifiedTool */
    private UnifiedTool mapTool(ChatCompletionTool tool) {
        if (tool.isFunction()) {
            var fn = tool.asFunction();
            return new UnifiedTool(
                    "function",
                    mapFunctionDefinition(fn.function())
            );
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
        return new UnifiedFunctionDefinition(
                fn.name(),
                fn.description().orElse(null),
                paramsNode
        );
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
                return new UnifiedToolChoice.None();
            }
            // AUTO 或 REQUIRED → IR 统一为 Auto
            return new UnifiedToolChoice.Auto();
        }
        if (tc.isAllowedToolChoice()) {
            // "required"（不指定具体函数）→ Auto
            return new UnifiedToolChoice.Auto();
        }
        if (tc.isNamedToolChoice()) {
            var named = tc.asNamedToolChoice();
            return new UnifiedToolChoice.Required(named.function().name());
        }
        return new UnifiedToolChoice.Auto();
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
            usage = CompletionUsage.builder()
                    .promptTokens(uResp.usage().promptTokens())
                    .completionTokens(uResp.usage().completionTokens())
                    .totalTokens(uResp.usage().totalTokens())
                    .build();
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

        // refusal (required field in SDK v3.x, set to empty)
        builder.refusal(Optional.empty());

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

    /** 将 IR finishReason（String）转为 SDK FinishReason，null 时默认为 STOP */
    private ChatCompletion.Choice.FinishReason mapFinishReason(String reason) {
        if (reason == null) return ChatCompletion.Choice.FinishReason.STOP;
        return ChatCompletion.Choice.FinishReason.of(reason);
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
                        if (c.delta().toolCalls() != null && !c.delta().toolCalls().isEmpty()) {
                            deltaBuilder.toolCalls(mapDeltaToolCalls(c.delta().toolCalls()));
                        }
                    } else {
                        deltaBuilder.content(Optional.empty());
                    }

                    Optional<ChatCompletionChunk.Choice.FinishReason> finishReason = Optional.empty();
                    if (c.finishReason() != null) {
                        finishReason = Optional.of(
                            ChatCompletionChunk.Choice.FinishReason.of(c.finishReason()));
                    }

                    return ChatCompletionChunk.Choice.builder()
                            .index(c.index())
                            .delta(deltaBuilder.build())
                            .finishReason(finishReason)
                            .build();
                })
                .toList();

        return ChatCompletionChunk.builder()
                .id(chunk.id() != null ? chunk.id() : "chatcmpl-" + UUID.randomUUID())
                .object_(JsonValue.from("chat.completion.chunk"))
                .created(chunk.created() != 0 ? chunk.created() : Instant.now().getEpochSecond())
                .model(chunk.model())
                .choices(choices)
                .build();
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
                    .index(i)
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
