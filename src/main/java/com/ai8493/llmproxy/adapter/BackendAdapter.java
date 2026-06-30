package com.ai8493.llmproxy.adapter;

import com.ai8493.llmproxy.config.BackendConfig;
import com.ai8493.llmproxy.model.ModelInfo;
import com.ai8493.llmproxy.model.UnifiedChatRequest;
import com.ai8493.llmproxy.model.UnifiedChatResponse;
import reactor.core.publisher.Flux;

import java.util.List;

public interface BackendAdapter {
    String backendName();

    void init(BackendConfig config);

    UnifiedChatResponse call(UnifiedChatRequest request);

    Flux<UnifiedChatResponse> stream(UnifiedChatRequest request);

    List<ModelInfo> listModels();

    /**
     * 关闭适配器持有的 SDK client 资源（连接池+线程池）。
     * 由 BackendAdapterFactory.invalidate 在 backend 配置变更后调用。
     * 不继承 AutoCloseable —— adapter 是长生命周期对象，不配合 try-with-resources 使用。
     */
    void close();
}
