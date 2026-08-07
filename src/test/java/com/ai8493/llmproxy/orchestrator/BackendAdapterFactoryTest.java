package com.ai8493.llmproxy.orchestrator;

import static org.assertj.core.api.Assertions.*;

import com.ai8493.llmproxy.adapter.BackendAdapter;
import com.ai8493.llmproxy.adapter.openai.OpenAiResponsesBackendAdapter;
import com.ai8493.llmproxy.config.entity.BackendConfigEntity;
import com.ai8493.llmproxy.config.repository.BackendConfigRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class BackendAdapterFactoryTest {

    @Autowired private BackendAdapterFactory factory;
    @Autowired private BackendConfigRepository backendRepo;

    // 16 字段 BackendConfigEntity（与 ConfigServiceTest 一致的最小构造）
    private static BackendConfigEntity backend(String name, String defaultModel) {
        return new BackendConfigEntity(
            name, "openai", "k", "http://localhost:8089/v1", defaultModel,
            null, 5L, 10L, 5L, 5, 60L, null, null, null, null, "2026-01-01T00:00:00Z");
    }

    private static BackendConfigEntity backendWithProtocol(String name, String protocol) {
        return new BackendConfigEntity(
            name, protocol, "k", "http://localhost:8089/v1", "gpt-4o",
            null, 5L, 10L, 5L, 5, 60L, null, null, null, null, "2026-01-01T00:00:00Z");
    }

    @Test
    void shouldReturnSameInstanceBeforeInvalidate() {
        backendRepo.save(backend("alpha", "m1"));
        BackendAdapter a1 = factory.get("alpha");
        BackendAdapter a2 = factory.get("alpha");
        assertThat(a2).isSameAs(a1);
    }

    @Test
    void shouldReturnNewInstanceAfterInvalidate() {
        backendRepo.save(backend("beta", "m1"));
        BackendAdapter before = factory.get("beta");

        factory.invalidate("beta");

        BackendAdapter after = factory.get("beta");
        assertThat(after).isNotSameAs(before);
    }

    @Test
    void shouldNotThrowWhenInvalidateNonexistent() {
        // cache 中没有 "ghost"，invalidate 应 no-op 不抛异常
        assertThatNoException().isThrownBy(() -> factory.invalidate("ghost"));
    }

    @Test
    void shouldReturnOpenAiResponsesAdapterForOpenaiResponsesProtocol() {
        backendRepo.save(backendWithProtocol("resp-backend", "openai-responses"));
        BackendAdapter adapter = factory.get("resp-backend");

        assertThat(adapter).isInstanceOf(OpenAiResponsesBackendAdapter.class);
        assertThat(adapter.backendName()).isEqualTo("resp-backend");
        factory.invalidate("resp-backend");
    }
}
