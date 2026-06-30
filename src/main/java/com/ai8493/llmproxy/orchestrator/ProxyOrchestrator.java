package com.ai8493.llmproxy.orchestrator;

import com.ai8493.llmproxy.adapter.BackendAdapter;
import com.ai8493.llmproxy.config.repository.BackendConfigRepository;
import com.ai8493.llmproxy.config.repository.ModelMappingRepository;
import com.ai8493.llmproxy.config.repository.ProtocolMappingRepository;
import com.ai8493.llmproxy.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

@Component
public class ProxyOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(ProxyOrchestrator.class);

    private final BackendAdapterFactory factory;
    private final ProtocolMappingRepository protocolRepo;
    private final ModelMappingRepository modelRepo;
    private final BackendConfigRepository backendRepo;

    public ProxyOrchestrator(BackendAdapterFactory factory,
                              ProtocolMappingRepository protocolRepo,
                              ModelMappingRepository modelRepo,
                              BackendConfigRepository backendRepo) {
        this.factory = factory;
        this.protocolRepo = protocolRepo;
        this.modelRepo = modelRepo;
        this.backendRepo = backendRepo;
    }

    public UnifiedChatResponse handle(UnifiedChatRequest uReq, String inboundProtocol) {
        Route r = resolve(inboundProtocol, uReq.model());
        return r.adapter().call(new UnifiedChatRequest(
            r.actualModel(), uReq.messages(), uReq.config(),
            uReq.tools(), uReq.toolChoice(), uReq.stream()));
    }

    public Flux<UnifiedChatResponse> handleStream(UnifiedChatRequest uReq, String inboundProtocol) {
        Route r = resolve(inboundProtocol, uReq.model());
        return r.adapter().stream(new UnifiedChatRequest(
            r.actualModel(), uReq.messages(), uReq.config(),
            uReq.tools(), uReq.toolChoice(), uReq.stream()));
    }

    public java.util.List<ModelInfo> listModels(String inboundProtocol) {
        Route r = resolve(inboundProtocol, null);
        return r.adapter().listModels();
    }

    // 改为 package-private（去 private），让同包测试类 ProxyOrchestratorTest 可直接调用，
    // 避免 handle 触发真实 HTTP。生产调用方 ProxyController 仍走 handle → resolve。
    Route resolve(String inboundProtocol, String requestedModel) {
        // 步骤 1：取入站协议下 enabled=1 且 updated_at 最大的一条
        var pm = protocolRepo.findFirstEnabledByClientProtocolOrderByUpdatedAtDesc(inboundProtocol);
        if (pm == null) {
            throw new IllegalArgumentException("无可用后端，入站协议: " + inboundProtocol);
        }
        String backendName = pm.backendCfgName();
        String clientProtocol = pm.clientProtocol();

        // 步骤 2：查 backend_config 拿 default_model
        var backendCfg = backendRepo.findById(backendName)
            .orElseThrow(() -> new IllegalStateException("后端配置缺失: " + backendName));
        String defaultModel = backendCfg.defaultModel();

        // 步骤 3：查 model_mapping(client_protocol, backend_cfg_name, request_model)
        String actualModel;
        if (requestedModel != null) {
            var mm = modelRepo.findByOwnerAndRequestModel(clientProtocol, backendName, requestedModel);
            if (mm != null) {
                actualModel = mm.actualModel();
            } else if (defaultModel != null) {
                actualModel = defaultModel;
            } else {
                actualModel = requestedModel;
            }
        } else {
            // listModels 路径，requestedModel 为 null，直接用 defaultModel
            actualModel = defaultModel;
        }

        log.debug("路由: 入站协议={} 请求模型={} → 后端={} 协议={} 实际模型={}",
            inboundProtocol, requestedModel, backendName, backendCfg.protocol(), actualModel);
        return new Route(factory.get(backendName), actualModel);
    }

    // package-private：resolve 改为 package-private 后，测试类需访问 actualModel() 访问器
    record Route(BackendAdapter adapter, String actualModel) {}
}
