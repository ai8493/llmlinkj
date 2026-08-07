package com.ai8493.llmproxy.adapter.gemini;

import com.ai8493.llmproxy.model.UnifiedChatRequest;
import com.ai8493.llmproxy.model.UnifiedChatResponse;
import com.ai8493.llmproxy.model.UnifiedChoice;
import com.ai8493.llmproxy.model.UnifiedMessage;
import com.ai8493.llmproxy.model.UnifiedPart;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class GeminiProtocolAdapterExtensionsTest {

    private final GeminiProtocolAdapter adapter = new GeminiProtocolAdapter();

    private static final String BASE_BODY = "{\"contents\":[{\"role\":\"user\",\"parts\":[{\"text\":\"hi\"}]}]}";

    @Test
    void shouldParseResponseMimeTypeAndSchema() {
        String body = "{\"contents\":[{\"role\":\"user\",\"parts\":[{\"text\":\"hi\"}]}],\"generationConfig\":{\"responseMimeType\":\"application/json\",\"responseSchema\":{\"type\":\"object\"},\"candidateCount\":3}}";
        UnifiedChatRequest req = adapter.toUnifiedRequest(body.getBytes(StandardCharsets.UTF_8), Map.of());
        assertThat(req.gemini()).isNotNull();
        assertThat(req.gemini().responseMimeType()).isEqualTo("application/json");
        assertThat(req.gemini().responseSchema()).isNotNull();
        assertThat(req.gemini().candidateCount()).isEqualTo(3);
    }

    @Test
    void shouldParseSafetySettings() {
        String body = "{\"contents\":[{\"role\":\"user\",\"parts\":[{\"text\":\"hi\"}]}],\"safetySettings\":[{\"category\":\"HARM_CATEGORY_HARASSMENT\",\"threshold\":\"BLOCK_NONE\"}]}";
        UnifiedChatRequest req = adapter.toUnifiedRequest(body.getBytes(StandardCharsets.UTF_8), Map.of());
        assertThat(req.gemini()).isNotNull();
        assertThat(req.gemini().safetySettings()).isNotNull();
        assertThat(req.gemini().safetySettings().isArray()).isTrue();
    }

    @Test
    void shouldDefaultExtensionsToNullWhenAbsent() {
        UnifiedChatRequest req = adapter.toUnifiedRequest(BASE_BODY.getBytes(StandardCharsets.UTF_8), Map.of());
        assertThat(req.gemini()).isNull();
    }

    @Test
    void shouldPreserveThoughtSignatureInAssistantMessage() throws Exception {
        // 构造含 thoughtSignature 的 Gemini 请求(thought part 上带 signature)
        String rawJson = """
            {
              "contents": [
                {
                  "role": "user",
                  "parts": [{"text": "hi"}]
                },
                {
                  "role": "model",
                  "parts": [
                    {"thought": true, "text": "思考中", "thoughtSignature": "sig-abc123"},
                    {"text": "你好"}
                  ]
                }
              ]
            }
            """;
        var uReq = adapter.toUnifiedRequest(rawJson.getBytes(StandardCharsets.UTF_8), Map.of());
        var messages = uReq.messages();
        // 找到 assistant 消息(含 reasoningContent)
        UnifiedMessage assistant = messages.stream()
            .filter(m -> m.role() == UnifiedMessage.Role.ASSISTANT)
            .findFirst()
            .orElseThrow();
        assertThat(assistant.reasoningContent()).isEqualTo("思考中");
        assertThat(assistant.thinkingSignature()).isEqualTo("sig-abc123");
    }

    @Test
    void shouldUseFinishReasonOriginalValueWhenValidGemini() throws Exception {
        var adapter = new GeminiProtocolAdapter();
        // IR finishReason 是 Gemini 合法值 "MAX_TOKENS",出站应直接用
        var uResp = UnifiedChatResponse.builder()
            .model("gemini-pro")
            .choices(List.of(UnifiedChoice.builder()
                .index(0)
                .message(UnifiedMessage.builder()
                    .role(UnifiedMessage.Role.ASSISTANT)
                    .content("hi")
                    .build())
                .finishReason("MAX_TOKENS")
                .build()))
            .build();

        byte[] out = adapter.fromUnifiedResponse(uResp);
        var json = new com.fasterxml.jackson.databind.ObjectMapper().readTree(out);
        String fr = json.path("candidates").get(0).path("finishReason").asText("");
        assertThat(fr).isEqualTo("MAX_TOKENS");
    }

    @Test
    void shouldMapFinishReasonFromOtherProtocol() throws Exception {
        var adapter = new GeminiProtocolAdapter();
        // IR finishReason 是 OpenAI 的 "length"(跨协议),应映射到 Gemini "MAX_TOKENS"
        var uResp = UnifiedChatResponse.builder()
            .model("gemini-pro")
            .choices(List.of(UnifiedChoice.builder()
                .index(0)
                .message(UnifiedMessage.builder()
                    .role(UnifiedMessage.Role.ASSISTANT)
                    .content("hi")
                    .build())
                .finishReason("length")
                .build()))
            .build();

        byte[] out = adapter.fromUnifiedResponse(uResp);
        var json = new com.fasterxml.jackson.databind.ObjectMapper().readTree(out);
        String fr = json.path("candidates").get(0).path("finishReason").asText("");
        assertThat(fr).isEqualTo("MAX_TOKENS");
    }

    @Test
    void shouldSerializeImagePartToInlineData() throws Exception {
        var adapter = new GeminiProtocolAdapter();
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var imageData = mapper.createObjectNode();
        imageData.put("url", "data:image/png;base64,iVBORw0KGgo=");
        imageData.putNull("detail");
        var uResp = UnifiedChatResponse.builder()
            .model("gemini-pro")
            .choices(List.of(UnifiedChoice.builder()
                .index(0)
                .message(UnifiedMessage.builder()
                    .role(UnifiedMessage.Role.ASSISTANT)
                    .content("图片")
                    .parts(List.of(new UnifiedPart.ImagePart(imageData)))
                    .build())
                .finishReason("STOP")
                .build()))
            .build();

        byte[] out = adapter.fromUnifiedResponse(uResp);
        var json = mapper.readTree(out);
        var parts = json.path("candidates").get(0).path("content").path("parts");
        assertThat(parts.isArray()).isTrue();
        boolean hasInlineData = false;
        for (var p : parts) {
            if (p.has("inlineData")) {
                hasInlineData = true;
                assertThat(p.path("inlineData").path("mimeType").asText("")).isEqualTo("image/png");
            }
        }
        assertThat(hasInlineData).isTrue();
    }

    @Test
    void shouldSerializeFileDataPartToFileData() throws Exception {
        var adapter = new GeminiProtocolAdapter();
        var uResp = UnifiedChatResponse.builder()
            .model("gemini-pro")
            .choices(List.of(UnifiedChoice.builder()
                .index(0)
                .message(UnifiedMessage.builder()
                    .role(UnifiedMessage.Role.ASSISTANT)
                    .parts(List.of(new UnifiedPart.FileDataPart(
                        "gs://bucket/file.pdf", "application/pdf")))
                    .build())
                .finishReason("STOP")
                .build()))
            .build();

        byte[] out = adapter.fromUnifiedResponse(uResp);
        var json = new com.fasterxml.jackson.databind.ObjectMapper().readTree(out);
        var parts = json.path("candidates").get(0).path("content").path("parts");
        boolean hasFileData = false;
        for (var p : parts) {
            if (p.has("fileData")) {
                hasFileData = true;
                assertThat(p.path("fileData").path("fileUri").asText("")).isEqualTo("gs://bucket/file.pdf");
                assertThat(p.path("fileData").path("mimeType").asText("")).isEqualTo("application/pdf");
            }
        }
        assertThat(hasFileData).isTrue();
    }
}
