package com.ai8493.llmproxy.adapter.openai;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.*;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import com.ai8493.llmproxy.config.BackendConfig;
import com.ai8493.llmproxy.model.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.Map;

@WireMockTest(httpPort = 8092)
class OpenAiResponsesEndToEndTest {

    private OpenAiResponsesBackendAdapter adapter;
    private ResponsesProtocolAdapter inboundAdapter;

    @BeforeEach
    void setUp() {
        adapter = new OpenAiResponsesBackendAdapter("e2e-resp");
        adapter.init(new BackendConfig(
            "openai-responses", "sk-test", "http://localhost:8092/v1",
            "gpt-4o", null,
            Duration.ofSeconds(5), Duration.ofSeconds(10), Duration.ofSeconds(5),
            new BackendConfig.PoolConfig(5, Duration.ofMinutes(1)),
            null));
        inboundAdapter = new ResponsesProtocolAdapter(null);
    }

    @Test
    void responsesInboundToResponsesBackendRoundTrip() throws Exception {
        // 1. 入站:Responses JSON -> IR(含 previous_response_id + include)
        String requestBody = "{\"model\":\"gpt-4o\",\"input\":[{\"type\":\"message\"," +
            "\"role\":\"user\",\"content\":[{\"type\":\"input_text\",\"text\":\"Hello\"}]}]," +
            "\"temperature\":0.7,\"max_output_tokens\":100," +
            "\"previous_response_id\":\"resp_abc123\"," +
            "\"include\":[\"file_search_call.results\"]}";
        UnifiedChatRequest irReq = inboundAdapter.toUnifiedRequest(
            requestBody.getBytes(), Map.of());

        assertThat(irReq.model()).isEqualTo("gpt-4o");
        assertThat(irReq.config().temperature()).isEqualTo(0.7);
        assertThat(irReq.openai().previousResponseId()).isEqualTo("resp_abc123");
        assertThat(irReq.openai().include()).containsExactly("file_search_call.results");

        // 2. IR -> 后端 SDK 请求参数(用 OpenAiResponsesRequestConverter 转,不实际调 WireMock)
        var reqConverter = new OpenAiResponsesRequestConverter();
        var params = reqConverter.convert(irReq);
        assertThat(params.model().get().asString()).isEqualTo("gpt-4o");
        assertThat(params.temperature()).isPresent().hasValue(0.7);
        assertThat(params.previousResponseId()).isPresent().hasValue("resp_abc123");
        assertThat(params.include()).isPresent();
        assertThat(params.include().get().get(0).asString()).isEqualTo("file_search_call.results");

        // 3. 后端响应 -> IR
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

        UnifiedChatResponse irResp = adapter.call(irReq);
        assertThat(irResp.choices().get(0).message().content()).isEqualTo("Hi!");

        // 4. IR -> 出站 Responses JSON
        byte[] outBytes = inboundAdapter.fromUnifiedResponse(irResp, irReq);
        JsonNode outNode = new ObjectMapper().readTree(outBytes);
        assertThat(outNode.get("object").asText()).isEqualTo("response");
        assertThat(outNode.get("output").get(0).get("content").get(0).get("text").asText())
            .isEqualTo("Hi!");
    }
}
