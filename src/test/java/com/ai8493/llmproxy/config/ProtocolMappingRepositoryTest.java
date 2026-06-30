package com.ai8493.llmproxy.config;

import static org.assertj.core.api.Assertions.*;

import com.ai8493.llmproxy.config.entity.ProtocolMappingEntity;
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
class ProtocolMappingRepositoryTest {

    @Autowired
    private ProtocolMappingRepository repo;
    @Autowired private BackendConfigRepository backendRepo;
    @Autowired private ModelMappingRepository modelRepo;

    // V6 迁移在 SpringContext 启动时灌入了种子数据（已提交，不在 @Transactional 内，不回滚）。
    // 每个测试方法执行前清空三张表，保证断言基于本方法插入的数据。
    @BeforeEach
    void cleanTables() {
        modelRepo.deleteAll();
        repo.deleteAll();
        backendRepo.deleteAll();
    }

    @Test
    void shouldFindByKey() {
        var entity = new ProtocolMappingEntity(
            "gemini", "minimax-claude", true, "t", null);
        repo.save(entity);

        var found = repo.findByKey("gemini", "minimax-claude");
        assertThat(found).isNotNull();
        assertThat(found.backendCfgName()).isEqualTo("minimax-claude");
        assertThat(found.enabled()).isTrue();
    }

    @Test
    void shouldFindByBackendCfgName() {
        repo.save(new ProtocolMappingEntity("openai", "deepseek", true, "t", null));
        repo.save(new ProtocolMappingEntity("gemini", "deepseek", true, "t", null));

        var refs = repo.findByBackendCfgName("deepseek");
        assertThat(refs).hasSize(2);
    }

    @Test
    void shouldDeleteByKey() {
        repo.save(new ProtocolMappingEntity("openai", "deepseek", true, "t", null));
        repo.deleteByKey("openai", "deepseek");
        assertThat(repo.findByKey("openai", "deepseek")).isNull();
    }

    @Test
    void shouldFindByClientProtocolLikePaged() {
        repo.save(new ProtocolMappingEntity("openai", "deepseek", true, "t", null));
        repo.save(new ProtocolMappingEntity("openai", "moonshot", true, "t", null));
        repo.save(new ProtocolMappingEntity("gemini", "minimax", true, "t", null));

        List<ProtocolMappingEntity> rows = repo.findByClientProtocolLikePaged("openai", 10, 0);
        long total = repo.countByClientProtocolLike("openai");

        assertThat(rows).hasSize(2);
        assertThat(total).isEqualTo(2);
        assertThat(rows).allSatisfy(m -> assertThat(m.clientProtocol()).isEqualTo("openai"));
    }

    @Test
    void shouldReturnAllWhenKwIsWildcard() {
        repo.save(new ProtocolMappingEntity("openai", "deepseek", true, "t", null));
        repo.save(new ProtocolMappingEntity("gemini", "minimax", true, "t", null));

        List<ProtocolMappingEntity> rows = repo.findByClientProtocolLikePaged("%", 10, 0);
        long total = repo.countByClientProtocolLike("%");

        assertThat(rows).hasSize(2);
        assertThat(total).isEqualTo(2);
    }

    @Test
    void shouldPaginateByClientProtocol() {
        for (int i = 0; i < 5; i++) {
            repo.save(new ProtocolMappingEntity("openai", "b" + i, true, "t", null));
        }

        List<ProtocolMappingEntity> page1 = repo.findByClientProtocolLikePaged("openai", 2, 0);
        assertThat(page1).hasSize(2);
        assertThat(repo.countByClientProtocolLike("openai")).isEqualTo(5);

        List<ProtocolMappingEntity> page3 = repo.findByClientProtocolLikePaged("openai", 2, 4);
        assertThat(page3).hasSize(1);
        assertThat(page3.get(0).backendCfgName()).isEqualTo("b4");
    }
}
