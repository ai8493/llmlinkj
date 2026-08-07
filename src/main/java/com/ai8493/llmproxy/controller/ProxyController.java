package com.ai8493.llmproxy.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ai8493.llmproxy.adapter.anthropic.AnthropicProtocolAdapter;
import com.ai8493.llmproxy.adapter.gemini.GeminiProtocolAdapter;
import com.ai8493.llmproxy.adapter.gemini.GeminiRequestContext;
import com.ai8493.llmproxy.adapter.openai.OpenAiProtocolAdapter;
import com.ai8493.llmproxy.adapter.openai.ParseResult;
import com.ai8493.llmproxy.adapter.openai.ResponsesProtocolAdapter;
import com.ai8493.llmproxy.model.UnifiedChatRequest;
import com.ai8493.llmproxy.model.UnifiedChatResponse;
import com.ai8493.llmproxy.orchestrator.ProxyOrchestrator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.DisconnectedClientHelper;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;

@RestController
public class ProxyController {

    private static final Logger log = LoggerFactory.getLogger(ProxyController.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final ProxyOrchestrator orchestrator;
    private final OpenAiProtocolAdapter openaiAdapter;
    private final GeminiProtocolAdapter geminiAdapter;
    private final ResponsesProtocolAdapter responsesAdapter;
    private final AnthropicProtocolAdapter anthropicAdapter;

    public ProxyController(ProxyOrchestrator orchestrator,
                            OpenAiProtocolAdapter openaiAdapter,
                            GeminiProtocolAdapter geminiAdapter,
                            ResponsesProtocolAdapter responsesAdapter,
                            AnthropicProtocolAdapter anthropicAdapter) {
        this.orchestrator = orchestrator;
        this.openaiAdapter = openaiAdapter;
        this.geminiAdapter = geminiAdapter;
        this.responsesAdapter = responsesAdapter;
        this.anthropicAdapter = anthropicAdapter;
    }

    // ===== OpenAI endpoints =====

    @PostMapping(value = "/v1/chat/completions",
                 consumes = MediaType.APPLICATION_JSON_VALUE,
                 produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<byte[]> openaiNonStream(@RequestBody byte[] rawBody) {
        try {
            UnifiedChatRequest uReq = openaiAdapter.toUnifiedRequest(rawBody, null);
            UnifiedChatResponse uResp = orchestrator.handle(uReq, openaiAdapter.protocolName());
            return Mono.just(openaiAdapter.fromUnifiedResponse(uResp));
        } catch (Exception e) {
            return Mono.error(e);
        }
    }

    @PostMapping(value = "/v1/chat/completions",
                 produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> openaiStream(@RequestBody byte[] rawBody) {
        try {
            UnifiedChatRequest uReq = openaiAdapter.toUnifiedRequest(rawBody, null);
            return orchestrator.handleStream(uReq, openaiAdapter.protocolName())
                .doOnNext(ir -> log.debug("客户端 SSE IR 块: {}", ir))
                .map(ir -> ServerSentEvent.<String>builder()
                    .data(" " + openaiAdapter.fromUnifiedStreamChunk(ir))
                    .build())
                .doOnNext(sse -> log.debug("客户端 SSE 行: {}", sse.data()))
                .onErrorResume(e -> {
                    if (isClientDisconnected(e)) {
                        log.warn("SSE 流式传输中客户端断开: {}", e.getMessage());
                        return Flux.empty();
                    }
                    log.warn("SSE 流式传输中异常，转成 error 事件吐给客户端: {}", e.getMessage());
                    return Flux.just(ServerSentEvent.<String>builder()
                        .data(openaiAdapter.errorStreamEvent(e))
                        .build());
                });
        } catch (Exception e) {
            return Flux.error(e);
        }
    }

    @GetMapping(value = "/v1/models", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<String> openaiModels() {
        log.debug("GET /v1/models: 查询模型列表");
        return Mono.fromCallable(() -> {
            var models = orchestrator.listModels(openaiAdapter.protocolName());
            log.debug("GET /v1/models: 获取到 {} 个模型", models.size());
            var data = models.stream()
                .map(m -> new ModelEntry(m.id(), "model",
                    m.created() != null ? m.created() : 0,
                    m.ownedBy() != null ? m.ownedBy() : ""))
                .toList();
            String json = objectMapper.writeValueAsString(new ModelsResponse("list", data));
            if (log.isDebugEnabled()) {
                log.debug("GET /v1/models 响应: {}", json);
            }
            return json;
        }).doOnError(e -> log.warn("GET /v1/models 失败: {}", e.getMessage(), e));
    }

    // ===== Responses API 端点 =====

    @PostMapping(value = "/v1/responses",
                 consumes = MediaType.APPLICATION_JSON_VALUE,
                 produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<byte[]> responsesNonStream(@RequestBody byte[] rawBody) {
        try {
            ParseResult pr = responsesAdapter.parseRequest(rawBody);
            UnifiedChatResponse uResp = orchestrator.handle(pr.request(), responsesAdapter.protocolName());
            return Mono.just(responsesAdapter.fromUnifiedResponse(uResp, pr.request(), pr.toolRemapContext()));
        } catch (Exception e) {
            return Mono.error(e);
        }
    }

    @PostMapping(value = "/v1/responses",
                 produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> responsesStream(@RequestBody byte[] rawBody) {
        try {
            ParseResult pr = responsesAdapter.parseRequest(rawBody);
            ResponsesProtocolAdapter.StreamState st = new ResponsesProtocolAdapter.StreamState(pr.toolRemapContext());
            // 从 raw JSON 提取回显信息
            try {
                ObjectMapper mapper = new ObjectMapper();
                JsonNode root = mapper.readTree(rawBody);
                if (root.has("instructions")) {
                    st.instructions = root.get("instructions").asText();
                }
                if (root.has("reasoning")) {
                    st.reasoning = root.get("reasoning");
                }
                if (root.has("metadata")) {
                    st.metadata = root.get("metadata");
                }
            } catch (Exception e) {
                // 忽略解析失败
            }
            return orchestrator.handleStream(pr.request(), responsesAdapter.protocolName())
                .doOnNext(ir -> log.debug("Responses IR 块: {}", ir))
                .flatMapIterable(chunk -> responsesAdapter.toStreamEvents(chunk, st))
                .doOnNext(event -> log.debug("Responses SSE 事件: {}", event))
                .map(json -> ServerSentEvent.<String>builder().data(json).build())
                .concatWith(Mono.fromCallable(() -> {
                    String event = responsesAdapter.completionEvent(st, pr.request());
                    log.debug("Responses SSE 事件: {}", event);
                    return ServerSentEvent.<String>builder().data(event).build();
                }))
                .onErrorResume(e -> {
                    if (isClientDisconnected(e)) {
                        log.warn("SSE 流式传输中客户端断开: {}", e.getMessage());
                        return Flux.empty();
                    }
                    log.warn("SSE 流式传输中异常，转成 error 事件吐给客户端: {}", e.getMessage());
                    return Flux.just(ServerSentEvent.<String>builder()
                        .data(responsesAdapter.errorStreamEvent(e))
                        .build());
                });
        } catch (Exception e) {
            return Flux.error(e);
        }
    }

    // ===== Anthropic endpoints =====

    @PostMapping(value = "/v1/messages",
                 consumes = MediaType.APPLICATION_JSON_VALUE,
                 produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> anthropicNonStream(@RequestBody byte[] rawBody,
                                                       @RequestHeader Map<String, String> headers) {
        try {
            UnifiedChatRequest uReq = anthropicAdapter.toUnifiedRequest(rawBody, headers);
            UnifiedChatResponse uResp = orchestrator.handle(uReq, anthropicAdapter.protocolName());
            return ResponseEntity.ok(anthropicAdapter.fromUnifiedResponse(uResp));
        } catch (Exception e) {
            log.warn("Anthropic 非流式调用失败: {}", e.getMessage(), e);
            int status = anthropicAdapter.errorStatusCode(e);
            return ResponseEntity.status(status).body(anthropicAdapter.errorResponse(e));
        }
    }

    @PostMapping(value = "/v1/messages",
                 produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> anthropicStream(@RequestBody byte[] rawBody,
                                                          @RequestHeader Map<String, String> headers) {
        UnifiedChatRequest uReq;
        try {
            uReq = anthropicAdapter.toUnifiedRequest(rawBody, headers);
        } catch (Exception e) {
            log.warn("Anthropic 流式请求解析失败: {}", e.getMessage(), e);
            return Flux.just(ServerSentEvent.<String>builder()
                .data(anthropicAdapter.errorStreamEvent(e))
                .build());
        }
        AnthropicProtocolAdapter.StreamState state = new AnthropicProtocolAdapter.StreamState();
        return orchestrator.handleStream(uReq, anthropicAdapter.protocolName())
            .doOnNext(ir -> log.debug("Anthropic SSE IR 块: {}", ir))
            .flatMapIterable(chunk -> anthropicAdapter.toStreamEvents(chunk, state))
            .doOnNext(event -> log.debug("Anthropic SSE 事件: {}", event))
            .map(json -> ServerSentEvent.<String>builder().data(json).build())
            .concatWith(Flux.defer(() -> Flux.fromIterable(anthropicAdapter.finalizeStream(state))
                .map(json -> ServerSentEvent.<String>builder().data(json).build())))
            .onErrorResume(e -> {
                if (isClientDisconnected(e)) {
                    log.warn("SSE 流式传输中客户端断开: {}", e.getMessage());
                    return Flux.empty();
                }
                log.warn("SSE 流式传输中异常，转成 error 事件吐给客户端: {}", e.getMessage());
                return Flux.just(ServerSentEvent.<String>builder()
                    .data(anthropicAdapter.errorStreamEvent(e))
                    .build());
            });
    }

    @GetMapping(value = "/v1/models",
                headers = "anthropic-version",
                produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<String> anthropicModels() {
        log.debug("GET /v1/models (Anthropic): 查询模型列表");
        return Mono.fromCallable(() -> {
            var models = orchestrator.listModels(anthropicAdapter.protocolName());
            log.debug("GET /v1/models (Anthropic): 获取到 {} 个模型", models.size());
            var root = objectMapper.createObjectNode();
            var data = objectMapper.createArrayNode();
            for (var m : models) {
                var obj = objectMapper.createObjectNode();
                obj.put("id", m.id());
                obj.put("type", "model");
                obj.put("display_name", m.id());
                obj.put("created_at", "2025-01-01T00:00:00Z");
                data.add(obj);
            }
            root.set("data", data);
            root.put("has_more", false);
            if (!models.isEmpty()) {
                root.put("first_id", models.get(0).id());
                root.put("last_id", models.get(models.size() - 1).id());
            }
            String json = objectMapper.writeValueAsString(root);
            if (log.isDebugEnabled()) {
                log.debug("GET /v1/models (Anthropic) 响应: {}", json);
            }
            return json;
        }).doOnError(e -> log.warn("GET /v1/models (Anthropic) 失败: {}", e.getMessage(), e));
    }

    // ===== Gemini endpoints =====

    @PostMapping(value = "/v1beta/models/{model}:generateContent",
                 consumes = MediaType.APPLICATION_JSON_VALUE,
                 produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<byte[]> geminiNonStream(@PathVariable String model,
                                         @RequestBody byte[] rawBody,
                                         @RequestHeader Map<String, String> headers) {
        try {
            String sessionKey = geminiAdapter.extractSessionKeyForController(headers, rawBody);
            GeminiRequestContext ctx = new GeminiRequestContext(sessionKey);
            UnifiedChatRequest uReq = geminiAdapter.toUnifiedRequest(rawBody, headers);
            if (uReq.model() == null) {
                uReq = UnifiedChatRequest.builder()
                    .model(model)
                    .messages(uReq.messages())
                    .config(uReq.config())
                    .tools(uReq.tools())
                    .toolChoice(uReq.toolChoice())
                    .stream(uReq.stream())
                    .anthropic(uReq.anthropic())
                    .openai(uReq.openai())
                    .gemini(uReq.gemini())
                    .build();
            }
            UnifiedChatResponse uResp = orchestrator.handle(uReq, geminiAdapter.protocolName());
            return Mono.just(geminiAdapter.fromUnifiedResponse(uResp, ctx));
        } catch (Exception e) {
            return Mono.error(e);
        }
    }

    @PostMapping(value = "/v1beta/models/{model}:streamGenerateContent",
                 consumes = MediaType.APPLICATION_JSON_VALUE,
                 produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> geminiStream(@PathVariable String model,
                                       @RequestBody byte[] rawBody,
                                       @RequestHeader Map<String, String> headers) {
        try {
            String sessionKey = geminiAdapter.extractSessionKeyForController(headers, rawBody);
            GeminiRequestContext ctx = new GeminiRequestContext(sessionKey);
            UnifiedChatRequest uReq = geminiAdapter.toUnifiedRequest(rawBody, headers);
            String actualModel = uReq.model() != null ? uReq.model() : model;
            uReq = UnifiedChatRequest.builder()
                .model(actualModel)
                .messages(uReq.messages())
                .config(uReq.config())
                .tools(uReq.tools())
                .toolChoice(uReq.toolChoice())
                .stream(true)
                .anthropic(uReq.anthropic())
                .openai(uReq.openai())
                .gemini(uReq.gemini())
                .build();
            return orchestrator.handleStream(uReq, geminiAdapter.protocolName())
                .doOnNext(ir -> log.debug("客户端 SSE IR 块: {}", ir))
                .map(ir -> ServerSentEvent.<String>builder()
                    .data(" " + geminiAdapter.fromUnifiedStreamChunk(ir, ctx))
                    .build())
                .doOnNext(sse -> log.debug("客户端 SSE 行: {}", sse.data()))
                .onErrorResume(e -> {
                    if (isClientDisconnected(e)) {
                        log.warn("SSE 流式传输中客户端断开: {}", e.getMessage());
                        return Flux.empty();
                    }
                    log.warn("SSE 流式传输中异常，转成 error 事件吐给客户端: {}", e.getMessage());
                    return Flux.just(ServerSentEvent.<String>builder()
                        .data(geminiAdapter.errorStreamEvent(e))
                        .build());
                });
        } catch (Exception e) {
            return Flux.error(e);
        }
    }

    @GetMapping(value = "/v1beta/models", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<String> geminiModels() {
        log.debug("GET /v1beta/models: 查询模型列表");
        return Mono.fromCallable(() -> {
            var models = orchestrator.listModels(geminiAdapter.protocolName());
            log.debug("GET /v1beta/models: 获取到 {} 个模型", models.size());
            var data = models.stream()
                .map(m -> new GeminiModelEntry("models/" + m.id(), m.id(), "", "", 0, 0,
                    java.util.List.of("generateContent")))
                .toList();
            String json = objectMapper.writeValueAsString(new GeminiModelsResponse(data));
            if (log.isDebugEnabled()) {
                log.debug("GET /v1beta/models 响应: {}", json);
            }
            return json;
        }).doOnError(e -> log.warn("GET /v1beta/models 失败: {}", e.getMessage(), e));
    }

    /**
     * 判断异常是否为客户端断开连接导致，用于 SSE 流式传输中静默处理。
     */
    private boolean isClientDisconnected(Throwable ex) {
        if (DisconnectedClientHelper.isClientDisconnectedException(ex)) {
            return true;
        }
        // 补充中文 Windows 系统的连接中断错误消息
        String msg = ex.getMessage();
        return msg != null && (msg.contains("连接中止") || msg.contains("连接重置")
            || msg.contains("远程主机强迫关闭") || msg.contains("broken pipe"));
    }

    // ===== Models 响应 DTO =====

    private record ModelsResponse(String object, java.util.List<ModelEntry> data) {}
    private record ModelEntry(String id, String object, long created, String ownedBy) {}
    private record GeminiModelsResponse(java.util.List<GeminiModelEntry> models) {}
    private record GeminiModelEntry(String name, String displayName, String description,
        String version, int inputTokenLimit, int outputTokenLimit,
        java.util.List<String> supportedActions) {}
}
