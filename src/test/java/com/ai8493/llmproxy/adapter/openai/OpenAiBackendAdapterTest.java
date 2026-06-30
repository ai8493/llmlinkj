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

@WireMockTest(httpPort = 8090)
class OpenAiBackendAdapterTest {

    private OpenAiBackendAdapter adapter;
    private BackendConfig config;

    @BeforeEach
    void setUp() {
        adapter = new OpenAiBackendAdapter("test-backend");
        config = new BackendConfig(
            "openai", "sk-test", "http://localhost:8090/v1",
            "gpt-4", null,
            Duration.ofSeconds(5), Duration.ofSeconds(10), Duration.ofSeconds(5),
            new BackendConfig.PoolConfig(5, Duration.ofMinutes(1))
        );
        adapter.init(config);
    }

    private UnifiedChatRequest makeRequest(String model, String userContent) {
        return new UnifiedChatRequest(
            model,
            List.of(new UnifiedMessage(UnifiedMessage.Role.USER, userContent,
                null, null, null, null, null)),
            null, null, null, false
        );
    }

    @Test
    void nonStreamSuccess() {
        stubFor(post(urlPathEqualTo("/v1/chat/completions"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"id\":\"test-1\",\"object\":\"chat.completion\"," +
                    "\"created\":1,\"model\":\"gpt-4\"," +
                    "\"choices\":[{\"index\":0," +
                    "\"message\":{\"role\":\"assistant\",\"content\":\"Hi!\"}," +
                    "\"finish_reason\":\"stop\"}]," +
                    "\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":2,\"total_tokens\":7}}")));

        UnifiedChatResponse resp = adapter.call(makeRequest("gpt-4", "Hello"));

        assertThat(resp.choices()).hasSize(1);
        assertThat(resp.choices().get(0).message().content()).isEqualTo("Hi!");
        assertThat(resp.choices().get(0).finishReason()).isEqualTo("stop");
        assertThat(resp.usage()).isNotNull();
        assertThat(resp.usage().totalTokens()).isEqualTo(7);
    }

    @Test
    void nonStreamErrorResponse() {
        stubFor(post(urlPathEqualTo("/v1/chat/completions"))
            .willReturn(aResponse()
                .withStatus(401)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"error\":{\"message\":\"Invalid API key\"}}")));

        assertThatThrownBy(() -> adapter.call(makeRequest("gpt-4", "Hello")))
            .isInstanceOf(com.ai8493.llmproxy.exception.BackendApiException.class)
            .hasMessageContaining("OpenAI API 调用失败");
    }

    @Test
    void realToolCallsResponse() throws Exception {
        String fixture = new String(
            java.nio.file.Files.readAllBytes(
                java.nio.file.Path.of("src/test/resources/fixtures/deepseek-tool-calls-response.json")),
            java.nio.charset.StandardCharsets.UTF_8);

        stubFor(post(urlPathEqualTo("/v1/chat/completions"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(fixture)));

        UnifiedChatResponse resp = adapter.call(makeRequest("deepseek-v4-flash", "java -version"));

        assertThat(resp.choices()).hasSize(1);
        UnifiedMessage msg = resp.choices().get(0).message();
        assertThat(msg).isNotNull();
        assertThat(msg.role()).isEqualTo(UnifiedMessage.Role.ASSISTANT);
        // 新 adapter 不再从 reasoning_content 提取 UnifiedPart
        assertThat(msg.parts()).isNull();
        assertThat(msg.toolCalls()).isNotNull().hasSize(1);
        assertThat(msg.toolCalls().get(0).id()).isEqualTo("call_00_8C8XdaB9FBmXLXAiWUVj3964");
        assertThat(msg.toolCalls().get(0).type()).isEqualTo("function");
        assertThat(msg.toolCalls().get(0).function().name()).isEqualTo("run_shell_command");
        assertThat(msg.toolCalls().get(0).function().arguments()).isNotNull();
        assertThat(msg.toolCalls().get(0).function().arguments().get("command").asText())
            .isEqualTo("java -version");
        assertThat(msg.toolCalls().get(0).function().arguments().get("dir_path").asText())
            .isEqualTo("D:\\AI\\gemini-work");
        assertThat(resp.choices().get(0).finishReason()).isEqualTo("tool_calls");
        assertThat(resp.usage()).isNotNull();
        assertThat(resp.usage().totalTokens()).isEqualTo(10880);
    }

    @Test
    void streamToolCalls() throws Exception {
        String sseBody = new String(
            java.nio.file.Files.readAllBytes(
                java.nio.file.Path.of("src/test/resources/fixtures/deepseek-stream-tool-calls.txt")),
            java.nio.charset.StandardCharsets.UTF_8);

        stubFor(post(urlPathEqualTo("/v1/chat/completions"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "text/event-stream")
                .withBody(sseBody)));

        var geminiAdapter = new com.ai8493.llmproxy.adapter.gemini.GeminiProtocolAdapter();
        List<String> geminiChunks = new java.util.ArrayList<>();

        adapter.stream(makeRequest("deepseek-v4-flash", "java -version"))
            .map(geminiAdapter::fromUnifiedStreamChunk)
            .doOnNext(geminiChunks::add)
            .blockLast();

        System.out.println("=== Gemini SSE output (" + geminiChunks.size() + " chunks) ===");
        for (String c : geminiChunks) {
            System.out.println(c.trim());
        }

        // 遍历所有块查找 functionCall
        boolean foundFnCall = false;
        for (String c : geminiChunks) {
            String json = c.replace("data: ", "").trim();
            var root = new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
            var parts = root.get("candidates").get(0).get("content").get("parts");
            for (var p : parts) {
                if (p.has("functionCall")) {
                    foundFnCall = true;
                    assertThat(p.get("functionCall").get("name").asText()).isEqualTo("run_shell_command");
                    assertThat(p.get("functionCall").get("args").get("command").asText())
                        .isEqualTo("java -version");
                }
            }
        }
        assertThat(foundFnCall).as("应包含 functionCall part").isTrue();
    }

    @Test
    void nonStreamShouldExtractReasoningContent() {
        // 响应中包含 reasoning_content 扩展字段
        stubFor(post(urlPathEqualTo("/v1/chat/completions"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"id\":\"test-rc-1\",\"object\":\"chat.completion\"," +
                    "\"created\":1,\"model\":\"deepseek-v4-flash\"," +
                    "\"choices\":[{\"index\":0," +
                    "\"message\":{\"role\":\"assistant\",\"content\":\"最终答案\"," +
                    "\"reasoning_content\":\"我先思考一下...\"}," +
                    "\"finish_reason\":\"stop\"}]," +
                    "\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":2,\"total_tokens\":7}}")));

        UnifiedChatResponse resp = adapter.call(makeRequest("deepseek-v4-flash", "Hello"));

        assertThat(resp.choices()).hasSize(1);
        UnifiedMessage msg = resp.choices().get(0).message();
        assertThat(msg.content()).isEqualTo("最终答案");
        assertThat(msg.reasoningContent()).isEqualTo("我先思考一下...");
    }

    @Test
    void nonStreamShouldHandleNullReasoningContent() {
        // reasoning_content 为 null 时应忽略
        stubFor(post(urlPathEqualTo("/v1/chat/completions"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"id\":\"test-null-rc\",\"object\":\"chat.completion\"," +
                    "\"created\":1,\"model\":\"gpt-4o\"," +
                    "\"choices\":[{\"index\":0," +
                    "\"message\":{\"role\":\"assistant\",\"content\":\"无推理\",\"reasoning_content\":null}," +
                    "\"finish_reason\":\"stop\"}]," +
                    "\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":2,\"total_tokens\":7}}")));

        UnifiedChatResponse resp = adapter.call(makeRequest("gpt-4o", "Hello"));

        assertThat(resp.choices().get(0).message().reasoningContent()).isNull();
    }

    @Test
    void backendName() {
        assertThat(adapter.backendName()).isEqualTo("test-backend");
    }
}
