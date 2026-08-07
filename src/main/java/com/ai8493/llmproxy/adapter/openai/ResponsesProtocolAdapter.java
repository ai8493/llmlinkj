package com.ai8493.llmproxy.adapter.openai;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.openai.core.JsonValue;
import com.openai.models.ResponsesModel;
import com.openai.models.responses.*;
import java.time.Instant;
import com.ai8493.llmproxy.adapter.ProtocolAdapter;
import com.ai8493.llmproxy.cache.SessionStore;
import com.ai8493.llmproxy.exception.BackendApiException;
import com.ai8493.llmproxy.exception.TransformException;
import com.ai8493.llmproxy.model.*;
import com.ai8493.llmproxy.model.extensions.OpenAiExtensions;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
public class ResponsesProtocolAdapter implements ProtocolAdapter {

    private final ObjectMapper mapper = new ObjectMapper()
        .registerModule(new Jdk8Module())
        .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        .registerModule(new SimpleModule() {{
            addSerializer(Double.class, new JsonSerializer<Double>() {
                @Override
                public void serialize(Double value, JsonGenerator gen,
                                      SerializerProvider serializers) throws java.io.IOException {
                    if (value == Math.floor(value) && !Double.isInfinite(value)) {
                        gen.writeNumber(value.longValue());
                    } else {
                        gen.writeNumber(value);
                    }
                }
            });
        }});

    private final SessionStore sessionStore;

    public ResponsesProtocolAdapter(SessionStore sessionStore) {
        this.sessionStore = sessionStore;
    }

    /**
     * 流式转换累积状态。
     * 每个请求一个实例，由 Controller 管理生命周期。
     */
    public static class StreamState {
        public int seq = 0;
        public String responseId = "resp_unknown";
        public String modelName = "";
        public long createdAt;
        public boolean firstChunk = true;

        // 文本块状态
        public boolean inTextBlock;
        public String currentMsgId;
        public int messageOutputIndex;
        public final StringBuilder textBuf = new StringBuilder();

        // reasoning 状态
        public boolean inReasoningBlock;
        public boolean reasoningPartAdded;
        public String reasoningItemId;
        public int reasoningIndex;
        public final StringBuilder reasoningBuf = new StringBuilder();

        // function_call 状态
        public boolean inFuncBlock;
        public final Map<Integer, FcAcc> funcAccs = new LinkedHashMap<>();

        // usage 追踪
        public long inputTokens;
        public long outputTokens;
        public long totalTokens;
        public long cachedTokens;
        public long reasoningTokens;

        // 流截断分类:跟踪是否已收到 finishReason + 是否有实质性输出
        public boolean hasFinishReason;
        public boolean hasSubstantiveOutput;

        // 请求回显
        public String instructions;
        public JsonNode reasoning;
        public JsonNode metadata;
        public final ToolRemapContext remapCtx;

        public StreamState() {
            this.remapCtx = null;
        }

        public StreamState(ToolRemapContext remapCtx) {
            this.remapCtx = remapCtx;
        }

        public int nextSeq() { return ++seq; }

        public static class FcAcc {
            public String id;
            public String name;
            public final StringBuilder argsBuf = new StringBuilder();
            // remap 相关字段
            public int remapKind = 0;          // 0=NONE, 1=CUSTOM, 2=NAMESPACE
            public String customOriginalName;   // custom 工具的原始名称
            public String namespaceName;        // namespace 名称
            public boolean itemAdded = false;   // 是否已发送 output_item.added
        }
    }

    @Override
    public String protocolName() { return "responses"; }

    @Override
    public UnifiedChatRequest toUnifiedRequest(byte[] rawRequest, Map<String, String> headers) {
        return parseInternal(rawRequest, null);
    }

    public ParseResult parseRequest(byte[] rawBody) {
        ToolRemapContext ctx = new ToolRemapContext();
        UnifiedChatRequest req = parseInternal(rawBody, ctx);
        return new ParseResult(req, ctx);
    }

    private UnifiedChatRequest parseInternal(byte[] rawRequest, ToolRemapContext ctx) {
        try {
            JsonNode root = mapper.readTree(rawRequest);
            boolean stream = root.has("stream") && root.get("stream").asBoolean(false);

            // 跨请求恢复 function_call:按 previous_response_id 从 SessionStore 补全缺失的 call item
            // DeepSeek/kimi 等后端要求 assistant tool_call 紧邻 tool result,否则 400
            if (root.isObject() && sessionStore != null) {
                sessionStore.enrichRequest((ObjectNode) root);
            }

            byte[] processed = preprocessInputTypes(root);
            ResponseCreateParams.Body body = mapper.readValue(processed,
                ResponseCreateParams.Body.class);

            if (body.store().orElse(false)) {
                throw new IllegalArgumentException("store=true 暂不支持，请使用无状态模式");
            }

            String model = body.model().map(ResponsesProtocolAdapter::extractModelName).orElse("");
            String instructions = com.ai8493.llmproxy.util.BillingHeaderStripper.strip(
                body.instructions().orElse(null));

            List<UnifiedMessage> messages = parseSdkInput(body.input(), instructions);

            List<String> stop = null;
            if (root.has("stop")) {
                JsonNode stopNode = root.get("stop");
                if (stopNode.isTextual()) stop = List.of(stopNode.asText());
                else if (stopNode.isArray()) {
                    stop = new ArrayList<>();
                    for (JsonNode s : stopNode) stop.add(s.asText());
                }
            }

            String reasoningEffort = null;
            if (body.reasoning().isPresent()) {
                var r = body.reasoning().get();
                if (r.effort().isPresent()) {
                    String effort = r.effort().get().asString();
                    reasoningEffort = switch (effort) {
                        case "minimal" -> "low";
                        case "none", "auto", "low", "medium", "high", "xhigh" -> effort;
                        default -> "auto";
                    };
                }
            }

            UnifiedGenerationConfig config = UnifiedGenerationConfig.builder()
                .temperature(body.temperature().orElse(null))
                .topP(body.topP().orElse(null))
                .maxOutputTokens(body.maxOutputTokens().map(Long::intValue).orElse(null))
                .stopSequences(stop)
                .reasoningEffort(reasoningEffort)
                .user(body.user().orElse(null))
                .parallelToolCalls(body.parallelToolCalls().orElse(null))
                .build();
            List<UnifiedTool> tools = parseSdkTools(body.tools(), ctx);
            UnifiedToolChoice toolChoice = parseSdkToolChoice(body.toolChoice());

            // 从 raw JSON 读 OpenAI 专属字段(SDK ResponseCreateParams.Body 不支持)
            OpenAiExtensions openaiExt = null;
            if (root.has("logprobs") || root.has("top_logprobs") || root.has("seed")
                    || root.has("n") || root.has("response_format")
                    || root.has("previous_response_id") || root.has("include")) {
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
                if (root.has("previous_response_id") && root.get("previous_response_id").isTextual()) {
                    extBuilder.previousResponseId(root.get("previous_response_id").asText());
                }
                if (root.has("include") && root.get("include").isArray()) {
                    List<String> includeList = new ArrayList<>();
                    for (JsonNode inc : root.get("include")) {
                        if (inc.isTextual()) includeList.add(inc.asText());
                    }
                    if (!includeList.isEmpty()) {
                        extBuilder.include(includeList);
                    }
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
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("无法解析 Responses 请求: " + e.getMessage(), e);
        }
    }

    /** 预处理 input 数组：补 type 字段、转换字符串 content 为数组格式 */
    private byte[] preprocessInputTypes(JsonNode root) {
        try {
            JsonNode input = root.get("input");
            if (input == null || !input.isArray()) return mapper.writeValueAsBytes(root);
            boolean modified = false;
            for (JsonNode item : input) {
                if (!item.isObject()) continue;
                var obj = (ObjectNode) item;
                // 补 "type":"message"
                if (!obj.has("type") && obj.has("role")) {
                    obj.put("type", "message");
                    modified = true;
                }
                // 字符串 content → [{type:"input_text", text:"..."}]
                JsonNode content = obj.get("content");
                if (content != null && content.isTextual()) {
                    ArrayNode arr = mapper.createArrayNode();
                    ObjectNode textPart = mapper.createObjectNode();
                    textPart.put("type", "input_text");
                    textPart.put("text", content.asText());
                    arr.add(textPart);
                    obj.set("content", arr);
                    modified = true;
                }
                // output_text → input_text
                if (content != null && content.isArray()) {
                    for (JsonNode contentItem : content) {
                        if (contentItem.has("type") && "output_text".equals(contentItem.get("type").asText())) {
                            ((ObjectNode) contentItem).put("type", "input_text");
                            modified = true;
                        }
                    }
                }
            }
            return mapper.writeValueAsBytes(root);
        } catch (Exception e) {
            throw new IllegalArgumentException("预处理 input 类型失败", e);
        }
    }

    // ---- 非流式响应转换（含请求回显） ----

    @Override
    public byte[] fromUnifiedResponse(UnifiedChatResponse uResp) {
        return fromUnifiedResponse(uResp, null);
    }

    public byte[] fromUnifiedResponse(UnifiedChatResponse uResp, UnifiedChatRequest originalRequest) {
        try {
            String respId = uResp.id() != null ? uResp.id() : "resp_" + UUID.randomUUID();
            long created = uResp.created() != 0 ? uResp.created() : Instant.now().getEpochSecond();

            List<ResponseOutputItem> items = new ArrayList<>();

            // reasoning item（来自 reasoning_content）
            for (UnifiedChoice choice : uResp.choices()) {
                if (choice.message() != null && choice.message().reasoningContent() != null) {
                    items.add(ResponseOutputItem.ofReasoning(
                        ResponseReasoningItem.builder()
                            .id(respId + "_reasoning_0")
                            .status(ResponseReasoningItem.Status.COMPLETED)
                            .summary(List.of(ResponseReasoningItem.Summary.builder()
                                .text(choice.message().reasoningContent()).build()))
                            .build()));
                }
            }

            // message + function_call items（使用现有 mapToOutputItems 逻辑）
            items.addAll(mapToOutputItems(uResp.choices(), respId));

            var builder = Response.builder()
                .id(respId).createdAt((double) created).model(uResp.model())
                .object_(JsonValue.from("response")).status(ResponseStatus.COMPLETED)
                .output(items);

            // 请求回显（温度/topP 始终显式设置，避免 builder 校验失败）
            if (originalRequest != null && originalRequest.config() != null) {
                var cfg = originalRequest.config();
                if (cfg.temperature() != null) builder.temperature(cfg.temperature().doubleValue());
                else builder.temperature((Double) null);
                if (cfg.topP() != null) builder.topP(cfg.topP().doubleValue());
                else builder.topP((Double) null);
                if (cfg.parallelToolCalls() != null) builder.parallelToolCalls(cfg.parallelToolCalls());
                else builder.parallelToolCalls(true);
            } else {
                builder.temperature((Double) null).topP((Double) null).parallelToolCalls(true);
            }
            if (originalRequest != null && originalRequest.tools() != null && !originalRequest.tools().isEmpty())
                builder.tools(mapToResponseTools(originalRequest.tools()));
            else builder.tools(List.of());
            if (originalRequest != null && originalRequest.toolChoice() != null)
                builder.toolChoice(mapToResponseToolChoice(originalRequest.toolChoice()));
            else builder.toolChoice(ToolChoiceOptions.AUTO);

            builder.error((ResponseError) null)
                .incompleteDetails((Response.IncompleteDetails) null)
                .instructions((Response.Instructions) null)
                .metadata((Response.Metadata) null);

            if (uResp.usage() != null) {
                builder.usage(ResponseUsage.builder()
                    .inputTokens(uResp.usage().promptTokens())
                    .outputTokens(uResp.usage().completionTokens())
                    .totalTokens(uResp.usage().totalTokens())
                    .inputTokensDetails(ResponseUsage.InputTokensDetails.builder()
                        .cachedTokens(uResp.usage().cachedTokens()).build())
                    .outputTokensDetails(ResponseUsage.OutputTokensDetails.builder()
                        .reasoningTokens(uResp.usage().reasoningTokens()).build())
                    .build());
            }

            Response response = builder.build();
            // 缓存 response_id -> output 中的 function_call items,供后续 previous_response_id 恢复
            if (sessionStore != null) {
                try {
                    JsonNode respNode = mapper.valueToTree(response);
                    sessionStore.recordResponse(respId, respNode.get("output"));
                } catch (Exception ex) {
                    // 缓存失败不影响主流程
                }
            }
            return mapper.writeValueAsBytes(response);
        } catch (Exception e) {
            throw new TransformException("序列化 Responses 响应失败", e);
        }
    }

    public byte[] fromUnifiedResponse(UnifiedChatResponse uResp, UnifiedChatRequest originalRequest,
                                       ToolRemapContext remapCtx) {
        // 无还原需求时走原路径
        if (remapCtx == null || remapCtx.isEmpty()) {
            return fromUnifiedResponse(uResp, originalRequest);
        }
        try {
            // 先用原路径构建完整响应
            byte[] raw = fromUnifiedResponse(uResp, originalRequest);
            JsonNode root = mapper.readTree(raw);

            // 修改 output 数组中的 function_call items
            JsonNode output = root.get("output");
            if (output != null && output.isArray()) {
                ArrayNode newOutput = mapper.createArrayNode();
                for (JsonNode item : output) {
                    String type = item.has("type") ? item.get("type").asText() : "";
                    String name = item.has("name") ? item.get("name").asText() : "";
                    String callId = item.has("call_id") ? item.get("call_id").asText() : "";

                    if ("function_call".equals(type) && remapCtx.isCustomProxy(name)) {
                        // custom 工具 → custom_tool_call
                        var spec = remapCtx.getCustomSpec(name);
                        String argsStr = item.has("arguments") ? item.get("arguments").asText() : "{}";
                        String input = extractCustomInput(tryParseJson(argsStr));
                        ObjectNode remapped = mapper.createObjectNode();
                        remapped.put("id", "ctc_" + callId);
                        remapped.put("type", "custom_tool_call");
                        remapped.put("status", "completed");
                        remapped.put("call_id", callId);
                        remapped.put("name", spec.originalName());
                        remapped.put("input", input);
                        newOutput.add(remapped);
                    } else if ("function_call".equals(type)) {
                        String argsStr = item.has("arguments") ? item.get("arguments").asText() : "{}";
                        String alias = extractCustomNs(argsStr);
                        ToolRemapContext.NamespaceSpec nsSpec = null;
                        if (alias != null) {
                            String namespace = remapCtx.getNamespaceByAlias(alias);
                            if (namespace != null) {
                                nsSpec = new ToolRemapContext.NamespaceSpec(name, namespace);
                            }
                        }
                        if (nsSpec == null) {
                            nsSpec = remapCtx.getNamespaceSpec(name);
                        }
                        if (nsSpec != null) {
                            ObjectNode remapped = item.deepCopy();
                            remapped.put("name", nsSpec.originalName());
                            remapped.put("namespace", nsSpec.namespace());
                            remapped.put("arguments", stripCustomNs(argsStr));
                            newOutput.add(remapped);
                        } else {
                            newOutput.add(item);
                        }
                    } else {
                        newOutput.add(item);
                    }
                }
                ((ObjectNode) root).set("output", newOutput);
            }

            return mapper.writeValueAsBytes(root);
        } catch (Exception e) {
            throw new TransformException("序列化 Responses 响应失败", e);
        }
    }

    /** 将 IR choices 转换为 Responses SDK 的 ResponseOutputItem 列表 */
    private List<ResponseOutputItem> mapToOutputItems(List<UnifiedChoice> choices, String defaultItemId) {
        List<ResponseOutputItem> items = new ArrayList<>();
        int itemIdx = 0;
        for (UnifiedChoice choice : choices) {
            String itemId = defaultItemId + "_" + itemIdx++;

            List<ResponseOutputMessage.Content> content = new ArrayList<>();
            UnifiedMessage msg = choice.message();
            if (msg != null) {
                if (msg.content() != null) {
                    content.add(ResponseOutputMessage.Content.ofOutputText(
                        ResponseOutputText.builder()
                            .text(msg.content())
                            .annotations(List.of())
                            .build()));
                }
                if (msg.toolCalls() != null) {
                    for (UnifiedToolCall tc : msg.toolCalls()) {
                        // function_call 在 Responses API 中是独立的 output item
                        var fcBuilder = ResponseFunctionToolCall.builder()
                            .callId(tc.id() != null ? tc.id() : "")
                            .name(tc.function().name())
                            .arguments(tc.function().arguments() != null
                                ? tc.function().arguments().toString() : "{}");
                        if (tc.id() != null) fcBuilder.id(tc.id());
                        items.add(ResponseOutputItem.ofFunctionCall(fcBuilder.build()));
                    }
                }
            }

            ResponseOutputMessage message = ResponseOutputMessage.builder()
                .id(itemId)
                .status(ResponseOutputMessage.Status.COMPLETED)
                .role(JsonValue.from("assistant"))
                .content(content)
                .build();
            items.add(ResponseOutputItem.ofMessage(message));
        }
        return items;
    }

    @Override
    public String fromUnifiedStreamChunk(UnifiedChatResponse chunk) {
        UnifiedDelta delta = (!chunk.choices().isEmpty()) ? chunk.choices().get(0).delta() : null;
        if (delta != null && delta.content() != null && !delta.content().isEmpty()) {
            try {
                return mapper.writeValueAsString(
                    ResponseTextDeltaEvent.builder()
                        .itemId("msg_1")
                        .outputIndex(0)
                        .contentIndex(0)
                        .delta(delta.content())
                        .logprobs(List.of())
                        .build());
            } catch (Exception e) {
                throw new TransformException("序列化 Responses 流块失败", e);
            }
        }
        return "";
    }

    // ---- 流式事件转换（StreamState 版本 + 旧版兼容） ----

    /**
     * 将单个 IR 流块转换为 Responses API SSE 事件 JSON 列表（StreamState 版本）。
     */
    public List<String> toStreamEvents(UnifiedChatResponse chunk, StreamState st) {
        try {
            List<String> events = new ArrayList<>();

            // 2a. 首个 chunk 初始化
            if (st.firstChunk) {
                st.firstChunk = false;
                st.responseId = chunk.id() != null ? chunk.id() : "resp_unknown";
                st.modelName = chunk.model() != null ? chunk.model() : "";
                st.createdAt = chunk.created();
                st.textBuf.setLength(0);
                st.reasoningBuf.setLength(0);
                st.inTextBlock = false;
                st.inReasoningBlock = false;
                st.inFuncBlock = false;
                st.reasoningPartAdded = false;
                st.funcAccs.clear();

                // response.created
                events.add(buildLifecycleEvent("response.created",
                    lifecycleEventsResponseJson(st.responseId, st.modelName, "in_progress", st.createdAt, st.instructions), st.nextSeq()));
                // response.in_progress
                events.add(buildLifecycleEvent("response.in_progress",
                    lifecycleEventsResponseJson(st.responseId, st.modelName, "in_progress", st.createdAt, st.instructions), st.nextSeq()));
            }

            UnifiedDelta delta = (!chunk.choices().isEmpty()) ? chunk.choices().get(0).delta() : null;

            // 2b. reasoning delta 处理
            if (delta != null && delta.reasoningContent() != null && !delta.reasoningContent().isEmpty()) {
                String rc = delta.reasoningContent();
                if (!st.inReasoningBlock) {
                    st.inReasoningBlock = true;
                    st.reasoningBuf.setLength(0);
                    st.reasoningItemId = "rs_" + st.responseId + "_0";
                    st.reasoningIndex = 0;

                    // response.output_item.added (reasoning)
                    events.add(mapper.writeValueAsString(
                        ResponseOutputItemAddedEvent.builder()
                            .outputIndex(st.reasoningIndex)
                            .item(ResponseOutputItem.ofReasoning(
                                ResponseReasoningItem.builder()
                                    .id(st.reasoningItemId)
                                    .status(ResponseReasoningItem.Status.IN_PROGRESS)
                                    .summary(List.of())
                                    .build()))
                            .sequenceNumber(st.nextSeq())
                            .type(JsonValue.from("response.output_item.added"))
                            .build()));

                    // response.reasoning_summary_part.added
                    events.add(mapper.writeValueAsString(
                        ResponseReasoningSummaryPartAddedEvent.builder()
                            .itemId(st.reasoningItemId)
                            .outputIndex(st.reasoningIndex)
                            .summaryIndex(0)
                            .part(ResponseReasoningSummaryPartAddedEvent.Part.builder().text("").build())
                            .sequenceNumber(st.nextSeq())
                            .type(JsonValue.from("response.reasoning_summary_part.added"))
                            .build()));
                    st.reasoningPartAdded = true;
                }
                st.reasoningBuf.append(rc);
                st.hasSubstantiveOutput = true;
                events.add(mapper.writeValueAsString(
                    ResponseReasoningSummaryTextDeltaEvent.builder()
                        .itemId(st.reasoningItemId)
                        .outputIndex(st.reasoningIndex)
                        .summaryIndex(0)
                        .delta(rc)
                        .sequenceNumber(st.nextSeq())
                        .type(JsonValue.from("response.reasoning_summary_text.delta"))
                        .build()));
            }

            // 2c. 文本 delta — 若 reasoning 活跃则先关闭
            if (delta != null && delta.content() != null && !delta.content().isEmpty()) {
                st.hasSubstantiveOutput = true;
                if (st.inReasoningBlock) {
                    events.addAll(closeReasoningBlock(st));
                }
                if (!st.inTextBlock) {
                    st.inTextBlock = true;
                    st.messageOutputIndex = st.reasoningPartAdded ? 1 : 0;
                    st.currentMsgId = "msg_" + st.responseId + "_" + st.messageOutputIndex;

                    // response.output_item.added (message)
                    events.add(mapper.writeValueAsString(
                        ResponseOutputItemAddedEvent.builder()
                            .outputIndex(st.messageOutputIndex)
                            .item(ResponseOutputItem.ofMessage(
                                ResponseOutputMessage.builder()
                                    .id(st.currentMsgId)
                                    .status(ResponseOutputMessage.Status.IN_PROGRESS)
                                    .role(JsonValue.from("assistant"))
                                    .content(List.of())
                                    .build()))
                            .sequenceNumber(st.nextSeq())
                            .type(JsonValue.from("response.output_item.added"))
                            .build()));

                    // response.content_part.added
                    events.add(mapper.writeValueAsString(
                        ResponseContentPartAddedEvent.builder()
                            .itemId(st.currentMsgId)
                            .outputIndex(st.messageOutputIndex)
                            .contentIndex(0)
                            .part(ResponseContentPartAddedEvent.Part.ofOutputText(
                                ResponseOutputText.builder().text("").annotations(List.of()).build()))
                            .sequenceNumber(st.nextSeq())
                            .type(JsonValue.from("response.content_part.added"))
                            .build()));
                }

                st.textBuf.append(delta.content());
                events.add(mapper.writeValueAsString(
                    ResponseTextDeltaEvent.builder()
                        .itemId(st.currentMsgId)
                        .outputIndex(st.messageOutputIndex)
                        .contentIndex(0)
                        .delta(delta.content())
                        .logprobs(List.of())
                        .sequenceNumber(st.nextSeq())
                        .type(JsonValue.from("response.output_text.delta"))
                        .build()));
            }

            // 2d. tool_calls delta 处理
            List<UnifiedToolCall> deltaToolCalls = (delta != null) ? delta.toolCalls() : null;
            if (deltaToolCalls != null && !deltaToolCalls.isEmpty()) {
                st.hasSubstantiveOutput = true;
                // 首次遇到 tool_call 时关闭 reasoning 和 text block
                if (!st.inFuncBlock) {
                    st.inFuncBlock = true;
                    if (st.inReasoningBlock) {
                        events.addAll(closeReasoningBlock(st));
                    }
                    if (st.inTextBlock) {
                        events.addAll(closeTextBlock(st));
                    }
                }
                for (UnifiedToolCall tc : deltaToolCalls) {
                    // content_block_start 的中间态 tool_use（args=null），跳过不发送，
                    // 等 message_stop 时 args 完整再发送，防止 Codex 收到空参数立即执行报错
                    if (tc.function().arguments() == null) {
                        continue;
                    }
                    String callId = tc.id() != null ? tc.id() : "call_unknown";
                    int fcIdx = st.funcAccs.size();
                    int outputIdx = (st.reasoningPartAdded || st.messageOutputIndex > 0 || st.textBuf.length() > 0)
                        ? st.messageOutputIndex + 1 + fcIdx
                        : fcIdx;
                    var acc = st.funcAccs.computeIfAbsent(fcIdx, k -> {
                        var a = new StreamState.FcAcc();
                        a.id = callId;
                        a.name = tc.function().name();
                        return a;
                    });

                    // 首次遇到此 tool_call 时发送 output_item.added
                    if (acc.argsBuf.length() == 0 && !tc.function().name().isEmpty()
                            && (acc.name == null || acc.name.isEmpty())) {
                        acc.name = tc.function().name();
                    }
                    // 检查 remap 上下文并记录到 FcAcc
                    if (st.remapCtx != null && !st.remapCtx.isEmpty() && acc.remapKind == 0) {
                        String fnName = acc.name;
                        if (st.remapCtx.isCustomProxy(fnName)) {
                            acc.remapKind = 1; // CUSTOM
                            acc.customOriginalName = st.remapCtx.getCustomSpec(fnName).originalName();
                        } else if (st.remapCtx.getNamespaceSpec(fnName) != null) {
                            acc.remapKind = 2; // NAMESPACE
                            var nsSpec = st.remapCtx.getNamespaceSpec(fnName);
                            acc.customOriginalName = nsSpec.originalName();
                            acc.namespaceName = nsSpec.namespace();
                        }
                    }
                    boolean isFirstArgsForThis = acc.argsBuf.length() == 0;
                    if (isFirstArgsForThis && fcIdx == st.funcAccs.size() - 1 && !acc.itemAdded) {
                        acc.itemAdded = true;
                        if (acc.remapKind == 1) {
                            // custom_tool_call 类型 —— 手动构建 JSON
                            ObjectNode itemAdded = mapper.createObjectNode();
                            itemAdded.put("type", "response.output_item.added");
                            itemAdded.put("sequence_number", st.nextSeq());
                            itemAdded.put("output_index", outputIdx);
                            ObjectNode item = mapper.createObjectNode();
                            item.put("id", "ctc_" + acc.id);
                            item.put("type", "custom_tool_call");
                            item.put("status", "in_progress");
                            item.put("call_id", acc.id);
                            item.put("name", acc.customOriginalName != null ? acc.customOriginalName : "");
                            item.put("input", "");
                            itemAdded.set("item", item);
                            events.add(mapper.writeValueAsString(itemAdded));
                        } else {
                            // function_call 类型（含 namespace 还原 name）
                            events.add(mapper.writeValueAsString(
                                ResponseOutputItemAddedEvent.builder()
                                    .outputIndex(outputIdx)
                                    .item(ResponseOutputItem.ofFunctionCall(
                                        ResponseFunctionToolCall.builder()
                                            .id(acc.id)
                                            .callId(acc.id)
                                            .name(acc.remapKind == 2
                                                ? (acc.customOriginalName != null ? acc.customOriginalName : acc.name)
                                                : (acc.name != null ? acc.name : ""))
                                            .arguments("")
                                            .build()))
                                .sequenceNumber(st.nextSeq())
                                .type(JsonValue.from("response.output_item.added"))
                                .build()));
                        }
                    }

                    String args = tc.function().arguments().toString();
                    acc.argsBuf.append(args);

                    // custom 工具跳过 arguments delta，仅内部累积
                    if (acc.remapKind == 1) {
                        continue;
                    }

                    events.add(mapper.writeValueAsString(
                        ResponseFunctionCallArgumentsDeltaEvent.builder()
                            .itemId(callId)
                            .outputIndex(outputIdx)
                            .delta(args)
                            .sequenceNumber(st.nextSeq())
                            .type(JsonValue.from("response.function_call_arguments.delta"))
                            .build()));
                }
            }

            // 2e. 累积 usage
            if (chunk.usage() != null) {
                st.inputTokens = chunk.usage().promptTokens();
                st.outputTokens = chunk.usage().completionTokens();
                st.totalTokens = chunk.usage().totalTokens();
                st.cachedTokens = chunk.usage().cachedTokens();
                st.reasoningTokens = chunk.usage().reasoningTokens();
            }

            // 2f. finish_reason 处理
            String finishReason = (!chunk.choices().isEmpty()) ? chunk.choices().get(0).finishReason() : null;
            if (finishReason != null) {
                st.hasFinishReason = true;
                if (st.inReasoningBlock) events.addAll(closeReasoningBlock(st));
                if (st.inTextBlock) events.addAll(closeTextBlock(st));
                if (st.inFuncBlock) events.addAll(closeFuncBlocks(st));
            }

            return events;
        } catch (Exception e) {
            throw new TransformException("序列化 Responses 流事件失败", e);
        }
    }

    /**
     * 旧版 toStreamEvents 保持向后兼容，内部委托到 StreamState 版本。
     */
    public List<String> toStreamEvents(UnifiedChatResponse chunk, boolean isFirstChunk,
                                        boolean isFirstContent) {
        StreamState st = new StreamState();
        st.firstChunk = isFirstChunk;
        // isFirstContent=false 表示文本块/工具块已经打开，标记对应 block 为活跃
        if (!isFirstContent) {
            st.inTextBlock = true;
            st.messageOutputIndex = 0;
            st.currentMsgId = "msg_1";
        }
        List<String> events = toStreamEvents(chunk, st);
        return events;
    }

    // ---- close 辅助方法 ----

    private List<String> closeReasoningBlock(StreamState st) {
        try {
            List<String> out = new ArrayList<>();
            if (!st.inReasoningBlock) return out;
            String full = st.reasoningBuf.toString();

            // response.reasoning_summary_text.done
            out.add(mapper.writeValueAsString(
                ResponseReasoningSummaryTextDoneEvent.builder()
                    .itemId(st.reasoningItemId).outputIndex(st.reasoningIndex).summaryIndex(0)
                    .text(full).sequenceNumber(st.nextSeq())
                    .type(JsonValue.from("response.reasoning_summary_text.done")).build()));

            // response.reasoning_summary_part.done
            out.add(mapper.writeValueAsString(
                ResponseReasoningSummaryPartDoneEvent.builder()
                    .itemId(st.reasoningItemId).outputIndex(st.reasoningIndex).summaryIndex(0)
                    .part(ResponseReasoningSummaryPartDoneEvent.Part.builder().text(full).build())
                    .sequenceNumber(st.nextSeq())
                    .type(JsonValue.from("response.reasoning_summary_part.done")).build()));

            // response.output_item.done (reasoning)
            out.add(mapper.writeValueAsString(
                ResponseOutputItemDoneEvent.builder()
                    .outputIndex(st.reasoningIndex)
                    .item(ResponseOutputItem.ofReasoning(
                        ResponseReasoningItem.builder()
                            .id(st.reasoningItemId).status(ResponseReasoningItem.Status.COMPLETED)
                            .summary(List.of(ResponseReasoningItem.Summary.builder().text(full).build())).build()))
                    .sequenceNumber(st.nextSeq())
                    .type(JsonValue.from("response.output_item.done")).build()));

            st.inReasoningBlock = false;
            return out;
        } catch (Exception e) {
            throw new RuntimeException("关闭 reasoning block 失败", e);
        }
    }

    private List<String> closeTextBlock(StreamState st) {
        try {
            List<String> out = new ArrayList<>();
            if (!st.inTextBlock) return out;
            String full = st.textBuf.toString();

            // response.output_text.done
            out.add(mapper.writeValueAsString(
                ResponseTextDoneEvent.builder()
                    .itemId(st.currentMsgId).outputIndex(st.messageOutputIndex).contentIndex(0)
                    .text(full).logprobs(List.of()).sequenceNumber(st.nextSeq())
                    .type(JsonValue.from("response.output_text.done")).build()));

            // response.content_part.done
            out.add(mapper.writeValueAsString(
                ResponseContentPartDoneEvent.builder()
                    .itemId(st.currentMsgId).outputIndex(st.messageOutputIndex).contentIndex(0)
                    .part(ResponseContentPartDoneEvent.Part.ofOutputText(
                        ResponseOutputText.builder().text(full).annotations(List.of()).build()))
                    .sequenceNumber(st.nextSeq())
                    .type(JsonValue.from("response.content_part.done")).build()));

            // response.output_item.done (message)
            out.add(mapper.writeValueAsString(
                ResponseOutputItemDoneEvent.builder()
                    .outputIndex(st.messageOutputIndex)
                    .item(ResponseOutputItem.ofMessage(
                        ResponseOutputMessage.builder()
                            .id(st.currentMsgId).status(ResponseOutputMessage.Status.COMPLETED)
                            .role(JsonValue.from("assistant"))
                            .content(List.of(ResponseOutputMessage.Content.ofOutputText(
                                ResponseOutputText.builder().text(full)
                                    .annotations(List.of()).build()))).build()))
                    .sequenceNumber(st.nextSeq())
                    .type(JsonValue.from("response.output_item.done")).build()));

            st.inTextBlock = false;
            return out;
        } catch (Exception e) {
            throw new RuntimeException("关闭 text block 失败", e);
        }
    }

    private List<String> closeFuncBlocks(StreamState st) {
        try {
            List<String> out = new ArrayList<>();
            if (!st.inFuncBlock || st.funcAccs.isEmpty()) return out;

            for (var entry : st.funcAccs.entrySet()) {
                int idx = entry.getKey();
                var acc = entry.getValue();
                String args = acc.argsBuf.length() > 0 ? acc.argsBuf.toString() : "{}";

                // 主路径：从 args 提取 custom_ns 还原 namespace
                if (acc.remapKind == 0 && st.remapCtx != null) {
                    String alias = extractCustomNs(args);
                    if (alias != null) {
                        String namespace = st.remapCtx.getNamespaceByAlias(alias);
                        if (namespace != null) {
                            acc.remapKind = 2;
                            acc.namespaceName = namespace;
                            acc.customOriginalName = acc.name;
                        }
                    }
                }
                // 剥离 custom_ns
                if (acc.remapKind == 2) {
                    args = stripCustomNs(args);
                }

                int outputIdx = st.messageOutputIndex + 1 + idx;

                if (acc.remapKind == 1) {
                    // custom_tool_call: 发送 custom_tool_call_input.delta + output_item.done
                    String customInput = extractCustomInput(tryParseJson(args));
                    String itemId = "ctc_" + acc.id;
                    String originalName = acc.customOriginalName != null ? acc.customOriginalName : acc.name;

                    // response.custom_tool_call_input.delta
                    ObjectNode inputDelta = mapper.createObjectNode();
                    inputDelta.put("type", "response.custom_tool_call_input.delta");
                    inputDelta.put("sequence_number", st.nextSeq());
                    inputDelta.put("item_id", itemId);
                    inputDelta.put("call_id", acc.id);
                    inputDelta.put("output_index", outputIdx);
                    inputDelta.put("delta", customInput);
                    out.add(mapper.writeValueAsString(inputDelta));

                    // response.output_item.done (custom_tool_call)
                    ObjectNode itemDone = mapper.createObjectNode();
                    itemDone.put("type", "response.output_item.done");
                    itemDone.put("sequence_number", st.nextSeq());
                    itemDone.put("output_index", outputIdx);
                    ObjectNode item = mapper.createObjectNode();
                    item.put("id", itemId);
                    item.put("type", "custom_tool_call");
                    item.put("status", "completed");
                    item.put("call_id", acc.id);
                    item.put("name", originalName);
                    item.put("input", customInput);
                    itemDone.set("item", item);
                    out.add(mapper.writeValueAsString(itemDone));
                    if (sessionStore != null) {
                        sessionStore.recordCallItem(st.responseId, item);
                    }
                } else {
                    // function_call: 正常发送 arguments.done + output_item.done
                    String displayName = acc.remapKind == 2
                        ? (acc.customOriginalName != null ? acc.customOriginalName : acc.name)
                        : (acc.name != null ? acc.name : "");

                    out.add(mapper.writeValueAsString(
                        ResponseFunctionCallArgumentsDoneEvent.builder()
                            .itemId(acc.id).outputIndex(outputIdx)
                            .name(displayName).arguments(args)
                            .sequenceNumber(st.nextSeq())
                            .type(JsonValue.from("response.function_call_arguments.done")).build()));

                    // response.output_item.done (function_call) - 手动构建以支持 namespace 字段
                    ObjectNode itemDone = mapper.createObjectNode();
                    itemDone.put("type", "response.output_item.done");
                    itemDone.put("sequence_number", st.nextSeq());
                    itemDone.put("output_index", outputIdx);
                    ObjectNode item = mapper.createObjectNode();
                    item.put("id", "fc_" + acc.id);
                    item.put("type", "function_call");
                    item.put("status", "completed");
                    item.put("arguments", args);
                    item.put("call_id", acc.id);
                    item.put("name", displayName);
                    if (acc.remapKind == 2 && acc.namespaceName != null) {
                        item.put("namespace", acc.namespaceName);
                    }
                    itemDone.set("item", item);
                    out.add(mapper.writeValueAsString(itemDone));
                    if (sessionStore != null) {
                        sessionStore.recordCallItem(st.responseId, item);
                    }
                }
            }
            st.inFuncBlock = false;
            return out;
        } catch (Exception e) {
            throw new RuntimeException("关闭 func blocks 失败", e);
        }
    }

    // ---- completion 事件 ----

    /**
     * 新版本：使用 StreamState 构建包含完整 output 数组和 usage 的 completion 事件。
     */
    public String completionEvent(StreamState st, UnifiedChatRequest originalRequest) {
        try {
            // 构建 output 数组
            List<ResponseOutputItem> outputs = new ArrayList<>();

            if (st.reasoningBuf.length() > 0 || st.reasoningPartAdded) {
                outputs.add(ResponseOutputItem.ofReasoning(
                    ResponseReasoningItem.builder()
                        .id(st.reasoningItemId).status(ResponseReasoningItem.Status.COMPLETED)
                        .summary(List.of(ResponseReasoningItem.Summary.builder()
                            .text(st.reasoningBuf.toString()).build()))
                        .build()));
            }

            if (st.textBuf.length() > 0) {
                outputs.add(ResponseOutputItem.ofMessage(
                    ResponseOutputMessage.builder()
                        .id(st.currentMsgId).status(ResponseOutputMessage.Status.COMPLETED)
                        .role(JsonValue.from("assistant"))
                        .content(List.of(ResponseOutputMessage.Content.ofOutputText(
                            ResponseOutputText.builder()
                                .text(st.textBuf.toString()).annotations(List.of()).build())))
                        .build()));
            }

            for (var acc : st.funcAccs.values()) {
                String args = acc.argsBuf.length() > 0 ? acc.argsBuf.toString() : "{}";

                // 主路径：从 args 提取 custom_ns 还原 namespace
                if (acc.remapKind == 0 && st.remapCtx != null) {
                    String alias = extractCustomNs(args);
                    if (alias != null) {
                        String namespace = st.remapCtx.getNamespaceByAlias(alias);
                        if (namespace != null) {
                            acc.remapKind = 2;
                            acc.namespaceName = namespace;
                            acc.customOriginalName = acc.name;
                        }
                    }
                }
                // 剥离 custom_ns
                if (acc.remapKind == 2) {
                    args = stripCustomNs(args);
                }

                if (acc.remapKind == 1) {
                    // custom_tool_call item —— 手动构建 JSON
                    String customInput = extractCustomInput(tryParseJson(args));
                    String originalName = acc.customOriginalName != null ? acc.customOriginalName : acc.name;
                    ObjectNode item = mapper.createObjectNode();
                    item.put("id", "ctc_" + acc.id);
                    item.put("type", "custom_tool_call");
                    item.put("status", "completed");
                    item.put("call_id", acc.id);
                    item.put("name", originalName);
                    item.put("input", customInput);
                    outputs.add(mapper.treeToValue(item, ResponseOutputItem.class));
                } else if (acc.remapKind == 2) {
                    // namespace function_call item —— 手动构建 JSON 以注入 namespace 字段
                    String displayName = acc.customOriginalName != null ? acc.customOriginalName : acc.name;
                    ObjectNode item = mapper.createObjectNode();
                    item.put("id", "fc_" + acc.id);
                    item.put("type", "function_call");
                    item.put("status", "completed");
                    item.put("arguments", args);
                    item.put("call_id", acc.id);
                    item.put("name", displayName);
                    if (acc.namespaceName != null) {
                        item.put("namespace", acc.namespaceName);
                    }
                    outputs.add(mapper.treeToValue(item, ResponseOutputItem.class));
                } else {
                    // 普通 function_call —— 保持原有 SDK builder 方式
                    outputs.add(ResponseOutputItem.ofFunctionCall(
                        ResponseFunctionToolCall.builder()
                            .id("fc_" + acc.id).callId(acc.id)
                            .name(acc.name != null ? acc.name : "")
                            .arguments(args)
                            .build()));
                }
            }

            var respBuilder = Response.builder()
                .id(st.responseId).createdAt((double) st.createdAt)
                .model(st.modelName).object_(JsonValue.from("response"))
                .output(outputs);

            // 默认 status=COMPLETED,流截断分类在下面覆盖
            respBuilder.status(ResponseStatus.COMPLETED);

            // usage（始终输出）
            var usageBuilder = ResponseUsage.builder()
                .inputTokens((int) st.inputTokens)
                .outputTokens((int) st.outputTokens)
                .totalTokens((int) (st.totalTokens > 0 ? st.totalTokens : st.inputTokens + st.outputTokens))
                .inputTokensDetails(ResponseUsage.InputTokensDetails.builder()
                    .cachedTokens((int) st.cachedTokens).build())
                .outputTokensDetails(ResponseUsage.OutputTokensDetails.builder()
                    .reasoningTokens((int) st.reasoningTokens).build());
            respBuilder.usage(usageBuilder.build());

            respBuilder
                .error((ResponseError) null)
                .incompleteDetails((Response.IncompleteDetails) null);

            // 流截断分类:无 finishReason 时按是否有实质性输出兜底
            // - 有输出 -> INCOMPLETE + reason=max_output_tokens(标记为不完整)
            // - 无输出 -> FAILED + error(stream_truncated)
            // - 有 finishReason -> COMPLETED(默认,无需覆盖)
            String eventType = "response.completed";
            if (!st.hasFinishReason) {
                if (st.hasSubstantiveOutput) {
                    respBuilder.status(ResponseStatus.INCOMPLETE)
                        .incompleteDetails(Response.IncompleteDetails.builder()
                            .reason(Response.IncompleteDetails.Reason.MAX_OUTPUT_TOKENS)
                            .build());
                    // 仍发 completed 事件,但 response.status=incomplete
                } else {
                    respBuilder.status(ResponseStatus.FAILED)
                        .error(ResponseError.builder()
                            .code(ResponseError.Code.of("stream_truncated"))
                            .message("上游流式响应被截断: 无输出且未发送 finish_reason")
                            .build());
                    eventType = "response.failed";
                }
            }
            respBuilder.instructions(st.instructions != null && !st.instructions.isEmpty()
                ? Response.Instructions.ofString(st.instructions) : null);
            respBuilder.metadata((Response.Metadata) null);

            // 回显请求字段（温度/topP 始终显式设置，避免 builder 校验失败）
            if (originalRequest != null && originalRequest.config() != null) {
                var cfg = originalRequest.config();
                if (cfg.temperature() != null) respBuilder.temperature(cfg.temperature().doubleValue());
                else respBuilder.temperature((Double) null);
                if (cfg.topP() != null) respBuilder.topP(cfg.topP().doubleValue());
                else respBuilder.topP((Double) null);
            } else {
                respBuilder.temperature((Double) null).topP((Double) null);
            }
            if (originalRequest != null && originalRequest.tools() != null && !originalRequest.tools().isEmpty())
                respBuilder.tools(mapToResponseTools(originalRequest.tools()));
            else respBuilder.tools(List.of());
            if (originalRequest != null && originalRequest.toolChoice() != null)
                respBuilder.toolChoice(mapToResponseToolChoice(originalRequest.toolChoice()));
            else respBuilder.toolChoice(ToolChoiceOptions.AUTO);
            if (originalRequest != null && originalRequest.config() != null && originalRequest.config().parallelToolCalls() != null)
                respBuilder.parallelToolCalls(originalRequest.config().parallelToolCalls());
            else respBuilder.parallelToolCalls(true);

            // 回显 reasoning/metadata/max_output_tokens
            if (st.reasoning != null && !st.reasoning.isEmpty()) {
                respBuilder.reasoning(mapper.convertValue(st.reasoning, com.openai.models.Reasoning.class));
            }
            if (st.metadata != null && !st.metadata.isEmpty()) {
                respBuilder.metadata(mapper.convertValue(st.metadata, Response.Metadata.class));
            }
            if (originalRequest != null && originalRequest.config() != null
                    && originalRequest.config().maxOutputTokens() != null) {
                respBuilder.maxOutputTokens(originalRequest.config().maxOutputTokens().longValue());
            }

            // 构建事件 JSON
            ObjectNode event = mapper.createObjectNode();
            event.put("type", eventType);
            event.set("response", mapper.valueToTree(respBuilder.build()));
            event.put("sequence_number", st.nextSeq());
            return mapper.writeValueAsString(event);
        } catch (Exception e) {
            throw new RuntimeException("构建 completion 事件失败", e);
        }
    }

    /**
     * 旧版 completionEvent 保持向后兼容。
     */
    public String completionEvent(String id, String modelName, long createdAt) {
        String respJson = lifecycleEventsResponseJson(id, modelName, "completed", createdAt);
        return buildLifecycleEvent("response.completed", respJson, 0);
    }

    /**
     * 流式响应中途出错时，构造一条 Responses 风格的 response.failed 事件 JSON 作为 SSE data 吐给客户端。
     * 用于 SSE 响应已 committed 后无法走 @ExceptionHandler 的场景。
     */
    public String errorStreamEvent(Throwable e) {
        int status = (e instanceof BackendApiException b) ? b.getStatusCode() : 502;
        try {
            var event = mapper.createObjectNode();
            event.put("type", "response.failed");
            var err = mapper.createObjectNode();
            err.put("code", String.valueOf(status));
            err.put("message", e.getMessage() != null ? e.getMessage() : "上游后端调用失败");
            event.set("error", err);
            return mapper.writeValueAsString(event);
        } catch (Exception ex) {
            return "{\"type\":\"response.failed\",\"error\":{\"code\":\"502\",\"message\":\"上游后端调用失败\"}}";
        }
    }

    // ---- 工具映射辅助方法 ----

    private List<Tool> mapToResponseTools(List<UnifiedTool> irTools) {
        return irTools.stream().map(t -> {
            if (t.function() != null) {
                var fnBuilder = com.openai.models.responses.FunctionTool.builder()
                    .name(t.function().name());
                if (t.function().description() != null) {
                    fnBuilder.description(t.function().description());
                }
                if (t.function().parameters() != null) {
                    fnBuilder.parameters(toFunctionToolParameters(t.function().parameters()));
                }
                return Tool.ofFunction(fnBuilder.strict(true).build());
            }
            return Tool.ofFunction(com.openai.models.responses.FunctionTool.builder().name("unknown").strict(true).build());
        }).toList();
    }

    // ---- 工具类型还原辅助方法 ----

    private String extractCustomInput(JsonNode arguments) {
        if (arguments == null) return "";
        JsonNode input = arguments.get("input");
        if (input != null && input.isTextual()) return input.asText();
        return "";
    }

    private JsonNode tryParseJson(String raw) {
        if (raw == null || raw.isEmpty()) return mapper.createObjectNode();
        try {
            return mapper.readTree(raw);
        } catch (Exception e) {
            return mapper.createObjectNode();
        }
    }

    private String extractCustomNs(String argsStr) {
        if (argsStr == null || argsStr.isEmpty() || argsStr.equals("{}")) return null;
        try {
            JsonNode args = mapper.readTree(argsStr);
            JsonNode customNs = args.get(ProxyConstants.MCP_SERVER_ROUTER_PARAM);
            return (customNs != null && customNs.isTextual()) ? customNs.asText() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private String stripCustomNs(String argsStr) {
        if (argsStr == null || argsStr.isEmpty()) return argsStr;
        try {
            ObjectNode args = (ObjectNode) mapper.readTree(argsStr);
            if (args.remove(ProxyConstants.MCP_SERVER_ROUTER_PARAM) != null) {
                return mapper.writeValueAsString(args);
            }
            return argsStr;
        } catch (Exception e) {
            return argsStr;
        }
    }

    /**
     * 将 IR 的 JsonNode parameters 转为 responses SDK 的 FunctionTool.Parameters。
     */
    private com.openai.models.responses.FunctionTool.Parameters toFunctionToolParameters(JsonNode params) {
        if (params == null) return null;
        var builder = com.openai.models.responses.FunctionTool.Parameters.builder();
        var fields = params.fields();
        while (fields.hasNext()) {
            var entry = fields.next();
            builder.putAdditionalProperty(entry.getKey(), JsonValue.fromJsonNode(entry.getValue()));
        }
        return builder.build();
    }

    private Response.ToolChoice mapToResponseToolChoice(UnifiedToolChoice tc) {
        return switch (tc) {
            case UnifiedToolChoice.None __ -> Response.ToolChoice.ofOptions(ToolChoiceOptions.NONE);
            case UnifiedToolChoice.Auto __ -> Response.ToolChoice.ofOptions(ToolChoiceOptions.AUTO);
            case UnifiedToolChoice.Required r -> Response.ToolChoice.ofFunction(
                ToolChoiceFunction.builder().name(r.functionName()).build());
            case UnifiedToolChoice.Any __ -> Response.ToolChoice.ofOptions(ToolChoiceOptions.REQUIRED);
        };
    }

    // ---- 请求解析辅助方法（基于 SDK 类型） ----

    private List<UnifiedMessage> parseSdkInput(Optional<ResponseCreateParams.Input> input,
                                                String instructions) {
        List<UnifiedMessage> messages = new ArrayList<>();

        // instructions 独立为一条 SYSTEM 消息
        if (instructions != null && !instructions.isEmpty()) {
            messages.add(UnifiedMessage.builder()
                .role(UnifiedMessage.Role.SYSTEM)
                .content(instructions)
                .build());
        }

        if (input.isEmpty()) {
            return messages;
        }

        var inp = input.get();
        if (inp.isText()) {
            messages.add(UnifiedMessage.builder()
                .role(UnifiedMessage.Role.USER)
                .content(inp.asText())
                .build());
            return messages;
        }

        if (!inp.isResponse()) return messages;

        List<String> pendingReasoning = new ArrayList<>();
        List<UnifiedToolCall> pendingToolCalls = new ArrayList<>();
        boolean reasoningMerged = false;

        for (ResponseInputItem item : inp.asResponse()) {
            if (item.isReasoning()) {
                var r = item.asReasoning();
                if (r.summary() != null && !r.summary().isEmpty()) {
                    String text = r.summary().get(0).text();
                    if (text != null && !text.isEmpty()) {
                        pendingReasoning.add(text);
                    }
                }
                continue;
            }

            if (item.isFunctionCall()) {
                var fc = item.asFunctionCall();
                String fnName = fc.name() != null ? fc.name() : "";
                String fnArgsStr = fc.arguments() != null ? fc.arguments() : "{}";
                JsonNode fnArgs;
                try { fnArgs = mapper.readTree(fnArgsStr); }
                catch (Exception __) { fnArgs = mapper.createObjectNode(); }
                String tcId = fc.callId() != null ? fc.callId() : fc.id().orElse("");
                pendingToolCalls.add(UnifiedToolCall.builder()
                    .id(tcId)
                    .type("function")
                    .function(UnifiedFunctionCall.builder()
                        .name(fnName)
                        .arguments(fnArgs)
                        .build())
                    .build());
                continue;
            }

            if (item.isCustomToolCall()) {
                var ctc = item.asCustomToolCall();
                String fnName = ctc.name();
                String rawInput = ctc.input() != null ? ctc.input() : "";
                String tcId = ctc.callId();
                // 将原始输入字符串包装为 {"input":"..."}
                ObjectNode argsObj = mapper.createObjectNode();
                argsObj.put("input", rawInput);
                pendingToolCalls.add(UnifiedToolCall.builder()
                    .id(tcId)
                    .type("function")
                    .function(UnifiedFunctionCall.builder()
                        .name(fnName)
                        .arguments(argsObj)
                        .build())
                    .build());
                continue;
            }

            // 非 reasoning / 非 function_call 的消息
            UnifiedMessage msg = null;
            if (item.isEasyInputMessage()) {
                msg = mapSdkEasyMessage(item.asEasyInputMessage());
            } else if (item.isMessage()) {
                msg = mapSdkMessage(item.asMessage());
            } else if (item.isFunctionCallOutput()) {
                msg = mapSdkFunctionCallOutput(item.asFunctionCallOutput());
            } else if (item.isCustomToolCallOutput()) {
                msg = mapSdkCustomToolCallOutput(item.asCustomToolCallOutput());
            } else if (item.isWebSearchCall() || item.isFileSearchCall()
                    || item.isComputerCall() || item.isCodeInterpreterCall()) {
                // 内置工具调用按 assistant 空消息处理，不影响消息流
                msg = UnifiedMessage.builder().role(UnifiedMessage.Role.ASSISTANT).build();
            }

            if (msg == null) continue;

            // developer -> user（非标准角色降级）
            if (msg.role() == UnifiedMessage.Role.SYSTEM && isDeveloperRole(item)) {
                msg = UnifiedMessage.builder()
                    .role(UnifiedMessage.Role.USER)
                    .content(msg.content())
                    .build();
            }

            // 合并 pending reasoning 到 assistant 消息
            if (!pendingReasoning.isEmpty()) {
                if (msg.role() == UnifiedMessage.Role.ASSISTANT && pendingToolCalls.isEmpty()) {
                    String rc = String.join("\n", pendingReasoning);
                    msg = UnifiedMessage.builder()
                        .role(msg.role())
                        .content(msg.content())
                        .parts(msg.parts())
                        .toolCalls(msg.toolCalls())
                        .toolCallId(msg.toolCallId())
                        .name(msg.name())
                        .reasoningContent(rc)
                        .build();
                    reasoningMerged = true;
                } else if (pendingToolCalls.isEmpty() && !reasoningMerged) {
                    flushReasoning(messages, pendingReasoning);
                }
            }

            // 合并 pending tool calls
            if (!pendingToolCalls.isEmpty()) {
                flushToolCalls(messages, pendingReasoning, pendingToolCalls);
            }

            messages.add(msg);

            // 非 assistant 消息标记当前轮次结束，清理 reasoning 状态
            if (msg.role() != UnifiedMessage.Role.ASSISTANT && reasoningMerged) {
                pendingReasoning.clear();
                reasoningMerged = false;
            }
        }

        // flush 残留
        if (!pendingToolCalls.isEmpty()) {
            flushToolCalls(messages, pendingReasoning, pendingToolCalls);
        }
        if (!pendingReasoning.isEmpty() && !reasoningMerged) {
            flushReasoning(messages, pendingReasoning);
        }
        if (reasoningMerged) {
            pendingReasoning.clear();
            reasoningMerged = false;
        }

        messages = normalizeToolCallMessageOrder(messages);
        return messages;
    }

    private void flushReasoning(List<UnifiedMessage> messages, List<String> pendingReasoning) {
        String rc = String.join("\n", pendingReasoning);
        messages.add(UnifiedMessage.builder()
            .role(UnifiedMessage.Role.ASSISTANT)
            .reasoningContent(rc)
            .build());
        pendingReasoning.clear();
    }

    private void flushToolCalls(List<UnifiedMessage> messages,
            List<String> pendingReasoning, List<UnifiedToolCall> pendingToolCalls) {
        String rc = !pendingReasoning.isEmpty() ? String.join("\n", pendingReasoning) : null;
        if (!pendingReasoning.isEmpty()) pendingReasoning.clear();
        messages.add(UnifiedMessage.builder()
            .role(UnifiedMessage.Role.ASSISTANT)
            .toolCalls(new ArrayList<>(pendingToolCalls))
            .reasoningContent(rc)
            .build());
        pendingToolCalls.clear();
    }

    private static boolean isSystemRole(String role) {
        return "system".equals(role);
    }

    private static boolean isDeveloperRole(ResponseInputItem item) {
        if (item.isEasyInputMessage()) {
            return "developer".equals(item.asEasyInputMessage().role().asString());
        }
        if (item.isMessage()) {
            return "developer".equals(item.asMessage().role().asString());
        }
        return false;
    }

    private UnifiedMessage mapSdkEasyMessage(EasyInputMessage msg) {
        String roleStr = msg.role().asString();
        UnifiedMessage.Role role = switch (roleStr) {
            case "system" -> UnifiedMessage.Role.SYSTEM;
            case "developer" -> UnifiedMessage.Role.USER;
            case "user" -> UnifiedMessage.Role.USER;
            case "assistant" -> UnifiedMessage.Role.ASSISTANT;
            default -> UnifiedMessage.Role.USER;
        };
        String content = null;
        List<UnifiedPart> parts = null;
        var cnt = msg.content();
        if (cnt.isTextInput()) {
            content = cnt.asTextInput();
        } else if (cnt.isResponseInputMessageContentList()) {
            var contentList = cnt.asResponseInputMessageContentList();
            if (hasImageContent(contentList)) {
                parts = toUnifiedParts(contentList);
            } else {
                content = extractTextFromContent(contentList);
            }
        }
        return UnifiedMessage.builder()
            .role(role)
            .content(content)
            .parts(parts)
            .build();
    }

    private UnifiedMessage mapSdkMessage(ResponseInputItem.Message msg) {
        String roleStr = msg.role().asString();
        UnifiedMessage.Role role = switch (roleStr) {
            case "system" -> UnifiedMessage.Role.SYSTEM;
            case "developer" -> UnifiedMessage.Role.USER;
            case "user" -> UnifiedMessage.Role.USER;
            case "assistant" -> UnifiedMessage.Role.ASSISTANT;
            case "tool" -> UnifiedMessage.Role.TOOL;
            default -> UnifiedMessage.Role.USER;
        };
        var contentList = msg.content();
        String content = null;
        List<UnifiedPart> parts = null;
        if (hasImageContent(contentList)) {
            parts = toUnifiedParts(contentList);
        } else {
            content = extractTextFromContent(contentList);
        }
        return UnifiedMessage.builder()
            .role(role)
            .content(content)
            .parts(parts)
            .build();
    }

    private String extractTextFromContent(List<ResponseInputContent> contentList) {
        if (contentList.isEmpty()) return null;
        // 如果有图片 block，返回 null 表示需要结构化处理
        if (hasImageContent(contentList)) return null;
        var first = contentList.get(0);
        if (first.isInputText()) return first.asInputText().text();
        // 多模态或复杂内容 → 序列化
        try { return mapper.writeValueAsString(contentList); }
        catch (Exception e) { return contentList.toString(); }
    }

    /** 检测 content list 是否包含图片 */
    private static boolean hasImageContent(List<ResponseInputContent> contentList) {
        for (var c : contentList) {
            if (c.isInputImage()) return true;
        }
        return false;
    }

    /** 将 SDK content list 转为 List<UnifiedPart> */
    private List<UnifiedPart> toUnifiedParts(List<ResponseInputContent> contentList) {
        List<UnifiedPart> parts = new ArrayList<>();
        for (var c : contentList) {
            if (c.isInputText()) {
                parts.add(new UnifiedPart.TextPart(c.asInputText().text()));
            } else if (c.isInputImage()) {
                var img = c.asInputImage();
                ObjectNode imageData = mapper.createObjectNode();
                img.imageUrl().ifPresent(url -> imageData.put("url", url));
                var detail = img.detail();
                if (detail != null) {
                    String ds = detail.asString();
                    if (ds != null && !ds.isEmpty()) {
                        imageData.put("detail", ds);
                    }
                }
                parts.add(new UnifiedPart.ImagePart(imageData));
            }
        }
        return parts;
    }

    private UnifiedMessage mapSdkFunctionCall(ResponseFunctionToolCall fc) {
        String fnName = fc.name() != null ? fc.name() : "";
        String fnArgsStr = fc.arguments() != null ? fc.arguments() : "{}";
        JsonNode fnArgs;
        try { fnArgs = mapper.readTree(fnArgsStr); }
        catch (Exception __) { fnArgs = mapper.createObjectNode(); }
        // 使用 callId 作为 tool call ID，确保与 function_call_output 的 call_id 匹配
        String tcId = fc.callId() != null ? fc.callId()
            : fc.id().orElse("");
        var tc = UnifiedToolCall.builder()
            .id(tcId)
            .type("function")
            .function(UnifiedFunctionCall.builder()
                .name(fnName)
                .arguments(fnArgs)
                .build())
            .build();
        return UnifiedMessage.builder()
            .role(UnifiedMessage.Role.ASSISTANT)
            .toolCalls(List.of(tc))
            .build();
    }

    private UnifiedMessage mapSdkFunctionCallOutput(
            ResponseInputItem.FunctionCallOutput fco) {
        String callId = fco.callId();
        String output = "";
        var out = fco.output();
        if (out.isString()) {
            output = out.asString();
            if (output.startsWith("unsupported call:")) {
                output = output +  ".The provided tool name might be incomplete or missing a prefix. Please use the exact, full tool name as defined in the schema.";
            }
        } else if (out.isResponseFunctionCallOutputItemList()) {
            try { output = mapper.writeValueAsString(out.asResponseFunctionCallOutputItemList()); }
            catch (Exception e) { output = "{}"; }
        }
        return UnifiedMessage.builder()
            .role(UnifiedMessage.Role.TOOL)
            .content(output)
            .toolCallId(callId)
            .build();
    }

    private UnifiedMessage mapSdkCustomToolCallOutput(
            ResponseCustomToolCallOutput ctco) {
        String callId = ctco.callId();
        String output = "";
        var out = ctco.output();
        if (out.isString()) {
            output = out.asString();
        } else if (out.isContentList()) {
            try { output = mapper.writeValueAsString(out.asContentList()); }
            catch (Exception e) { output = "{}"; }
        }
        return UnifiedMessage.builder()
            .role(UnifiedMessage.Role.TOOL)
            .content(output)
            .toolCallId(callId)
            .build();
    }

    private List<UnifiedTool> parseSdkTools(Optional<List<Tool>> toolsOpt) {
        return parseSdkTools(toolsOpt, null);
    }

    private List<UnifiedTool> parseSdkTools(Optional<List<Tool>> toolsOpt, ToolRemapContext ctx) {
        if (toolsOpt.isEmpty()) return null;
        List<UnifiedTool> result = new ArrayList<>();
        for (Tool t : toolsOpt.get()) {
            if (t.isFunction()) {
                var fn = t.asFunction();
                JsonNode fnParams = mapper.convertValue(
                    fn.parameters().orElse(null), JsonNode.class);
                result.add(UnifiedTool.builder()
                    .type("function")
                    .function(UnifiedFunctionDefinition.builder()
                        .name(fn.name())
                        .description(fn.description().orElse(null))
                        .parameters(fnParams)
                        .build())
                    .build());
            } else if (t.isCustom()) {
                var ct = t.asCustom();
                String name = ct.name();
                String desc = ct.description().orElse(null);
                if ("apply_patch".equals(name)) {
                    result.addAll(CodexToolCompat.applyPatchTool());
                    if (ctx != null) {
                        ctx.putCustom("apply_patch", "apply_patch", ToolRemapContext.Kind.APPLY_PATCH);
                    }
                } else {
                    result.addAll(CodexToolCompat.expandCustom(name, desc));
                    if (ctx != null) {
                        // P3-14: 保留原始 description 到 CustomSpec,供响应侧还原
                        ctx.putCustom(name, name, ToolRemapContext.Kind.RAW, desc);
                    }
                }
            } else if (t.isNamespace()) {
                var nt = t.asNamespace();
                List<UnifiedTool> children = new ArrayList<>();
                for (var child : nt.tools()) {
                    if (child.isFunction()) {
                        var fn = child.asFunction();
                        JsonNode fnParams = fn._parameters().isMissing()
                            ? null
                            : fn._parameters().convert(JsonNode.class);
                        children.add(UnifiedTool.builder()
                            .type("function")
                            .function(UnifiedFunctionDefinition.builder()
                                .name(fn.name())
                                .description(fn.description().orElse(null))
                                .parameters(fnParams)
                                .build())
                            .build());
                    }
                }
                String nsAlias = ctx != null ? ctx.generateAlias(nt.name()) : null;
                result.addAll(CodexToolCompat.expandNamespace(
                    nt.name(), nt.description(), children, nsAlias));
                if (ctx != null) {
                    String cleanNs = nt.name().replace("__", "_").replaceAll("_+$", "");
                    for (UnifiedTool child : children) {
                        if (child == null || child.function() == null || child.function().name() == null) continue;
                        // P3-13: 用 computeFlatName 保证与 expandNamespace 内部计算一致(含长度截断)
                        String flatName = CodexToolCompat.computeFlatName(cleanNs, child.function().name());
                        ctx.putNamespace(flatName, child.function().name(), nt.name(), nsAlias);
                    }
                }
            }
            // 其他类型（web_search 等）跳过
        }
        return result.isEmpty() ? null : result;
    }

    private UnifiedToolChoice parseSdkToolChoice(
            Optional<ResponseCreateParams.ToolChoice> tcOpt) {
        if (tcOpt.isEmpty()) return null;
        var tc = tcOpt.get();
        if (tc.isOptions()) {
            return switch (tc.asOptions().asString()) {
                case "none" -> UnifiedToolChoice.None.builder().build();
                default -> UnifiedToolChoice.Auto.builder().build();
            };
        }
        if (tc.isFunction()) {
            String fnName = tc.asFunction().name() != null
                ? tc.asFunction().name() : "";
            return UnifiedToolChoice.Required.builder()
                .functionName(fnName)
                .build();
        }
        return UnifiedToolChoice.Auto.builder().build();
    }

    /** SDK 无法识别的 input item，通过原始 JSON 兜底解析 */
    private UnifiedMessage mapSdkUnknownItem(ResponseInputItem item) {
        // ResponseInputItem 的 _json 未公开，通过其他访问器获取 raw 数据
        // 尝试从 _additionalProperties 和已知字段重建
        try {
            String raw = mapper.writeValueAsString(item);
            JsonNode node = mapper.readTree(raw);
            String role = node.has("role") ? node.get("role").asText() : "user";
            String content = null;
            if (node.has("content")) {
                JsonNode c = node.get("content");
                if (c.isTextual()) content = c.asText();
                else if (c.isArray()) content = c.toString();
            }
            String name = node.has("name") ? node.get("name").asText() : null;
            String toolCallId = node.has("call_id") ? node.get("call_id").asText() : null;

            List<UnifiedToolCall> toolCalls = null;
            if (node.has("tool_calls")) {
                // 利用旧解析逻辑
                toolCalls = parseSdkToolCallsFromNode(node.get("tool_calls"));
            }

            return UnifiedMessage.builder()
                .role(switch (role) {
                    case "system" -> UnifiedMessage.Role.SYSTEM;
                    case "user" -> UnifiedMessage.Role.USER;
                    case "assistant" -> UnifiedMessage.Role.ASSISTANT;
                    case "tool" -> UnifiedMessage.Role.TOOL;
                    default -> UnifiedMessage.Role.USER;
                })
                .content(content)
                .toolCalls(toolCalls)
                .toolCallId(toolCallId)
                .name(name)
                .build();
        } catch (Exception e) {
            return UnifiedMessage.builder()
                .role(UnifiedMessage.Role.USER)
                .content("")
                .build();
        }
    }

    private List<UnifiedToolCall> parseSdkToolCallsFromNode(JsonNode tcNode) {
        if (tcNode == null || !tcNode.isArray()) return null;
        // 复用旧解析逻辑中的 tool_calls 解析
        List<UnifiedToolCall> result = new ArrayList<>();
        for (JsonNode tc : tcNode) {
            String id = tc.has("id") ? tc.get("id").asText() : null;
            JsonNode fn = tc.get("function");
            String fnName = null;
            JsonNode fnArgs = null;
            if (fn != null) {
                fnName = fn.has("name") ? fn.get("name").asText() : null;
                if (fn.has("arguments")) {
                    JsonNode a = fn.get("arguments");
                    if (a.isTextual()) {
                        try { fnArgs = mapper.readTree(a.asText()); }
                        catch (Exception __) { fnArgs = mapper.createObjectNode(); }
                    } else fnArgs = a;
                }
            }
            result.add(UnifiedToolCall.builder()
                .id(id)
                .type("function")
                .function(UnifiedFunctionCall.builder()
                    .name(fnName)
                    .arguments(fnArgs)
                    .build())
                .build());
        }
        return result.isEmpty() ? null : result;
    }

    // ---- 生命周期事件 ----

    /** 构建生命周期事件 JSON（带 sequenceNumber） */
    private String buildLifecycleEvent(String type, String responseJson, int sequenceNumber) {
        try {
            ObjectNode event = mapper.createObjectNode();
            event.put("type", type);
            event.set("response", mapper.readTree(responseJson));
            event.put("sequence_number", sequenceNumber);
            return mapper.writeValueAsString(event);
        } catch (Exception e) {
            throw new RuntimeException("构建生命周期事件失败", e);
        }
    }

    /** 旧版 buildLifecycleEvent 保持向后兼容 */
    private String buildLifecycleEvent(String type, String responseJson) {
        return buildLifecycleEvent(type, responseJson, 0);
    }

    /** 从 ResponsesModel 三种变体中提取模型名字符串 */
    private static String extractModelName(ResponsesModel m) {
        if (m.isString()) return m.asString();
        if (m.isChat()) return m.asChat().toString();
        if (m.isOnly()) return m.asOnly().toString();
        return m.toString();
    }

    /** 序列化生命周期事件的 response 对象（仅含必要字段，不触发 SDK 校验） */
    private String lifecycleEventsResponseJson(String id, String modelName, String status, long createdAt, String instructions) {
        try {
            ObjectNode r = mapper.createObjectNode();
            r.put("id", id);
            r.put("object", "response");
            r.put("model", modelName);
            r.put("status", status);
            r.put("created_at", createdAt);
            r.put("background", false);
            r.putNull("error");
            r.put("instructions", instructions != null ? instructions : "");
            return mapper.writeValueAsString(r);
        } catch (Exception e) {
            throw new RuntimeException("构建 Response JSON 失败", e);
        }
    }

    /** 旧版兼容（无 instructions） */
    private String lifecycleEventsResponseJson(String id, String modelName, String status, long createdAt) {
        return lifecycleEventsResponseJson(id, modelName, status, createdAt, null);
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
