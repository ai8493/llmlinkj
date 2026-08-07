package com.ai8493.llmproxy.orchestrator;

import static org.assertj.core.api.Assertions.*;

import com.ai8493.llmproxy.config.entity.BackendConfigEntity;
import com.ai8493.llmproxy.config.entity.ModelMappingEntity;
import com.ai8493.llmproxy.config.entity.ProtocolMappingEntity;
import com.ai8493.llmproxy.config.repository.BackendConfigRepository;
import com.ai8493.llmproxy.config.repository.ModelMappingRepository;
import com.ai8493.llmproxy.config.repository.ProtocolMappingRepository;
import com.ai8493.llmproxy.model.UnifiedChatRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ProxyOrchestratorTest {

    @Autowired private ProxyOrchestrator orchestrator;
    @Autowired private BackendConfigRepository backendRepo;
    @Autowired private ProtocolMappingRepository protocolRepo;
    @Autowired private ModelMappingRepository modelRepo;

    // V6 迁移在 SpringContext 启动时灌入了种子数据（已提交，不在 @Transactional 内，不回滚）。
    // 每个测试方法执行前清空三张表，保证断言基于本方法插入的数据。
    @BeforeEach
    void cleanTables() {
        modelRepo.deleteAll();
        protocolRepo.deleteAll();
        backendRepo.deleteAll();
    }

    // 构造一个最小可用的 BackendConfigEntity（16 字段 record）
    private static BackendConfigEntity backend(String name, String protocol, String defaultModel) {
        return new BackendConfigEntity(
            name, protocol, "k", "http://localhost:8089/v1", defaultModel,
            null, 5L, 10L, 5L, 5, 60L, null, null, null, null, "2026-01-01T00:00:00Z");
    }

    // 保存一条 protocol_mapping：enabled + updated_at 可控
    private void saveProtocolMapping(String clientProtocol, String backendName, boolean enabled, String updatedAt) {
        protocolRepo.save(new ProtocolMappingEntity(
            clientProtocol, backendName, enabled, updatedAt, null));
    }

    @Test
    void shouldThrowWhenNoProtocolMapping() {
        // DB 无任何 protocol_mapping 条目，resolve 应抛"无可用后端"
        var req = UnifiedChatRequest.builder()
            .model("gpt-4")
            .stream(false)
            .build();
        assertThatThrownBy(() -> orchestrator.handle(req, "openai"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("无可用后端")
            .hasMessageContaining("openai");
    }

    @Test
    void shouldRouteByMostRecentEnabled() {
        // 入站协议 openai 有 2 条 enabled=true，updated_at 不同，应路由到 updated_at 大的那条。
        // factory 已懒加载，backendName 用任意名（alpha/beta），factory.get 时从 DB 读 backend_config 现构造 adapter。
        backendRepo.save(backend("alpha", "openai", "alpha-model"));
        backendRepo.save(backend("beta", "openai", "beta-model"));
        saveProtocolMapping("openai", "alpha", true, "2026-01-01T00:00:00Z");
        saveProtocolMapping("openai", "beta", true, "2026-06-01T00:00:00Z");

        // 直接调 resolve（package-private），返回 Route（package-private record）。
        // requestedModel="any-model" 在 model_mapping 无映射，fallback 到 DB 的 default-model。
        // 路由到 beta（updated_at 更大），actualModel 应为 beta 的 default-model。
        var route = orchestrator.resolve("openai", "any-model");
        assertThat(route.actualModel()).isEqualTo("beta-model");
    }

    @Test
    void shouldSkipDisabledBackend() {
        // 唯一一条 enabled 改为 false，应抛"无可用后端"
        backendRepo.save(backend("alpha", "openai", "alpha-model"));
        saveProtocolMapping("openai", "alpha", false, "2026-06-01T00:00:00Z");

        var req = UnifiedChatRequest.builder()
            .model("any-model")
            .stream(false)
            .build();
        assertThatThrownBy(() -> orchestrator.handle(req, "openai"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("无可用后端");
    }

    @Test
    void shouldUseModelMappingWhenConfigured() {
        // model_mapping 有 (openai, alpha, gpt-4) → alpha-gpt4-actual，actualModel 用映射值
        backendRepo.save(backend("alpha", "openai", "alpha-default"));
        saveProtocolMapping("openai", "alpha", true, "2026-06-01T00:00:00Z");
        modelRepo.save(new ModelMappingEntity(
            "openai", "alpha", "gpt-4", "alpha-gpt4-actual", "2026-06-01T00:00:00Z"));

        var route = orchestrator.resolve("openai", "gpt-4");
        assertThat(route.actualModel()).isEqualTo("alpha-gpt4-actual");
    }

    @Test
    void shouldFallbackToBackendDefaultModel() {
        // model_mapping 无映射，actualModel 用 backend_config.default_model
        backendRepo.save(backend("alpha", "openai", "alpha-default"));
        saveProtocolMapping("openai", "alpha", true, "2026-06-01T00:00:00Z");

        var route = orchestrator.resolve("openai", "gpt-4");
        assertThat(route.actualModel()).isEqualTo("alpha-default");
    }

    @Test
    void shouldHandleNullRequestedModel() {
        // listModels 路径，requestedModel=null，不查 model_mapping，用 default_model
        backendRepo.save(backend("alpha", "openai", "alpha-default"));
        saveProtocolMapping("openai", "alpha", true, "2026-06-01T00:00:00Z");
        modelRepo.save(new ModelMappingEntity(
            "openai", "alpha", "ignored-model", "should-not-be-used", "2026-06-01T00:00:00Z"));

        var route = orchestrator.resolve("openai", null);
        assertThat(route.actualModel()).isEqualTo("alpha-default");
    }
}
