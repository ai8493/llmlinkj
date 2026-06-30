package com.ai8493.llmproxy.exception;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class GlobalExceptionHandlerTest {

    @Autowired
    private WebTestClient client;

    // 修复前：NoResourceFoundException 被 handleGeneral 兜底成 500 server_error 并记 ERROR
    // 修复后：专门的 handler 返回 404，不记 ERROR
    @Test
    void shouldReturn404ForMissingStaticResource() {
        client.get().uri("/missing-static-resource.txt").exchange()
            .expectStatus().isNotFound()
            .expectBody(String.class).value(s -> s.contains("\"type\":\"not_found\""));
    }
}
