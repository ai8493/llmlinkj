package com.ai8493.llmproxy.controller;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.*;

import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import com.ai8493.llmproxy.config.entity.BackendConfigEntity;
import com.ai8493.llmproxy.config.entity.ProtocolMappingEntity;
import com.ai8493.llmproxy.config.repository.BackendConfigRepository;
import com.ai8493.llmproxy.config.repository.ModelMappingRepository;
import com.ai8493.llmproxy.config.repository.ProtocolMappingRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@WireMockTest(httpPort = 8089)
@ActiveProfiles("test")
class ProxyControllerTest {

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private BackendConfigRepository backendRepo;

    @Autowired
    private ProtocolMappingRepository protocolRepo;

    @Autowired
    private ModelMappingRepository modelRepo;

    @BeforeEach
    void setUpDb() {
        // V6 迁移在 SpringContext 启动时灌入了种子数据（已提交），且本类非 @Transactional 不会自动回滚。
        // 每个测试方法执行前清空三张表，保证路由命中本方法预置的数据而非种子。
        modelRepo.deleteAll();
        protocolRepo.deleteAll();
        backendRepo.deleteAll();

        // 预置 1 条 backend_config：deepseek（protocol=openai，base-url 指向 WireMock）
        backendRepo.save(new BackendConfigEntity(
            "deepseek",                  // name
            "openai",                    // protocol
            "test-key",                  // apiKey
            "http://localhost:8089/v1",   // baseUrl
            "gpt-4",                     // defaultModel
            null,                        // defaultMaxTokens
            5L,                          // connectTimeout
            10L,                         // readTimeout
            5L,                          // writeTimeout
            5,                           // maxIdleConnections
            60L,                         // keepAliveDuration
            null,                        // reasoningEffortMode
            null,                        // reasoningEffortDefault
            null,                        // thinkingDefaultType
            null,                        // thinkingDefaultBudget
            "2026-01-01T00:00:00Z"       // updatedAt
        ));

        // 预置 3 条 protocol_mapping：openai/gemini/responses 均路由到 deepseek
        protocolRepo.save(new ProtocolMappingEntity(
            "openai", "deepseek", true, "2026-01-01T00:00:00Z", null));
        protocolRepo.save(new ProtocolMappingEntity(
            "gemini", "deepseek", true, "2026-01-01T00:00:00Z", null));
        protocolRepo.save(new ProtocolMappingEntity(
            "responses", "deepseek", true, "2026-01-01T00:00:00Z", null));
    }

    private static String readFixture(String path) throws IOException {
        return Files.readString(
            new ClassPathResource("fixtures/" + path).getFile().toPath());
    }

    private static Map<String, Object> openaiRequest(String model, List<Map<String, String>> messages) {
        return Map.of("model", model, "messages", messages);
    }

    private static Map<String, String> userMessage(String content) {
        return Map.of("role", "user", "content", content);
    }

    // ===== OpenAI endpoints =====

    @Test
    void openaiTextResponse() throws Exception {
        stubFor(post(urlPathEqualTo("/v1/chat/completions"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(readFixture("openai-text-response.json"))));

        Map<String, Object> requestBody = openaiRequest("gpt-4",
            List.of(userMessage("Hello")));

        webTestClient.post()
            .uri("/v1/chat/completions")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(requestBody)
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.choices[0].message.content").isEqualTo("Hello! How can I help you?")
            .jsonPath("$.choices[0].finish_reason").isEqualTo("stop");
    }

    @Test
    void openaiSafetyBlock() throws Exception {
        stubFor(post(urlPathEqualTo("/v1/chat/completions"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(readFixture("openai-safety-response.json"))));

        Map<String, Object> requestBody = openaiRequest("gpt-4",
            List.of(userMessage("Hello")));

        webTestClient.post()
            .uri("/v1/chat/completions")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(requestBody)
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.choices[0].finish_reason").isEqualTo("content_filter");
    }

    @Test
    void openaiEmptyMessages() {
        Map<String, Object> requestBody = Map.of(
            "model", "gpt-4",
            "messages", List.of()
        );

        webTestClient.post()
            .uri("/v1/chat/completions")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(requestBody)
            .exchange()
            .expectStatus().isBadRequest();
    }

    // ===== Models 端点测试 =====

    @Test
    void openaiModelsList() throws Exception {
        stubFor(get(urlPathEqualTo("/v1/models"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                    {"object":"list","data":[
                        {"id":"gpt-4","object":"model","created":1687882411,"owned_by":"openai"},
                        {"id":"gpt-4o","object":"model","created":1712361600,"owned_by":"openai"}
                    ]}
                    """)));

        webTestClient.get()
            .uri("/v1/models")
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.object").isEqualTo("list")
            .jsonPath("$.data.length()").isEqualTo(2)
            .jsonPath("$.data[0].id").isEqualTo("gpt-4")
            .jsonPath("$.data[1].id").isEqualTo("gpt-4o");
    }

    @Test
    void geminiModelsList() throws Exception {
        stubFor(get(urlPathEqualTo("/v1/models"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                    {"object":"list","data":[
                        {"id":"gpt-4","object":"model","created":1687882411,"owned_by":"openai"}
                    ]}
                    """)));

        webTestClient.get()
            .uri("/v1beta/models")
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.models.length()").isEqualTo(1)
            .jsonPath("$.models[0].name").isEqualTo("models/gpt-4")
            .jsonPath("$.models[0].supportedActions[0]").isEqualTo("generateContent");
    }

    @Test
    void geminiModelsEmpty() throws Exception {
        stubFor(get(urlPathEqualTo("/v1/models"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"object\":\"list\",\"data\":[]}")));

        webTestClient.get()
            .uri("/v1beta/models")
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.models.length()").isEqualTo(0);
    }

    // ===== Gemini endpoints =====

    @Test
    void geminiTextResponse() throws Exception {
        stubFor(post(urlPathEqualTo("/v1/chat/completions"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(readFixture("openai-text-response.json"))));

        Map<String, Object> requestBody = Map.of(
            "contents", List.of(Map.of(
                "role", "user",
                "parts", List.of(Map.of("text", "Hello"))
            )),
            "model", "gemini-2.0-flash-exp"
        );

        webTestClient.post()
            .uri("/v1beta/models/gemini-2.0-flash-exp:generateContent")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(requestBody)
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.candidates[0].content.parts[0].text").isEqualTo("Hello! How can I help you?");
    }

    // ===== Gemini 流式——真实报文全链路测试 =====

    @Test
    void geminiRealRequestStream() throws Exception {
        stubFor(post(urlPathEqualTo("/v1/chat/completions"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "text/event-stream")
                .withBody(
                    // 模拟各种边界值：delta 有内容、delta 空对象、finish_reason=null、最后一块 finish_reason=stop
                    "data: {\"id\":\"1\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"x\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"Hello\"},\"finish_reason\":null}]}\n\n" +
                    "data: {\"id\":\"2\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"x\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":null}]}\n\n" +
                    "data: {\"id\":\"3\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"x\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\" world\"},\"finish_reason\":null}]}\n\n" +
                    "data: {\"id\":\"4\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"x\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n" +
                    "data: [DONE]\n\n"
                )));

        String requestBody = readFixture("gemini-cli-real-request.json");

        webTestClient.post()
            .uri("/v1beta/models/gemini-3-pro:streamGenerateContent")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(requestBody)
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .consumeWith(result -> {
                String body = new String(result.getResponseBody(), java.nio.charset.StandardCharsets.UTF_8);
                assertThat(body).contains("data:");
                assertThat(body).doesNotContain("\"server_error\"");
            });

    }

    // ===== Gemini 流式——thoughtSignature 剥离测试 =====

    @Test
    void geminiThoughtSignatureStream() throws Exception {
        stubFor(post(urlPathEqualTo("/v1/chat/completions"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "text/event-stream")
                .withBody(
                    "data: {\"id\":\"1\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"x\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"java\"},\"finish_reason\":null}]}\n\n" +
                    "data: {\"id\":\"2\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"x\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n" +
                    "data: [DONE]\n\n"
                )));

        String requestBody = readFixture("gemini-thought-signature-request.json");

        webTestClient.post()
            .uri("/v1beta/models/gemini-3-pro:streamGenerateContent")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(requestBody)
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .consumeWith(result -> {
                String body = new String(result.getResponseBody(), java.nio.charset.StandardCharsets.UTF_8);
                assertThat(body).contains("data:");
                assertThat(body).doesNotContain("\"server_error\"");
            });
    }

    // ===== Responses API 端点 =====

    @Test
    void responsesNonStreamText() throws Exception {
        stubFor(post(urlPathEqualTo("/v1/chat/completions"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(readFixture("openai-text-response.json"))));

        Map<String, Object> requestBody = Map.of(
            "model", "gpt-4o",
            "input", "Hello"
        );

        webTestClient.post()
            .uri("/v1/responses")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(requestBody)
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.object").isEqualTo("response")
            .jsonPath("$.status").isEqualTo("completed")
            .jsonPath("$.output[0].type").isEqualTo("message")
            .jsonPath("$.output[0].content[0].type").isEqualTo("output_text")
            .jsonPath("$.output[0].content[0].text").isEqualTo("Hello! How can I help you?");
    }

    @Test
    void responsesRejectStoreTrue() {
        Map<String, Object> requestBody = Map.of(
            "model", "gpt-4o",
            "input", "Hello",
            "store", true
        );

        webTestClient.post()
            .uri("/v1/responses")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(requestBody)
            .exchange()
            .expectStatus().isBadRequest();
    }

    @Test
    void responsesStream() throws Exception {
        stubFor(post(urlPathEqualTo("/v1/chat/completions"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "text/event-stream")
                .withBody(
                    "data: {\"id\":\"1\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"x\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"Hello\"},\"finish_reason\":null}]}\n\n" +
                    "data: {\"id\":\"2\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"x\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\" world\"},\"finish_reason\":null}]}\n\n" +
                    "data: {\"id\":\"3\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"x\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n" +
                    "data: [DONE]\n\n"
                )));

        Map<String, Object> requestBody = Map.of(
            "model", "gpt-4o",
            "input", "Hello",
            "stream", true
        );

        webTestClient.post()
            .uri("/v1/responses")
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.TEXT_EVENT_STREAM)
            .bodyValue(requestBody)
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .consumeWith(result -> {
                String body = new String(result.getResponseBody(), java.nio.charset.StandardCharsets.UTF_8);
                assertThat(body).contains("response.created");
                assertThat(body).contains("response.in_progress");
                assertThat(body).contains("response.output_text.delta");
                assertThat(body).contains("Hello");
                assertThat(body).contains(" world");
                assertThat(body).contains("response.completed");
            });
    }

}
