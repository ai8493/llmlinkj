package com.ai8493.llmproxy.controller;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest
@AutoConfigureWebTestClient
@ActiveProfiles("test")
class ProxyControllerHeadersTest {

    @Autowired
    private WebTestClient webClient;

    @Test
    void shouldAcceptAnthropicBetaHeader() {
        String requestBody = "{\"model\":\"claude-3-5-sonnet-20241022\",\"max_tokens\":100,\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}";
        webClient.post().uri("/v1/messages")
            .header("Content-Type", "application/json")
            .header("anthropic-beta", "prompt-caching-2024-07-31")
            .bodyValue(requestBody)
            .exchange()
            .expectStatus().value(status -> assertThat(status).satisfiesAnyOf(
                s -> assertThat(s).isBetween(200, 299),
                s -> assertThat(s).isBetween(500, 599)
            ));
    }
}
