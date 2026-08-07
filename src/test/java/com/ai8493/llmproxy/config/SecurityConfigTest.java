package com.ai8493.llmproxy.config;

import com.ai8493.llmproxy.config.entity.BackendConfigEntity;
import com.ai8493.llmproxy.config.entity.ProtocolMappingEntity;
import com.ai8493.llmproxy.config.repository.BackendConfigRepository;
import com.ai8493.llmproxy.config.repository.ProtocolMappingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest
@AutoConfigureWebTestClient
@ActiveProfiles("test")
class SecurityConfigTest {

    @Autowired
    private WebTestClient client;

    @Autowired
    private BackendConfigRepository backendRepo;

    @Autowired
    private ProtocolMappingRepository protocolRepo;

    @BeforeEach
    void setUpDb() {
        // 预置 1 条 backend_config + 3 条 protocol_mapping，让 /v1/models 能路由到 deepseek。
        // 本测试无 @WireMockTest，后端调用必失败 → 5xx，证明 SecurityConfig 放行了 /v1/*。
        backendRepo.save(new BackendConfigEntity(
            "deepseek", "openai", "test-key", "http://localhost:8089/v1", "gpt-4",
            null, 5L, 10L, 5L, 5, 60L, null, null, null, null, "2026-01-01T00:00:00Z"));
        protocolRepo.save(new ProtocolMappingEntity(
            "openai", "deepseek", true, "2026-01-01T00:00:00Z", null));
        protocolRepo.save(new ProtocolMappingEntity(
            "gemini", "deepseek", true, "2026-01-01T00:00:00Z", null));
        protocolRepo.save(new ProtocolMappingEntity(
            "responses", "deepseek", true, "2026-01-01T00:00:00Z", null));
    }

    @Test
    void shouldRedirectToLoginWhenNotAuthenticated() {
        client.get().uri("/admin/protocols").exchange()
            .expectStatus().is3xxRedirection()
            .expectHeader().valueMatches("Location", ".*/admin/login");
    }

    @Test
    void shouldAllowProxyPathWithoutAuth() {
        client.get().uri("/v1/models").exchange()
            .expectStatus().is5xxServerError();
    }
}
