package com.ai8493.llmproxy.exception;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class GlobalExceptionHandlerTest {

    @Autowired
    private WebTestClient client;

    @Autowired
    private GlobalExceptionHandler handler;

    // 修复前：NoResourceFoundException 被 handleGeneral 兜底成 500 server_error 并记 ERROR
    // 修复后：专门的 handler 返回 404，不记 ERROR
    @Test
    void shouldReturn404ForMissingStaticResource() {
        client.get().uri("/missing-static-resource.txt").exchange()
            .expectStatus().isNotFound()
            .expectBody(String.class).value(s -> s.contains("\"type\":\"not_found\""));
    }

    // P2-8: TransformException -> 422 transform_error
    @Test
    void shouldReturn422ForTransformException() {
        var resp = handler.handleTransformError(new TransformException("未知消息类型"));

        assertThat(resp.getStatusCode().value()).isEqualTo(422);
        @SuppressWarnings("unchecked")
        var error = (java.util.Map<String, Object>) resp.getBody().get("error");
        assertThat(error.get("message")).isEqualTo("未知消息类型");
        assertThat(error.get("type")).isEqualTo("transform_error");
        assertThat(error.get("code")).isEqualTo(422);
    }

    @Test
    void shouldPreserveCauseInTransformException() {
        TransformException ex = new TransformException("转换失败", new RuntimeException("root cause"));
        assertThat(ex.getCause()).isInstanceOf(RuntimeException.class);
        assertThat(ex.getMessage()).isEqualTo("转换失败");
    }
}
