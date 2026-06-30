package com.ai8493.llmproxy.config;

import static org.assertj.core.api.Assertions.*;

import com.ai8493.llmproxy.config.entity.ModelMappingEntity;
import com.ai8493.llmproxy.config.repository.ModelMappingRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ModelMappingRepositoryTest {

    @Autowired
    private ModelMappingRepository repo;

    @Test
    void shouldUpsertByCompositeKey() {
        repo.save(new ModelMappingEntity("openai", "deepseek", "gpt-4", "deepseek-v4", "t"));
        repo.save(new ModelMappingEntity("openai", "deepseek", "gpt-4", "deepseek-v4-updated", "t2"));

        var rows = repo.findByOwner("openai", "deepseek");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).actualModel()).isEqualTo("deepseek-v4-updated");
    }

    @Test
    void shouldFindByOwnerOrderedByRequestModel() {
        repo.save(new ModelMappingEntity("openai", "deepseek", "claude-3", "glm-latest", "t"));
        repo.save(new ModelMappingEntity("openai", "deepseek", "gpt-4", "deepseek-v4", "t"));
        repo.save(new ModelMappingEntity("openai", "minimax", "gpt-4", "minimax-m3", "t"));

        var rows = repo.findByOwner("openai", "deepseek");
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).requestModel()).isEqualTo("claude-3");
        assertThat(rows.get(1).requestModel()).isEqualTo("gpt-4");
    }

    @Test
    void shouldDeleteByOwner() {
        repo.save(new ModelMappingEntity("openai", "deepseek", "gpt-4", "deepseek-v4", "t"));
        repo.save(new ModelMappingEntity("openai", "deepseek", "claude-3", "glm-latest", "t"));
        repo.save(new ModelMappingEntity("gemini", "minimax", "gpt-4", "minimax-m3", "t"));

        repo.deleteByOwner("openai", "deepseek");

        assertThat(repo.findByOwner("openai", "deepseek")).isEmpty();
        assertThat(repo.findByOwner("gemini", "minimax")).hasSize(1);
    }

    @Test
    void shouldDeleteByKey() {
        repo.save(new ModelMappingEntity("openai", "deepseek", "gpt-4", "deepseek-v4", "t"));
        repo.save(new ModelMappingEntity("openai", "deepseek", "claude-3", "glm-latest", "t"));

        repo.deleteByKey("openai", "deepseek", "gpt-4");

        var rows = repo.findByOwner("openai", "deepseek");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).requestModel()).isEqualTo("claude-3");
    }
}
