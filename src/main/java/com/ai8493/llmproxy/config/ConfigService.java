package com.ai8493.llmproxy.config;

import com.ai8493.llmproxy.config.entity.BackendConfigEntity;
import com.ai8493.llmproxy.config.entity.ModelMappingEntity;
import com.ai8493.llmproxy.config.entity.ProtocolMappingEntity;
import com.ai8493.llmproxy.config.repository.BackendConfigRepository;
import com.ai8493.llmproxy.config.repository.ModelMappingRepository;
import com.ai8493.llmproxy.config.repository.ProtocolMappingRepository;
import com.ai8493.llmproxy.orchestrator.BackendAdapterFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Service
public class ConfigService {

    private final BackendConfigRepository backendRepo;
    private final ProtocolMappingRepository protocolRepo;
    private final ModelMappingRepository modelRepo;
    private final BackendAdapterFactory backendFactory;

    public ConfigService(BackendConfigRepository backendRepo,
                          ProtocolMappingRepository protocolRepo,
                          ModelMappingRepository modelRepo,
                          BackendAdapterFactory backendFactory) {
        this.backendRepo = backendRepo;
        this.protocolRepo = protocolRepo;
        this.modelRepo = modelRepo;
        this.backendFactory = backendFactory;
    }

    // ===== BackendConfig =====

    public List<BackendConfigEntity> listBackends() {
        var list = new ArrayList<BackendConfigEntity>();
        backendRepo.findAll().forEach(e -> {
            list.add(new BackendConfigEntity(
                e.name(), e.protocol(), e.apiKey(), e.baseUrl(),
                e.defaultModel(), e.defaultMaxTokens(),
                e.connectTimeout(), e.readTimeout(), e.writeTimeout(),
                e.maxIdleConnections(), e.keepAliveDuration(), e.updatedAt()));
        });
        return list;
    }

    // 分页 + 按名模糊查询：name 为空时查全部，否则 LIKE %name%。
    // 手动 LIMIT/OFFSET 绕开 Spring Data JDBC 的 Pageable SQL（SQLite 不支持 OFFSET ... FETCH FIRST）。
    public Page<BackendConfigEntity> searchBackends(String name, int page, int size) {
        Pageable pageable = PageRequest.of(page, size);
        int offset = page * size;
        String kw = (name == null || name.isBlank()) ? "%" : "%" + name + "%";
        var rows = backendRepo.findByNameLikePaged(kw, size, offset);
        long total = backendRepo.countByNameLike(kw);
        var entities = rows.stream().map(e -> new BackendConfigEntity(
            e.name(), e.protocol(), e.apiKey(), e.baseUrl(),
            e.defaultModel(), e.defaultMaxTokens(),
            e.connectTimeout(), e.readTimeout(), e.writeTimeout(),
            e.maxIdleConnections(), e.keepAliveDuration(), e.updatedAt())).toList();
        return new PageImpl<>(entities, pageable, total);
    }

    public void saveBackend(BackendConfigEntity input) {
        String apiKey = input.apiKey();
        if (apiKey == null || apiKey.isEmpty()) {
            var existing = backendRepo.findById(input.name()).orElseThrow();
            apiKey = existing.apiKey();
        }
        backendRepo.save(new BackendConfigEntity(
            input.name(), input.protocol(), apiKey, input.baseUrl(),
            input.defaultModel(), input.defaultMaxTokens(),
            input.connectTimeout(), input.readTimeout(), input.writeTimeout(),
            input.maxIdleConnections(), input.keepAliveDuration(),
            Instant.now().toString()));
        backendFactory.invalidate(input.name());
    }

    public void deleteBackend(String name) {
        var refs = protocolRepo.findByBackendCfgName(name);
        if (!refs.isEmpty()) {
            throw new IllegalStateException(
                "后端 " + name + " 被路由引用 " + refs.size() + " 条，请先解除关联");
        }
        backendRepo.deleteById(name);
        backendFactory.invalidate(name);
    }

    // ===== ProtocolMapping =====

    public List<ProtocolMappingEntity> listProtocolMappings() {
        return protocolRepo.findAllOrdered();
    }

    // 分页 + 按 clientProtocol 过滤：clientProtocol 为空时查全部（kw="%"）。
    // clientProtocol 为有限枚举，kw 直接传原值（如 "openai"）不包 %，区别于 searchBackends 的 %name% 模糊匹配；
    // 手动 LIMIT/OFFSET 绕开 Spring Data JDBC 的 Pageable SQL（SQLite 不支持 OFFSET ... FETCH FIRST）。
    public Page<ProtocolMappingEntity> searchProtocolMappings(String clientProtocol, int page, int size) {
        Pageable pageable = PageRequest.of(page, size);
        int offset = page * size;
        String kw = (clientProtocol == null || clientProtocol.isBlank()) ? "%" : clientProtocol;
        var rows = protocolRepo.findByClientProtocolLikePaged(kw, size, offset);
        long total = protocolRepo.countByClientProtocolLike(kw);
        return new PageImpl<>(rows, pageable, total);
    }

    // 保存协议映射 + 全量替换模型映射（同一事务）。
    // modelMappings == null 表示调用方不意图改模型映射（如 toggleProtocol 启用/禁用切换），
    // 此时跳过替换避免误删。空列表（非 null）才会清空所有模型映射。
    @Transactional
    public void saveProtocolMapping(ProtocolMappingEntity input) {
        var now = Instant.now().toString();
        protocolRepo.save(new ProtocolMappingEntity(
            input.clientProtocol(), input.backendCfgName(),
            input.enabled(), now, null));
        if (input.modelMappings() == null) return;
        modelRepo.deleteByOwner(input.clientProtocol(), input.backendCfgName());
        for (var m : input.modelMappings()) {
            if (m.requestModel() == null || m.requestModel().isBlank()) continue;
            modelRepo.save(new ModelMappingEntity(
                input.clientProtocol(), input.backendCfgName(),
                m.requestModel(), m.actualModel(), now));
        }
    }

    @Transactional
    public void deleteProtocolMapping(String clientProtocol, String backendCfgName) {
        modelRepo.deleteByOwner(clientProtocol, backendCfgName);
        protocolRepo.deleteByKey(clientProtocol, backendCfgName);
    }

    // 编辑模式下"添加"按钮即时落库：单条 upsert。
    // 前端已做 requestModel 重复校验，此处直接 upsert（INSERT OR REPLACE）兜底并发。
    public void addModelMapping(String clientProtocol, String backendCfgName, String requestModel, String actualModel) {
        var now = Instant.now().toString();
        modelRepo.save(new ModelMappingEntity(clientProtocol, backendCfgName, requestModel, actualModel, now));
    }

    // 编辑模式下"删除"按钮即时落库：按主键 (client, backend, requestModel) 删单条。
    public void deleteModelMapping(String clientProtocol, String backendCfgName, String requestModel) {
        modelRepo.deleteByKey(clientProtocol, backendCfgName, requestModel);
    }

    public ProtocolMappingEntity getProtocolMapping(String clientProtocol, String backendCfgName) {
        var pm = protocolRepo.findByKey(clientProtocol, backendCfgName);
        if (pm == null) return null;
        var models = modelRepo.findByOwner(clientProtocol, backendCfgName);
        return new ProtocolMappingEntity(
            pm.clientProtocol(), pm.backendCfgName(),
            pm.enabled(), pm.updatedAt(), models);
    }

    // 查 clientProtocol 下 enabled 且 updated_at 最大的路由项，取对应后端的 defaultModel。
    // 复用 ProtocolMappingRepository.findFirstEnabledByClientProtocolOrderByUpdatedAtDesc。
    // 查不到（无 enabled 路由项 / 后端不存在 / defaultModel 为空）时返回空字符串，让用户手动填。
    public String findDefaultModelForClientProtocol(String clientProtocol) {
        var pm = protocolRepo.findFirstEnabledByClientProtocolOrderByUpdatedAtDesc(clientProtocol);
        if (pm == null) return "";
        var backend = backendRepo.findById(pm.backendCfgName()).orElse(null);
        if (backend == null) return "";
        return backend.defaultModel() == null ? "" : backend.defaultModel();
    }
}
