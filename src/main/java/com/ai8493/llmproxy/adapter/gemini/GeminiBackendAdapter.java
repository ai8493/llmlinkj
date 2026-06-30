package com.ai8493.llmproxy.adapter.gemini;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.genai.Client;
import com.google.genai.types.GenerateContentParameters;
import com.google.genai.types.GenerateContentResponse;
import com.ai8493.llmproxy.adapter.BackendAdapter;
import com.ai8493.llmproxy.client.BackendClientFactory;
import com.ai8493.llmproxy.config.BackendConfig;
import com.ai8493.llmproxy.converter.FunctionCallMapper;
import com.ai8493.llmproxy.converter.ToolMapper;
import com.ai8493.llmproxy.exception.BackendApiException;
import com.ai8493.llmproxy.model.UnifiedChatRequest;
import com.ai8493.llmproxy.model.UnifiedChatResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

public class GeminiBackendAdapter implements BackendAdapter {

    private static final Logger log = LoggerFactory.getLogger(GeminiBackendAdapter.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private final String backendName;
    private final FunctionCallMapper functionCallMapper;
    private final ToolMapper toolMapper;
    private Client client;

    public GeminiBackendAdapter(String backendName) {
        this.backendName = backendName;
        this.toolMapper = new ToolMapper();
        this.functionCallMapper = new FunctionCallMapper();
    }

    @Override
    public String backendName() { return backendName; }

    @Override
    public void init(BackendConfig config) {
        this.client = BackendClientFactory.createClient(config);
    }

    @Override
    public UnifiedChatResponse call(UnifiedChatRequest request) {
        GeminiRequestConverter reqConverter = new GeminiRequestConverter(toolMapper);
        GenerateContentParameters params = reqConverter.toGeminiRequest(request);
        log.debug("{} 后端请求: model={} | contents={}", backendName, params.model(),
            params.contents().map(Object::toString).orElse("[]"));
        GenerateContentResponse resp = client.models.generateContent(
            params.model().orElseThrow(() -> new IllegalArgumentException("model 不能为空")),
            params.contents().orElse(java.util.List.of()),
            params.config().orElse(null));
        log.debug("{} 后端响应: {}", backendName, resp);
        GeminiResponseConverter respConverter = new GeminiResponseConverter(functionCallMapper);
        return respConverter.toUnifiedResponse(resp);
    }

    @Override
    public Flux<UnifiedChatResponse> stream(UnifiedChatRequest request) {
        return Flux.create((FluxSink<UnifiedChatResponse> sink) -> {
            try {
                GeminiRequestConverter reqConverter = new GeminiRequestConverter(toolMapper);
                GenerateContentParameters params = reqConverter.toGeminiRequest(request);
                log.debug("{} 后端流式请求: model={} | contents={}", backendName, params.model(),
                    params.contents().map(Object::toString).orElse("[]"));
                var stream = client.models.generateContentStream(
                    params.model().orElseThrow(() -> new IllegalArgumentException("model 不能为空")),
                    params.contents().orElse(java.util.List.of()),
                    params.config().orElse(null));
                GeminiStreamingResponseConverter respConverter =
                    new GeminiStreamingResponseConverter(request.model());
                stream.iterator().forEachRemaining(chunk -> {
                    if (!sink.isCancelled()) {
                        UnifiedChatResponse uChunk = respConverter.toUnifiedStreamChunk(chunk);
                        if (uChunk.choices() != null && !uChunk.choices().isEmpty()) {
                            sink.next(uChunk);
                        }
                    }
                });
                sink.complete();
            } catch (Exception e) {
                sink.error(new BackendApiException(
                    backendName, 502, "Gemini API 调用失败: " + e.getMessage()));
            }
        }, FluxSink.OverflowStrategy.BUFFER);
    }

    @Override
    public java.util.List<com.ai8493.llmproxy.model.ModelInfo> listModels() {
        try {
            var config = com.google.genai.types.ListModelsConfig.builder().build();
            var pager = client.models.list(config);
            java.util.List<com.ai8493.llmproxy.model.ModelInfo> result = new java.util.ArrayList<>();
            for (var model : pager) {
                String id = model.name()
                    .map(n -> n.replace("models/", ""))
                    .orElse("unknown");
                result.add(new com.ai8493.llmproxy.model.ModelInfo(id, 0L, "google"));
            }
            log.debug("{} 模型列表: {} 个模型", backendName, result.size());
            return result;
        } catch (Exception e) {
            log.error("{} 获取模型列表异常: {}", backendName, e.getMessage(), e);
            throw new com.ai8493.llmproxy.exception.BackendApiException(backendName, 502,
                "Gemini 模型列表获取失败: " + e.getMessage());
        }
    }

    @Override
    public void close() {
        client.close();
    }
}
