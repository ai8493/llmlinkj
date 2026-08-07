package com.ai8493.llmproxy.adapter.anthropic;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.http.StreamResponse;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.RawMessageStreamEvent;
import com.ai8493.llmproxy.adapter.BackendAdapter;
import com.ai8493.llmproxy.client.BackendClientFactory;
import com.ai8493.llmproxy.config.BackendConfig;
import com.ai8493.llmproxy.exception.BackendApiException;
import com.ai8493.llmproxy.model.ModelInfo;
import com.ai8493.llmproxy.model.UnifiedChatRequest;
import com.ai8493.llmproxy.model.UnifiedChatResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.List;

public class AnthropicBackendAdapter implements BackendAdapter {

    private static final Logger log = LoggerFactory.getLogger(AnthropicBackendAdapter.class);

    private final String backendName;
    private AnthropicClient client;
    private String defaultModel;
    private long defaultMaxTokens = 4096L;

    public AnthropicBackendAdapter(String backendName) {
        this.backendName = backendName;
    }

    @Override
    public String backendName() { return backendName; }

    @Override
    public void init(BackendConfig config) {
        this.client = BackendClientFactory.createAnthropicClient(config);
        this.defaultModel = config.defaultModel();
        if (config.defaultMaxTokens() != null && config.defaultMaxTokens() > 0) {
            this.defaultMaxTokens = config.defaultMaxTokens();
        }
    }

    @Override
    public UnifiedChatResponse call(UnifiedChatRequest request) {
        try {
            var reqConverter = new AnthropicRequestConverter(defaultMaxTokens);
            MessageCreateParams params = reqConverter.convert(request);
            log.debug("{} 后端请求: model={} messages={}", backendName,
                params.model().asString(), params.messages().size());

            // logRequestParams(backendName, params, "后端请求");

            var result = client.messages().create(params);

            log.debug("{} 后端响应: id={} model={} inputTokens={} outputTokens={}", backendName,
                result.id(), result.model().asString(),
                result.usage().inputTokens(), result.usage().outputTokens());
            if (log.isTraceEnabled()) {
                log.trace("{} 后端响应体: {}", backendName, result.toString());
            }
            var respConverter = new AnthropicResponseConverter();
            return respConverter.convert(result);
        } catch (AnthropicServiceException e) {
            log.error("{} 后端请求失败: status={} body={}", backendName,
                e.statusCode(), e.body());
            throw new BackendApiException(backendName, e.statusCode(),
                "Anthropic API 调用失败: " + e.getMessage(), String.valueOf(e.body()));
        } catch (Exception e) {
            log.error("{} 后端请求异常: {}", backendName, e.getMessage(), e);
            throw new BackendApiException(backendName, 502,
                "Anthropic API 调用失败: " + e.getMessage());
        }
    }

    @Override
    public Flux<UnifiedChatResponse> stream(UnifiedChatRequest request) {
        return Flux.<UnifiedChatResponse>create(sink -> {
            try {
                var reqConverter = new AnthropicRequestConverter(defaultMaxTokens);
                MessageCreateParams params = reqConverter.convert(request);

                // 分字段打印，避免 SDK toString() 截断导致看不到 tools
                int toolCount = params.tools().map(List::size).orElse(0);
                List<String> toolNames = params.tools().orElse(List.of()).stream()
                    .map(tu -> {
                        if (tu.isTool()) {
                            var t = tu.asTool();
                            var reqs = t.inputSchema().required().orElse(List.of());
                            return t.name() + "(required=" + reqs + ")";
                        }
                        return tu.toString();
                    })
                    .toList();
                String sysPrompt = params.system()
                    .map(s -> s.isString() ? s.asString() : s.toString())
                    .orElse("(无)");
                log.debug("{} 后端流式请求: model={} messages={} systemLen={} tools={} {}",
                    backendName, params.model().asString(), params.messages().size(), sysPrompt.length(), toolCount, toolNames);

                // logRequestParams(backendName, params, "后端流式请求");

                var respConverter = new AnthropicStreamingResponseConverter();
                try (StreamResponse<RawMessageStreamEvent> streamResponse =
                        client.messages().createStreaming(params)) {
                    streamResponse.stream().forEach(event -> {
                        if (sink.isCancelled()) return;

                        // 记录所有原始事件类型（无论是否产生输出），便于排查空响应
                        String eventType = event.isMessageStart() ? "message_start"
                            : event.isContentBlockStart() ? "content_block_start"
                            : event.isContentBlockDelta() ? "content_block_delta"
                            : event.isContentBlockStop() ? "content_block_stop"
                            : event.isMessageDelta() ? "message_delta"
                            : event.isMessageStop() ? "message_stop"
                            : "unknown";
                        log.debug("{} 后端原始事件: type={}", backendName, eventType);

                        // inputJson delta 需单独打印内容，排查工具参数是否被静默丢失
                        if (event.isContentBlockDelta() && event.asContentBlockDelta().delta().isInputJson()) {
                            var delta = event.asContentBlockDelta();
                            log.debug("{} 后端 inputJson delta: index={} json={}", backendName,
                                delta.index(), delta.delta().asInputJson().partialJson());
                        }

                        // messageDelta 不产生 choices，但携带 usage，需单独打印
                        if (event.isMessageDelta()) {
                            var md = event.asMessageDelta();
                            if (md.usage() != null) {
                                var u = md.usage();
                                log.debug("{} 后端流式 usage: inputTokens={} outputTokens={} cachedRead={} cachedCreation={}",
                                    backendName,
                                    u.inputTokens().isPresent() ? u.inputTokens().get() : 0,
                                    u.outputTokens(),
                                    u.cacheReadInputTokens().isPresent() ? u.cacheReadInputTokens().get() : 0,
                                    u.cacheCreationInputTokens().isPresent() ? u.cacheCreationInputTokens().get() : 0);
                            }
                        }

                        UnifiedChatResponse uChunk = respConverter.convertEvent(event);
                        if (uChunk.choices() != null && !uChunk.choices().isEmpty()
                                || uChunk.usage() != null) {
                            var choice = uChunk.choices() != null && !uChunk.choices().isEmpty()
                                ? uChunk.choices().get(0) : null;
                            log.debug("{} 后端原始流式事件: id={} finishReason={} contentLen={} reasoningLen={} toolCalls={} usage={}",
                                backendName, uChunk.id(),
                                choice != null ? choice.finishReason() : null,
                                choice != null && choice.delta() != null && choice.delta().content() != null
                                    ? choice.delta().content().length() : 0,
                                choice != null && choice.delta() != null && choice.delta().reasoningContent() != null
                                    ? choice.delta().reasoningContent().length() : 0,
                                choice != null && choice.delta() != null && choice.delta().toolCalls() != null
                                    ? choice.delta().toolCalls().size() : 0,
                                uChunk.usage() != null
                                    ? uChunk.usage().promptTokens() + "/" + uChunk.usage().completionTokens()
                                    : "null");
                            if (log.isTraceEnabled()) {
                                log.trace("{} 后端原始流式事件体: {}", backendName, event.toString());
                            }
                            sink.next(uChunk);
                        }
                    });
                }
                log.debug("{} 流式响应完成", backendName);
                // 流截断分类:无 stopReason 时按是否有输出兜底
                if (!respConverter.isStreamCompleted() && !sink.isCancelled()) {
                    if (respConverter.hasSubstantiveOutput()) {
                        log.warn("{} 流式响应被截断: 已有输出但无 stopReason,合成 finish_reason=length", backendName);
                        sink.next(respConverter.synthesizeIncompleteChunk());
                    } else {
                        log.error("{} 流式响应被截断: 无输出且无 stopReason,标记为 stream_truncated", backendName);
                        sink.error(new BackendApiException(backendName, 502,
                            "上游流式响应被截断: 无输出且未发送 stop_reason"));
                        return;
                    }
                }
                sink.complete();
            } catch (AnthropicServiceException e) {
                log.error("{} 流式请求失败: status={} body={}", backendName,
                    e.statusCode(), e.body());
                sink.error(new BackendApiException(backendName, e.statusCode(),
                    "Anthropic API 流式调用失败: " + e.getMessage(), String.valueOf(e.body())));
            } catch (Exception e) {
                log.error("{} 流式请求异常: {}", backendName, e.getMessage(), e);
                sink.error(new BackendApiException(backendName, 502,
                    "Anthropic API 流式调用失败: " + e.getMessage()));
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private static void logRequestParams(String name, MessageCreateParams params, String label) {
        if (!log.isTraceEnabled()) return;
        try {
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            var root = mapper.createObjectNode();
            root.put("model", params.model().asString());
            root.put("max_tokens", params.maxTokens());
            params.temperature().ifPresent(t -> root.put("temperature", t));
            params.topP().ifPresent(p -> root.put("top_p", p));
            params.stopSequences().ifPresent(s -> {
                var arr = mapper.createArrayNode();
                s.forEach(arr::add);
                root.set("stop_sequences", arr);
            });
            params.system().ifPresent(sys -> {
                if (sys.isString()) root.put("system", sys.asString());
                else root.put("system", sys.toString());
            });
            var msgsArr = mapper.createArrayNode();
            for (var msg : params.messages()) {
                msgsArr.add(msg.toString());
            }
            root.set("messages", msgsArr);
            params.tools().ifPresent(ts -> {
                var toolsArr = mapper.createArrayNode();
                for (var tu : ts) {
                    if (tu.isTool()) {
                        var t = tu.asTool();
                        var toolNode = mapper.createObjectNode();
                        toolNode.put("name", t.name());
                        t.description().ifPresent(d -> toolNode.put("description", d));
                        var schema = t.inputSchema();
                        var schemaNode = mapper.createObjectNode();
                        schemaNode.put("type", schema._type().toString());
                        schema.required().ifPresent(r -> {
                            var arr = mapper.createArrayNode();
                            r.forEach(arr::add);
                            schemaNode.set("required", arr);
                        });
                        schema.properties().ifPresent(p ->
                            schemaNode.put("properties", p.toString()));
                        toolNode.set("input_schema", schemaNode);
                        toolsArr.add(toolNode);
                    }
                }
                root.set("tools", toolsArr);
            });
            String bodyJson = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root);
            log.trace("{} {} 完整 JSON ({} chars):\n{}",
                name, label, bodyJson.length(), bodyJson);
        } catch (Exception e) {
            log.warn("{} {} 请求体构建失败", name, label, e);
        }
    }

    @Override
    public List<ModelInfo> listModels() {
        try {
            var page = client.models().list();
            List<ModelInfo> result = new ArrayList<>();
            for (var model : page.data()) {
                result.add(ModelInfo.builder()
                        .id(model.id())
                        .created(0L)
                        .ownedBy("anthropic")
                        .build());
            }
            log.debug("{} 模型列表: {} 个模型", backendName, result.size());
            return result;
        } catch (AnthropicServiceException e) {
            log.error("{} 获取模型列表失败: status={} body={}", backendName,
                e.statusCode(), e.body());
            throw new BackendApiException(backendName, e.statusCode(),
                "Anthropic 模型列表获取失败: " + e.getMessage(), String.valueOf(e.body()));
        } catch (Exception e) {
            log.error("{} 获取模型列表异常: {}", backendName, e.getMessage(), e);
            throw new BackendApiException(backendName, 502,
                "Anthropic 模型列表获取失败: " + e.getMessage());
        }
    }

    @Override
    public void close() {
        client.close();
    }
}
