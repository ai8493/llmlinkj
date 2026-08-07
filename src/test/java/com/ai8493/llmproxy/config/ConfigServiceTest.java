package com.ai8493.llmproxy.config;

import static org.assertj.core.api.Assertions.*;

import com.ai8493.llmproxy.adapter.BackendAdapter;
import com.ai8493.llmproxy.config.entity.BackendConfigEntity;
import com.ai8493.llmproxy.config.entity.ModelMappingEntity;
import com.ai8493.llmproxy.config.entity.ProtocolMappingEntity;
import com.ai8493.llmproxy.config.repository.BackendConfigRepository;
import com.ai8493.llmproxy.config.repository.ModelMappingRepository;
import com.ai8493.llmproxy.config.repository.ProtocolMappingRepository;
import com.ai8493.llmproxy.orchestrator.BackendAdapterFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ConfigServiceTest {

    @Autowired private ConfigService service;
    @Autowired private BackendConfigRepository backendRepo;
    @Autowired private ProtocolMappingRepository protocolRepo;
    @Autowired private ModelMappingRepository modelRepo;
    @Autowired private BackendAdapterFactory backendFactory;

    // V6 迁移在 SpringContext 启动时灌入了种子数据（已提交，不在 @Transactional 内，不回滚）。
    // 每个测试方法执行前清空三张表，保证断言基于本方法插入的数据。
    @BeforeEach
    void cleanTables() {
        modelRepo.deleteAll();
        protocolRepo.deleteAll();
        backendRepo.deleteAll();
    }

    @Test
    void shouldListAllBackends() {
        backendRepo.save(new BackendConfigEntity(
            "test", "openai", "k", "u", "m", null, 5, 5, 5, 5, 60, null, null, null, null, "t"));

        var list = service.listBackends();
        assertThat(list).hasSize(1);
        assertThat(list.get(0).name()).isEqualTo("test");
    }

    @Test
    void shouldReturnPlainApiKeyWhenListing() {
        backendRepo.save(new BackendConfigEntity(
            "test", "openai", "sk-abcdef1234567890", "u", "m", null,
            5, 5, 5, 5, 60, null, null, null, null, "t"));

        var list = service.listBackends();
        assertThat(list.get(0).apiKey()).isEqualTo("sk-abcdef1234567890");
    }

    @Test
    void shouldSaveBackendWithMaskedApiKeyUnchangedWhenBlank() {
        backendRepo.save(new BackendConfigEntity(
            "test", "openai", "sk-secret123", "u", "m", null,
            5, 5, 5, 5, 60, null, null, null, null, "t"));

        service.saveBackend(new BackendConfigEntity(
            "test", "anthropic", "", "u2", "m2", null,
            5, 5, 5, 5, 60, null, null, null, null, "t"));

        var saved = backendRepo.findById("test").orElseThrow();
        assertThat(saved.protocol()).isEqualTo("anthropic");
        assertThat(saved.apiKey()).isEqualTo("sk-secret123");
    }

    @Test
    void shouldDeleteBackendWhenNoProtocolMappingReference() {
        backendRepo.save(new BackendConfigEntity(
            "test", "openai", "k", "u", "m", null, 5, 5, 5, 5, 60, null, null, null, null, "t"));

        service.deleteBackend("test");
        assertThat(backendRepo.findById("test")).isEmpty();
    }

    @Test
    void shouldRejectDeleteBackendWhenReferencedByProtocolMapping() {
        backendRepo.save(new BackendConfigEntity(
            "test", "openai", "k", "u", "m", null, 5, 5, 5, 5, 60, null, null, null, null, "t"));
        protocolRepo.save(new ProtocolMappingEntity(
            "openai", "test", true, "t", null));

        assertThatThrownBy(() -> service.deleteBackend("test"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("被路由引用");
    }

    @Test
    void shouldListProtocolMappings() {
        protocolRepo.save(new ProtocolMappingEntity(
            "gemini", "minimax-claude", true, "t", null));

        var list = service.listProtocolMappings();
        assertThat(list).hasSize(1);
        assertThat(list.get(0).backendCfgName()).isEqualTo("minimax-claude");
    }

    @Test
    void shouldSearchProtocolMappingsByClientProtocol() {
        protocolRepo.save(new ProtocolMappingEntity("openai", "deepseek", true, "t", null));
        protocolRepo.save(new ProtocolMappingEntity("openai", "moonshot", true, "t", null));
        protocolRepo.save(new ProtocolMappingEntity("gemini", "minimax", true, "t", null));

        var page = service.searchProtocolMappings("openai", 0, 10);

        assertThat(page.getContent()).hasSize(2);
        assertThat(page.getTotalElements()).isEqualTo(2);
        assertThat(page.getContent()).allSatisfy(m -> assertThat(m.clientProtocol()).isEqualTo("openai"));
    }

    @Test
    void shouldSearchAllProtocolMappingsWhenClientProtocolBlank() {
        protocolRepo.save(new ProtocolMappingEntity("openai", "deepseek", true, "t", null));
        protocolRepo.save(new ProtocolMappingEntity("gemini", "minimax", true, "t", null));

        var page = service.searchProtocolMappings(null, 0, 10);

        assertThat(page.getContent()).hasSize(2);
        assertThat(page.getTotalElements()).isEqualTo(2);
    }

    @Test
    void shouldPaginateProtocolMappings() {
        for (int i = 0; i < 12; i++) {
            protocolRepo.save(new ProtocolMappingEntity("openai", "b" + i, true, "t", null));
        }

        var page1 = service.searchProtocolMappings("openai", 0, 10);
        var page2 = service.searchProtocolMappings("openai", 1, 10);

        assertThat(page1.getContent()).hasSize(10);
        assertThat(page1.getTotalPages()).isEqualTo(2);
        assertThat(page2.getContent()).hasSize(2);
    }

    @Test
    void shouldSaveProtocolMappingWithModelMappings() {
        service.saveProtocolMapping(new ProtocolMappingEntity(
            "openai", "deepseek", true, "t",
            java.util.List.of(
                new ModelMappingEntity("openai", "deepseek", "gpt-4", "deepseek-v4", "t"),
                new ModelMappingEntity("openai", "deepseek", "claude-3", "glm-latest", "t"))));

        var detail = service.getProtocolMapping("openai", "deepseek");
        assertThat(detail).isNotNull();
        assertThat(detail.modelMappings()).hasSize(2);
        assertThat(detail.modelMappings()).extracting(ModelMappingEntity::requestModel)
            .containsExactly("claude-3", "gpt-4");
    }

    @Test
    void shouldFullReplaceModelMappingsOnResave() {
        service.saveProtocolMapping(new ProtocolMappingEntity(
            "openai", "deepseek", true, "t",
            java.util.List.of(
                new ModelMappingEntity("openai", "deepseek", "gpt-4", "deepseek-v4", "t"),
                new ModelMappingEntity("openai", "deepseek", "claude-3", "glm-latest", "t"))));

        service.saveProtocolMapping(new ProtocolMappingEntity(
            "openai", "deepseek", true, "t",
            java.util.List.of(
                new ModelMappingEntity("openai", "deepseek", "gpt-5", "deepseek-v5", "t"),
                new ModelMappingEntity("openai", "deepseek", "gpt-6", "deepseek-v6", "t"),
                new ModelMappingEntity("openai", "deepseek", "gpt-7", "deepseek-v7", "t"))));

        var detail = service.getProtocolMapping("openai", "deepseek");
        assertThat(detail.modelMappings()).hasSize(3);
        assertThat(detail.modelMappings()).extracting(ModelMappingEntity::requestModel)
            .containsExactly("gpt-5", "gpt-6", "gpt-7");
    }

    @Test
    void shouldNotTouchModelMappingsWhenModelMappingsNull() {
        service.saveProtocolMapping(new ProtocolMappingEntity(
            "openai", "deepseek", true, "t",
            java.util.List.of(
                new ModelMappingEntity("openai", "deepseek", "gpt-4", "deepseek-v4", "t"))));

        // toggleProtocol 场景：不传 modelMappings（Jackson 反序列化为 null）
        service.saveProtocolMapping(new ProtocolMappingEntity(
            "openai", "deepseek", false, "t", null));

        var detail = service.getProtocolMapping("openai", "deepseek");
        assertThat(detail.enabled()).isFalse();
        assertThat(detail.modelMappings()).hasSize(1);
        assertThat(detail.modelMappings().get(0).requestModel()).isEqualTo("gpt-4");
    }

    @Test
    void shouldClearModelMappingsWhenEmptyList() {
        service.saveProtocolMapping(new ProtocolMappingEntity(
            "openai", "deepseek", true, "t",
            java.util.List.of(
                new ModelMappingEntity("openai", "deepseek", "gpt-4", "deepseek-v4", "t"))));

        service.saveProtocolMapping(new ProtocolMappingEntity(
            "openai", "deepseek", true, "t", java.util.List.of()));

        var detail = service.getProtocolMapping("openai", "deepseek");
        assertThat(detail.modelMappings()).isEmpty();
    }

    @Test
    void shouldCascadeDeleteModelMappingsWhenProtocolDeleted() {
        service.saveProtocolMapping(new ProtocolMappingEntity(
            "openai", "deepseek", true, "t",
            java.util.List.of(
                new ModelMappingEntity("openai", "deepseek", "gpt-4", "deepseek-v4", "t"))));

        service.deleteProtocolMapping("openai", "deepseek");

        var detail = service.getProtocolMapping("openai", "deepseek");
        assertThat(detail).isNull();
        assertThat(modelRepo.findByOwner("openai", "deepseek")).isEmpty();
    }

    @Test
    void shouldReturnNullWhenProtocolMappingNotFound() {
        assertThat(service.getProtocolMapping("nonexistent", "none")).isNull();
    }

    @Test
    void shouldReturnDefaultModelWhenEnabledProtocolMappingExists() {
        backendRepo.save(new BackendConfigEntity(
            "deepseek", "responses", "k", "u", "deepseek-v3", null,
            5, 5, 5, 5, 60, null, null, null, null, "2026-06-01T00:00:00Z"));
        protocolRepo.save(new ProtocolMappingEntity(
            "responses", "deepseek", true, "2026-06-01T00:00:00Z", null));

        String model = service.findDefaultModelForClientProtocol("responses");
        assertThat(model).isEqualTo("deepseek-v3");
    }

    @Test
    void shouldReturnEmptyWhenNoEnabledProtocolMapping() {
        backendRepo.save(new BackendConfigEntity(
            "deepseek", "responses", "k", "u", "deepseek-v3", null,
            5, 5, 5, 5, 60, null, null, null, null, "2026-06-01T00:00:00Z"));
        protocolRepo.save(new ProtocolMappingEntity(
            "responses", "deepseek", false, "2026-06-01T00:00:00Z", null));

        String model = service.findDefaultModelForClientProtocol("responses");
        assertThat(model).isEmpty();
    }

    @Test
    void shouldReturnEmptyWhenBackendNotFound() {
        // 协议映射指向的后端名在 backend_config 表中不存在（脏数据）
        protocolRepo.save(new ProtocolMappingEntity(
            "responses", "ghost-backend", true, "2026-06-01T00:00:00Z", null));

        String model = service.findDefaultModelForClientProtocol("responses");
        assertThat(model).isEmpty();
    }

    @Test
    void shouldReturnDefaultModelFromMostRecentEnabledProtocolMapping() {
        // 同一 clientProtocol 下两条 enabled=true 但 updatedAt 不同的路由项，
        // 分别指向不同 defaultModel 的后端，断言返回 updatedAt 较大那条对应的后端 defaultModel。
        backendRepo.save(new BackendConfigEntity(
            "deepseek", "responses", "k", "u", "deepseek-v3", null,
            5, 5, 5, 5, 60, null, null, null, null, "2026-06-01T00:00:00Z"));
        backendRepo.save(new BackendConfigEntity(
            "moonshot", "responses", "k", "u", "moonshot-v1", null,
            5, 5, 5, 5, 60, null, null, null, null, "2026-06-01T00:00:00Z"));
        protocolRepo.save(new ProtocolMappingEntity(
            "responses", "deepseek", true, "2026-06-01T00:00:00Z", null));
        protocolRepo.save(new ProtocolMappingEntity(
            "responses", "moonshot", true, "2026-06-02T00:00:00Z", null));

        String model = service.findDefaultModelForClientProtocol("responses");
        assertThat(model).isEqualTo("moonshot-v1");
    }

    @Test
    void shouldInvalidateCacheOnSaveBackend() {
        backendRepo.save(new BackendConfigEntity(
            "cache-test", "openai", "k", "https://api.openai.com", "m1", null,
            5, 5, 5, 5, 60, null, null, null, null, "t"));
        BackendAdapter before = backendFactory.get("cache-test");

        // 改 defaultModel 触发 saveBackend
        service.saveBackend(new BackendConfigEntity(
            "cache-test", "openai", "k", "https://api.openai.com", "m2", null,
            5, 5, 5, 5, 60, null, null, null, null, "t"));
        BackendAdapter after = backendFactory.get("cache-test");

        assertThat(after).isNotSameAs(before);
    }

    @Test
    void shouldInvalidateCacheOnDeleteBackend() {
        backendRepo.save(new BackendConfigEntity(
            "cache-test-del", "openai", "k", "https://api.openai.com", "m1", null,
            5, 5, 5, 5, 60, null, null, null, null, "t"));
        backendFactory.get("cache-test-del");

        service.deleteBackend("cache-test-del");

        // 删除后 cache 应被清空，再 get 应抛"未找到后端"（DB 已无此 backend）
        assertThatThrownBy(() -> backendFactory.get("cache-test-del"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("未找到后端");
    }
}
