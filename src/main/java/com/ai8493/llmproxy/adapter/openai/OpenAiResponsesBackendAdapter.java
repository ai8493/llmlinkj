package com.ai8493.llmproxy.adapter.openai;

import com.ai8493.llmproxy.adapter.BackendAdapter;
import com.ai8493.llmproxy.client.BackendClientFactory;
import com.ai8493.llmproxy.config.BackendConfig;
import com.ai8493.llmproxy.exception.BackendApiException;
import com.ai8493.llmproxy.model.ModelInfo;
import com.ai8493.llmproxy.model.UnifiedChatRequest;
import com.ai8493.llmproxy.model.UnifiedChatResponse;
import com.openai.client.OpenAIClient;
import com.openai.errors.OpenAIServiceException;
import com.openai.models.responses.ResponseCreateParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;
import java.util.ArrayList;
import java.util.List;

public class OpenAiResponsesBackendAdapter implements BackendAdapter {

    private static final Logger log = LoggerFactory.getLogger(OpenAiResponsesBackendAdapter.class);

    private final String backendName;
    private OpenAIClient client;
    private BackendConfig config;

    public OpenAiResponsesBackendAdapter(String backendName) {
        this.backendName = backendName;
    }

    @Override
    public String backendName() { return backendName; }

    @Override
    public void init(BackendConfig config) {
        this.client = BackendClientFactory.createOpenAiClient(config);
        this.config = config;
    }

    @Override
    public UnifiedChatResponse call(UnifiedChatRequest request) {
        try {
            var reqConverter = new OpenAiResponsesRequestConverter();
            ResponseCreateParams params = reqConverter.convert(request, config);
            log.debug("{} responses 后端请求: model={} stream={}",
                backendName, request.model(), request.stream());

            var result = client.responses().create(params);
            log.debug("{} responses 后端响应: id={} status={}",
                backendName, result.id(), result.status());

            var respConverter = new OpenAiResponsesResponseConverter();
            return respConverter.convert(result);
        } catch (OpenAIServiceException e) {
            log.error("{} responses 后端请求失败: status={} body={}", backendName,
                e.statusCode(), e.body());
            throw new BackendApiException(backendName, e.statusCode(),
                "OpenAI Responses API 调用失败: " + e.getMessage(),
                String.valueOf(e.body()));
        } catch (Exception e) {
            log.error("{} responses 后端请求异常: {}", backendName, e.getMessage(), e);
            throw new BackendApiException(backendName, 502,
                "OpenAI Responses API 调用失败: " + e.getMessage());
        }
    }

    @Override
    public Flux<UnifiedChatResponse> stream(UnifiedChatRequest request) {
        return Flux.<UnifiedChatResponse>create(sink -> {
            ResponseCreateParams params = null;
            try {
                var reqConverter = new OpenAiResponsesRequestConverter();
                params = reqConverter.convert(request, config);

                log.debug("{} responses 后端流式请求: model={}", backendName, request.model());
                var stream = client.responses().createStreaming(params);
                var respConverter = new OpenAiResponsesStreamingResponseConverter(request.model());

                stream.stream().forEach(evt -> {
                    if (sink.isCancelled()) return;
                    UnifiedChatResponse chunk = respConverter.convert(evt);
                    if (chunk != null) {
                        sink.next(chunk);
                    }
                });

                log.debug("{} responses 流式响应完成", backendName);
                sink.complete();
            } catch (OpenAIServiceException e) {
                log.error("{} responses 流式请求失败: status={} body={} params={}", backendName,
                    e.statusCode(), e.body(), params);
                sink.error(new BackendApiException(backendName, e.statusCode(),
                    "OpenAI Responses API 流式调用失败: " + e.getMessage(),
                    String.valueOf(e.body())));
            } catch (Exception e) {
                log.error("{} responses 流式请求异常: {}", backendName, e.getMessage(), e);
                sink.error(new BackendApiException(backendName, 502,
                    "OpenAI Responses API 流式调用失败: " + e.getMessage()));
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public List<ModelInfo> listModels() {
        try {
            var page = client.models().list();
            List<ModelInfo> result = new ArrayList<>();
            for (var model : page.data()) {
                result.add(new ModelInfo(model.id(), model.created(), model.ownedBy()));
            }
            return result;
        } catch (OpenAIServiceException e) {
            throw new BackendApiException(backendName, e.statusCode(),
                "OpenAI 模型列表获取失败: " + e.getMessage(),
                String.valueOf(e.body()));
        } catch (Exception e) {
            throw new BackendApiException(backendName, 502,
                "OpenAI 模型列表获取失败: " + e.getMessage());
        }
    }

    @Override
    public void close() {
        if (client != null) client.close();
    }
}
