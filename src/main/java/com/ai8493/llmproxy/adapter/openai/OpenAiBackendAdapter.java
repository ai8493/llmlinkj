package com.ai8493.llmproxy.adapter.openai;

import com.openai.client.OpenAIClient;
import com.openai.errors.OpenAIServiceException;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.ChatCompletionStreamOptions;
import com.ai8493.llmproxy.adapter.BackendAdapter;
import com.ai8493.llmproxy.client.BackendClientFactory;
import com.ai8493.llmproxy.config.BackendConfig;
import com.ai8493.llmproxy.exception.BackendApiException;
import com.ai8493.llmproxy.model.UnifiedChatRequest;
import com.ai8493.llmproxy.model.UnifiedChatResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

public class OpenAiBackendAdapter implements BackendAdapter {

    private static final Logger log = LoggerFactory.getLogger(OpenAiBackendAdapter.class);

    private final String backendName;
    private OpenAIClient client;
    private String defaultModel;

    public OpenAiBackendAdapter(String backendName) {
        this.backendName = backendName;
    }

    @Override
    public String backendName() { return backendName; }

    @Override
    public void init(BackendConfig config) {
        this.client = BackendClientFactory.createOpenAiClient(config);
        this.defaultModel = config.defaultModel();
    }

    @Override
    public UnifiedChatResponse call(UnifiedChatRequest request) {
        try {
            var reqConverter = new OpenAiRequestConverter();
            ChatCompletionCreateParams params = reqConverter.convert(request);
            log.debug("{} 后端请求: model={} messages={} tools={}",
                backendName, params.model().asString(),
                params.messages().size(), params.tools().map(java.util.List::size).orElse(0));
            logRequestParams(backendName, params, "后端请求");

            var result = client.chat().completions().create(params);

            log.debug("{} 后端响应: id={} model={} choices={}", backendName,
                result.id(), result.model(), result.choices().size());
            if (log.isTraceEnabled()) {
                log.trace("{} 后端响应体: {}", backendName, result.toString());
            }
            var respConverter = new OpenAiResponseConverter();
            return respConverter.convert(result);
        } catch (OpenAIServiceException e) {
            log.error("{} 后端请求失败: status={} body={}", backendName,
                e.statusCode(), e.body());
            throw new BackendApiException(backendName, e.statusCode(),
                "OpenAI API 调用失败: " + e.getMessage());
        } catch (Exception e) {
            log.error("{} 后端请求异常: {}", backendName, e.getMessage(), e);
            throw new BackendApiException(backendName, 502,
                "OpenAI API 调用失败: " + e.getMessage());
        }
    }

    @Override
    public Flux<UnifiedChatResponse> stream(UnifiedChatRequest request) {
        return Flux.create(sink -> {
            ChatCompletionCreateParams params = null;
            try {
                var reqConverter = new OpenAiRequestConverter();
                params = reqConverter.convert(request);

                // 流式请求注入 stream_options.include_usage=true，使后端在最后一个 chunk 返回 usage
                if (request.stream()) {
                    params = params.toBuilder()
                        .streamOptions(ChatCompletionStreamOptions.builder()
                            .includeUsage(true)
                            .build())
                        .build();
                }

                log.debug("{} 后端流式请求: model={} messages={} tools={}",
                    backendName, params.model().asString(),
                    params.messages().size(), params.tools().map(java.util.List::size).orElse(0));
                logRequestParams(backendName, params, "后端流式请求");
                var stream = client.chat().completions().createStreaming(params);
                var respConverter = new OpenAiStreamingResponseConverter(request.model());
                stream.stream().forEach(chunk -> {
                    if (!sink.isCancelled()) {
                        UnifiedChatResponse uChunk = respConverter.convertChunk(chunk);
                        if (uChunk.choices() != null && !uChunk.choices().isEmpty()) {
                            var choice = uChunk.choices().get(0);
                            log.debug("{} 后端原始流式块: id={} finishReason={} contentLen={} toolCalls={}",
                                backendName, chunk.id(),
                                choice.finishReason(),
                                choice.delta() != null && choice.delta().content() != null
                                    ? choice.delta().content().length() : 0,
                                choice.delta() != null && choice.delta().toolCalls() != null
                                    ? choice.delta().toolCalls().size() : 0);
                            if (log.isTraceEnabled()) {
                                try {
                                    var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                                    var root = mapper.createObjectNode();
                                    root.put("id", chunk.id());
                                    root.put("model", chunk.model());
                                    root.put("created", chunk.created());
                                    var choicesArr = mapper.createArrayNode();
                                    for (var c : chunk.choices()) {
                                        var cn = mapper.createObjectNode();
                                        cn.put("index", c.index());
                                        c.delta().content().ifPresent(ct ->
                                            cn.put("delta_content", ct));
                                        c.delta().role().ifPresent(r ->
                                            cn.put("delta_role", r.toString()));
                                        c.delta().toolCalls().ifPresent(tcs -> {
                                            var tcArr = mapper.createArrayNode();
                                            for (var tc : tcs) {
                                                tcArr.add(tc.toString());
                                            }
                                            cn.set("delta_tool_calls", tcArr);
                                        });
                                        c.finishReason().ifPresent(fr ->
                                            cn.put("finish_reason", fr.toString()));
                                        choicesArr.add(cn);
                                    }
                                    root.set("choices", choicesArr);
                                    chunk.usage().ifPresent(u -> {
                                        var un = mapper.createObjectNode();
                                        un.put("prompt_tokens", u.promptTokens());
                                        un.put("completion_tokens", u.completionTokens());
                                        un.put("total_tokens", u.totalTokens());
                                        root.set("usage", un);
                                    });
                                    log.trace("{} 后端原始流式块体 JSON:\n{}", backendName,
                                        mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root));
                                } catch (Exception e) {
                                    log.trace("{} 后端原始流式块体: {}", backendName, chunk.toString());
                                }
                            }
                            sink.next(uChunk);
                        }
                    }
                });
                log.debug("{} 流式响应完成", backendName);
                sink.complete();
            } catch (OpenAIServiceException e) {
                log.error("{} 流式请求失败: status={} body={} params={}", backendName,
                    e.statusCode(), e.body(), params);
                sink.error(new BackendApiException(backendName, e.statusCode(),
                    "OpenAI API 流式调用失败: " + e.getMessage()));
            } catch (Exception e) {
                log.error("{} 流式请求异常: {}", backendName, e.getMessage(), e);
                sink.error(new BackendApiException(backendName, 502,
                    "OpenAI API 流式调用失败: " + e.getMessage()));
            }
        });
    }

    private static void logRequestParams(String name, ChatCompletionCreateParams params, String label) {
        if (!log.isTraceEnabled()) return;
        try {
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            var root = mapper.createObjectNode();
            root.put("model", params.model().asString());

            var msgsArr = mapper.createArrayNode();
            for (var msg : params.messages()) {
                var msgNode = mapper.createObjectNode();
                if (msg.isSystem()) {
                    msgNode.put("role", "system");
                    msgNode.put("content", msg.asSystem().content().asText());
                } else if (msg.isUser()) {
                    var m = msg.asUser();
                    msgNode.put("role", "user");
                    if (m.content().isText()) {
                        msgNode.put("content", m.content().asText());
                    } else if (m.content().isArrayOfContentParts()) {
                        var partsArr = mapper.createArrayNode();
                        for (var part : m.content().asArrayOfContentParts()) {
                            if (part.isText()) {
                                partsArr.add(mapper.createObjectNode()
                                    .put("type", "text")
                                    .put("text", part.asText().text()));
                            } else if (part.isImageUrl()) {
                                partsArr.add(mapper.createObjectNode()
                                    .put("type", "image_url")
                                    .put("url", part.asImageUrl().imageUrl().url()));
                            }
                        }
                        msgNode.set("content", partsArr);
                    }
                    m.name().ifPresent(n -> msgNode.put("name", n));
                } else if (msg.isAssistant()) {
                    var m = msg.asAssistant();
                    msgNode.put("role", "assistant");
                    m.content().ifPresent(c -> {
                        if (c.isText()) msgNode.put("content", c.asText());
                    });
                    if (m.toolCalls().isPresent()) {
                        var tcArr = mapper.createArrayNode();
                        for (var tc : m.toolCalls().get()) {
                            var tcNode = mapper.createObjectNode();
                            if (tc.isFunction()) {
                                var ftc = tc.asFunction();
                                tcNode.put("id", ftc.id());
                                tcNode.put("type", "function");
                                var fnNode = mapper.createObjectNode();
                                fnNode.put("name", ftc.function().name());
                                fnNode.put("arguments", ftc.function().arguments());
                                tcNode.set("function", fnNode);
                            }
                            tcArr.add(tcNode);
                        }
                        msgNode.set("tool_calls", tcArr);
                    }
                } else if (msg.isTool()) {
                    var m = msg.asTool();
                    msgNode.put("role", "tool");
                    msgNode.put("tool_call_id", m.toolCallId());
                    msgNode.put("content", m.content().asText());
                }
                msgsArr.add(msgNode);
            }
            root.set("messages", msgsArr);

            params.tools().ifPresent(ts -> {
                var toolsArr = mapper.createArrayNode();
                for (var t : ts) {
                    var toolNode = mapper.createObjectNode();
                    if (t.isFunction()) {
                        var fn = t.asFunction().function();
                        toolNode.put("type", "function");
                        var fnNode = mapper.createObjectNode();
                        fnNode.put("name", fn.name());
                        fn.description().ifPresent(d -> fnNode.put("description", d));
                        fn.parameters().ifPresent(p -> {
                            try {
                                fnNode.set("parameters", mapper.readTree(p.toString()));
                            } catch (Exception e) {
                                fnNode.put("parameters", p.toString());
                            }
                        });
                        toolNode.set("function", fnNode);
                    } else {
                        toolNode.put("type", t.toString());
                    }
                    toolsArr.add(toolNode);
                }
                root.set("tools", toolsArr);
            });

            params.maxTokens().ifPresent(t -> root.put("max_tokens", t));
            params.temperature().ifPresent(t -> root.put("temperature", t));
            params.topP().ifPresent(p -> root.put("top_p", p));
            params.user().ifPresent(u -> root.put("user", u));
            params.stop().ifPresent(s -> root.put("stop", s.toString()));
            params.toolChoice().ifPresent(tc -> root.put("tool_choice", tc.toString()));
            params.parallelToolCalls().ifPresent(p -> root.put("parallel_tool_calls", p));
            params.reasoningEffort().ifPresent(r -> root.put("reasoning_effort", r.toString()));

            String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root);
            log.trace("{} {} 完整 JSON ({} chars):\n{}", name, label, json.length(), json);
        } catch (Exception e) {
            log.warn("{} {} 请求体构建失败", name, label, e);
        }
    }

    @Override
    public java.util.List<com.ai8493.llmproxy.model.ModelInfo> listModels() {
        try {
            var page = client.models().list();
            java.util.List<com.ai8493.llmproxy.model.ModelInfo> result = new java.util.ArrayList<>();
            for (var model : page.data()) {
                result.add(new com.ai8493.llmproxy.model.ModelInfo(
                    model.id(), model.created(), model.ownedBy()));
            }
            log.debug("{} 模型列表: {} 个模型", backendName, result.size());
            return result;
        } catch (com.openai.errors.OpenAIServiceException e) {
            log.error("{} 获取模型列表失败: status={} body={}", backendName,
                e.statusCode(), e.body());
            throw new com.ai8493.llmproxy.exception.BackendApiException(backendName, e.statusCode(),
                "OpenAI 模型列表获取失败: " + e.getMessage());
        } catch (Exception e) {
            log.error("{} 获取模型列表异常: {}", backendName, e.getMessage(), e);
            throw new com.ai8493.llmproxy.exception.BackendApiException(backendName, 502,
                "OpenAI 模型列表获取失败: " + e.getMessage());
        }
    }

    @Override
    public void close() {
        client.close();
    }
}
