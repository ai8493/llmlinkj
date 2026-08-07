package com.ai8493.llmproxy.config;

import static org.assertj.core.api.Assertions.*;

import com.ai8493.llmproxy.config.entity.BackendConfigEntity;
import com.ai8493.llmproxy.config.repository.BackendConfigRepository;
import com.ai8493.llmproxy.config.repository.ModelMappingRepository;
import com.ai8493.llmproxy.config.repository.ProtocolMappingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class BackendConfigRepositoryTest {

    @Autowired
    private BackendConfigRepository repo;
    @Autowired private ProtocolMappingRepository protocolRepo;
    @Autowired private ModelMappingRepository modelRepo;

    // V6 迁移在 SpringContext 启动时灌入了种子数据（已提交，不在 @Transactional 内，不回滚）。
    // 每个测试方法执行前清空三张表，保证断言基于本方法插入的数据。
    @BeforeEach
    void cleanTables() {
        modelRepo.deleteAll();
        protocolRepo.deleteAll();
        repo.deleteAll();
    }

    @Test
    void shouldSaveAndFindByName() {
        var entity = new BackendConfigEntity(
            "test-backend", "openai", "sk-test", "http://localhost:8089/v1",
            "gpt-4", 65536, 10, 600, 30,
            20, 300, null, null, null, null, "2026-06-23T00:00:00Z");

        repo.save(entity);
        var found = repo.findById("test-backend").orElseThrow();

        assertThat(found.protocol()).isEqualTo("openai");
        assertThat(found.defaultMaxTokens()).isEqualTo(65536);
    }

    @Test
    void shouldUpdateOnDuplicateName() {
        var entity = new BackendConfigEntity(
            "dup", "openai", "k1", "u1", "m1", null,
            5, 5, 5, 5, 60, null, null, null, null, "t");
        repo.save(entity);

        var updated = new BackendConfigEntity(
            "dup", "anthropic", "k2", "u2", "m2", 100,
            5, 5, 5, 5, 60, null, null, null, null, "t");
        repo.save(updated);

        assertThat(repo.count()).isEqualTo(1);
        assertThat(repo.findById("dup").orElseThrow().protocol()).isEqualTo("anthropic");
    }

    @Test
    void shouldFindByNameLikePaged() {
        repo.save(new BackendConfigEntity("test-openai", "openai", "k", "u", "m", null, 5, 5, 5, 5, 60, null, null, null, null, "t"));
        repo.save(new BackendConfigEntity("test-gemini", "gemini", "k", "u", "m", null, 5, 5, 5, 5, 60, null, null, null, null, "t"));
        repo.save(new BackendConfigEntity("prod-deepseek", "openai", "k", "u", "m", null, 5, 5, 5, 5, 60, null, null, null, null, "t"));

        List<BackendConfigEntity> rows = repo.findByNameLikePaged("%test%", 10, 0);
        long total = repo.countByNameLike("%test%");

        assertThat(rows).hasSize(2);
        assertThat(total).isEqualTo(2);
        assertThat(rows).allSatisfy(b -> assertThat(b.name()).contains("test"));
    }

    @Test
    void shouldReturnEmptyWhenNameNoMatch() {
        repo.save(new BackendConfigEntity("test-openai", "openai", "k", "u", "m", null, 5, 5, 5, 5, 60, null, null, null, null, "t"));

        List<BackendConfigEntity> rows = repo.findByNameLikePaged("%nonexistent%", 10, 0);
        long total = repo.countByNameLike("%nonexistent%");

        assertThat(rows).isEmpty();
        assertThat(total).isZero();
    }

    @Test
    void shouldPaginateWithLimitOffset() {
        for (int i = 0; i < 5; i++) {
            repo.save(new BackendConfigEntity(
                "b" + i, "openai", "k", "u", "m", null, 5, 5, 5, 5, 60, null, null, null, null, "t"));
        }

        // 第 1 页（size=2）：b0, b1
        List<BackendConfigEntity> page1 = repo.findByNameLikePaged("%", 2, 0);
        assertThat(page1).hasSize(2);
        assertThat(repo.countByNameLike("%")).isEqualTo(5);

        // 第 3 页（offset=4）：b4
        List<BackendConfigEntity> page3 = repo.findByNameLikePaged("%", 2, 4);
        assertThat(page3).hasSize(1);
        assertThat(page3.get(0).name()).isEqualTo("b4");
    }

    @Test
    void shouldSaveAndReadNullApiKeyAndDefaultModel() {
        // V5 后 api_key 与 default_model 可空，验证 NULL 能写入并读回 null
        var entity = new BackendConfigEntity(
            "nullable-backend", "openai", null, "http://localhost:8089/v1",
            null, 65536, 10, 600, 30,
            20, 300, null, null, null, null, "2026-06-27T00:00:00Z");

        repo.save(entity);
        var found = repo.findById("nullable-backend").orElseThrow();

        assertThat(found.apiKey()).isNull();
        assertThat(found.defaultModel()).isNull();
        assertThat(found.name()).isEqualTo("nullable-backend");
    }
}
