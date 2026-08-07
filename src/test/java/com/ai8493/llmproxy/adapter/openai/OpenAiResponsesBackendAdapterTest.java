package com.ai8493.llmproxy.adapter.openai;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.*;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import com.ai8493.llmproxy.config.BackendConfig;
import com.ai8493.llmproxy.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.List;

@WireMockTest(httpPort = 8091)
class OpenAiResponsesBackendAdapterTest {

    private OpenAiResponsesBackendAdapter adapter;
    private BackendConfig config;

    @BeforeEach
    void setUp() {
        adapter = new OpenAiResponsesBackendAdapter("test-resp-backend");
        config = new BackendConfig(
            "openai-responses", "sk-test", "http://localhost:8091/v1",
            "gpt-4o", null,
            Duration.ofSeconds(5), Duration.ofSeconds(10), Duration.ofSeconds(5),
            new BackendConfig.PoolConfig(5, Duration.ofMinutes(1)),
            null);
        adapter.init(config);
    }

    private UnifiedChatRequest makeRequest(String userContent) {
        return UnifiedChatRequest.builder()
            .model("gpt-4o")
            .messages(List.of(UnifiedMessage.builder()
                .role(UnifiedMessage.Role.USER)
                .content(userContent)
                .build()))
            .stream(false)
            .build();
    }

    @Test
    void callReturnsResponseFromBackend() {
        stubFor(post(urlPathEqualTo("/v1/responses"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"id\":\"resp_1\",\"object\":\"response\"," +
                    "\"created_at\":1,\"model\":\"gpt-4o\",\"status\":\"completed\"," +
                    "\"output\":[{\"type\":\"message\",\"id\":\"msg_1\"," +
                    "\"status\":\"completed\",\"role\":\"assistant\"," +
                    "\"content\":[{\"type\":\"output_text\",\"text\":\"Hi!\",\"annotations\":[]}]}]," +
                    "\"usage\":{\"input_tokens\":5,\"output_tokens\":2,\"total_tokens\":7," +
                    "\"input_tokens_details\":{\"cached_tokens\":0}," +
                    "\"output_tokens_details\":{\"reasoning_tokens\":0}}}")));

        UnifiedChatResponse resp = adapter.call(makeRequest("Hello"));

        assertThat(resp.id()).isEqualTo("resp_1");
        assertThat(resp.choices()).hasSize(1);
        assertThat(resp.choices().get(0).message().content()).isEqualTo("Hi!");
        assertThat(resp.usage().totalTokens()).isEqualTo(7);
    }

    @Test
    void callThrowsOnBackendError() {
        stubFor(post(urlPathEqualTo("/v1/responses"))
            .willReturn(aResponse()
                .withStatus(401)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"error\":{\"message\":\"Invalid API key\"}}")));

        assertThatThrownBy(() -> adapter.call(makeRequest("Hi")))
            .isInstanceOf(com.ai8493.llmproxy.exception.BackendApiException.class);
    }

    @Test
    void streamReturnsChunksFromBackend() {
        // SSE 响应:response.created + output_text.delta + response.completed
        String sseBody = "event: response.created\n" +
            "data: {\"type\":\"response.created\",\"sequence_number\":0," +
            "\"response\":{\"id\":\"resp_1\",\"object\":\"response\",\"created_at\":1," +
            "\"model\":\"gpt-4o\",\"status\":\"in_progress\",\"output\":[]}}\n\n" +
            "event: response.output_text.delta\n" +
            "data: {\"type\":\"response.output_text.delta\",\"sequence_number\":1," +
            "\"item_id\":\"msg_1\",\"output_index\":0,\"content_index\":0," +
            "\"delta\":\"Hello\",\"logprobs\":[]}\n\n" +
            "event: response.completed\n" +
            "data: {\"type\":\"response.completed\",\"sequence_number\":2," +
            "\"response\":{\"id\":\"resp_1\",\"object\":\"response\",\"created_at\":1," +
            "\"model\":\"gpt-4o\",\"status\":\"completed\",\"output\":[]," +
            "\"usage\":{\"input_tokens\":5,\"output_tokens\":2,\"total_tokens\":7," +
            "\"input_tokens_details\":{\"cached_tokens\":0}," +
            "\"output_tokens_details\":{\"reasoning_tokens\":0}}}}\n\n";

        stubFor(post(urlPathEqualTo("/v1/responses"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "text/event-stream")
                .withBody(sseBody)));

        UnifiedChatRequest req = UnifiedChatRequest.builder()
            .model("gpt-4o")
            .messages(List.of(UnifiedMessage.builder()
                .role(UnifiedMessage.Role.USER)
                .content("Hi")
                .build()))
            .stream(true)
            .build();

        java.util.List<UnifiedChatResponse> chunks = adapter.stream(req).collectList().block();

        assertThat(chunks).isNotNull();
        // 至少有文本 delta + completed 两个 chunk
        assertThat(chunks.size()).isGreaterThanOrEqualTo(2);
        // 最后一个 chunk 应该有 finishReason 和 usage
        UnifiedChatResponse last = chunks.get(chunks.size() - 1);
        assertThat(last.choices().get(0).finishReason()).isEqualTo("completed");
        assertThat(last.usage()).isNotNull();
        assertThat(last.usage().totalTokens()).isEqualTo(7);
    }
}
