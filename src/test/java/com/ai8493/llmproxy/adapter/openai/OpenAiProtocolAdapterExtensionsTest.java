package com.ai8493.llmproxy.adapter.openai;

import com.ai8493.llmproxy.model.UnifiedChatRequest;
import com.ai8493.llmproxy.model.UnifiedChatResponse;
import com.ai8493.llmproxy.model.UnifiedChoice;
import com.ai8493.llmproxy.model.UnifiedMessage;
import com.ai8493.llmproxy.model.UnifiedPart;
import com.ai8493.llmproxy.model.UnifiedToolChoice;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OpenAiProtocolAdapterExtensionsTest {

    private final OpenAiProtocolAdapter adapter = new OpenAiProtocolAdapter();

    private static final String BASE_BODY =
        "{\"model\":\"gpt-4\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}";

    @Test
    void shouldParseLogprobsAndSeed() {
        String body = "{\"model\":\"gpt-4\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],"
            + "\"logprobs\":true,\"top_logprobs\":5,\"seed\":42,\"n\":3}";
        UnifiedChatRequest req = adapter.toUnifiedRequest(body.getBytes(StandardCharsets.UTF_8), null);
        assertThat(req.openai()).isNotNull();
        assertThat(req.openai().logprobs()).isTrue();
        assertThat(req.openai().topLogprobs()).isEqualTo(5);
        assertThat(req.openai().seed()).isEqualTo(42L);
        assertThat(req.openai().n()).isEqualTo(3);
    }

    @Test
    void shouldParseResponseFormat() {
        String body = "{\"model\":\"gpt-4\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],"
            + "\"response_format\":{\"type\":\"json_object\"}}";
        UnifiedChatRequest req = adapter.toUnifiedRequest(body.getBytes(StandardCharsets.UTF_8), null);
        assertThat(req.openai()).isNotNull();
        assertThat(req.openai().responseFormat()).isNotNull();
        assertThat(req.openai().responseFormat().path("type").asText()).isEqualTo("json_object");
    }

    @Test
    void shouldDefaultExtensionsToNullWhenAbsent() {
        UnifiedChatRequest req = adapter.toUnifiedRequest(BASE_BODY.getBytes(StandardCharsets.UTF_8), null);
        assertThat(req.openai()).isNull();
    }

    @Test
    void shouldParseMultimodalContentToParts() throws Exception {
        var adapter = new OpenAiProtocolAdapter();
        String rawJson = """
            {
              "model": "gpt-4o",
              "messages": [
                {
                  "role": "user",
                  "content": [
                    {"type": "text", "text": "这是什么图片"},
                    {"type": "image_url", "image_url": {"url": "data:image/png;base64,iVBOR", "detail": "high"}}
                  ]
                }
              ]
            }
            """;
        var uReq = adapter.toUnifiedRequest(rawJson.getBytes(StandardCharsets.UTF_8), null);
        var msg = uReq.messages().get(0);
        assertThat(msg.parts()).isNotNull().hasSize(2);
        assertThat(msg.parts().get(0)).isInstanceOf(UnifiedPart.TextPart.class);
        assertThat(((UnifiedPart.TextPart) msg.parts().get(0)).text()).isEqualTo("这是什么图片");
        assertThat(msg.parts().get(1)).isInstanceOf(UnifiedPart.ImagePart.class);
        var img = (UnifiedPart.ImagePart) msg.parts().get(1);
        assertThat(img.imageData().path("url").asText("")).isEqualTo("data:image/png;base64,iVBOR");
        assertThat(img.imageData().path("detail").asText("")).isEqualTo("high");
    }

    @Test
    void shouldNotDowngradeRequiredToolChoice() throws Exception {
        var adapter = new OpenAiProtocolAdapter();
        String rawJson = """
            {
              "model": "gpt-4o",
              "messages": [{"role": "user", "content": "hi"}],
              "tools": [{"type": "function", "function": {"name": "f", "parameters": {}}}],
              "tool_choice": "required"
            }
            """;
        var uReq = adapter.toUnifiedRequest(rawJson.getBytes(StandardCharsets.UTF_8), null);
        assertThat(uReq.toolChoice()).isInstanceOf(UnifiedToolChoice.Any.class);
    }

    @Test
    void shouldDowngradeDeveloperToSystem() throws Exception {
        var adapter = new OpenAiProtocolAdapter();
        String rawJson = """
            {
              "model": "o3",
              "messages": [
                {"role": "developer", "content": "你是助手"},
                {"role": "user", "content": "hi"}
              ]
            }
            """;
        var uReq = adapter.toUnifiedRequest(rawJson.getBytes(StandardCharsets.UTF_8), null);
        var first = uReq.messages().get(0);
        assertThat(first.role()).isEqualTo(UnifiedMessage.Role.SYSTEM);
        assertThat(first.content()).isEqualTo("你是助手");
    }

    @Test
    void shouldParseAllConfigFields() throws Exception {
        var adapter = new OpenAiProtocolAdapter();
        String rawJson = """
            {
              "model": "o3",
              "messages": [{"role": "user", "content": "hi"}],
              "max_completion_tokens": 4096,
              "reasoning_effort": "high",
              "parallel_tool_calls": false,
              "presence_penalty": 0.5,
              "frequency_penalty": 0.3,
              "seed": 42
            }
            """;
        var uReq = adapter.toUnifiedRequest(rawJson.getBytes(StandardCharsets.UTF_8), null);
        var cfg = uReq.config();
        assertThat(cfg.maxCompletionTokens()).isEqualTo(4096);
        assertThat(cfg.reasoningEffort()).isEqualTo("high");
        assertThat(cfg.parallelToolCalls()).isFalse();
        assertThat(cfg.presencePenalty()).isEqualTo(0.5);
        assertThat(cfg.frequencyPenalty()).isEqualTo(0.3);
        assertThat(cfg.seed()).isEqualTo(42L);
    }

    @Test
    void shouldParseAllOpenAiExtensionsFields() throws Exception {
        var adapter = new OpenAiProtocolAdapter();
        String rawJson = """
            {
              "model": "gpt-4o",
              "messages": [{"role": "user", "content": "hi"}],
              "logit_bias": {"-123": 5},
              "metadata": {"user_id": "u123"},
              "store": false,
              "audio": {"voice": "alloy", "format": "wav"},
              "modalities": ["text", "audio"],
              "prediction": {"type": "content", "content": "预测内容"},
              "web_search_options": {"search_context_size": "medium"}
            }
            """;
        var uReq = adapter.toUnifiedRequest(rawJson.getBytes(StandardCharsets.UTF_8), null);
        var ext = uReq.openai();
        assertThat(ext).isNotNull();
        assertThat(ext.logitBias()).isNotNull();
        assertThat(ext.metadata()).isNotNull();
        assertThat(ext.store()).isFalse();
        assertThat(ext.audio()).isNotNull();
        assertThat(ext.modalities()).containsExactly("text", "audio");
        assertThat(ext.prediction()).isNotNull();
        assertThat(ext.webSearchOptions()).isNotNull();
    }

    @Test
    void shouldUseFinishReasonOriginalValueWhenValidOpenAi() throws Exception {
        var adapter = new OpenAiProtocolAdapter();
        // IR finishReason 是 OpenAI 合法值 "stop",出站应直接用
        var uResp = UnifiedChatResponse.builder()
            .id("chatcmpl-1")
            .model("gpt-4o")
            .created(100L)
            .choices(List.of(UnifiedChoice.builder()
                .index(0)
                .message(UnifiedMessage.builder()
                    .role(UnifiedMessage.Role.ASSISTANT)
                    .content("hi")
                    .build())
                .finishReason("stop")
                .build()))
            .build();

        byte[] out = adapter.fromUnifiedResponse(uResp);
        var json = new com.fasterxml.jackson.databind.ObjectMapper().readTree(out);
        String fr = json.path("choices").get(0).path("finish_reason").asText("");
        assertThat(fr).isEqualTo("stop");
    }

    @Test
    void shouldMapFinishReasonFromOtherProtocol() throws Exception {
        var adapter = new OpenAiProtocolAdapter();
        // IR finishReason 是 Gemini 的 "STOP"(跨协议),应映射到 OpenAI "stop"
        var uResp = UnifiedChatResponse.builder()
            .id("chatcmpl-1")
            .model("gpt-4o")
            .created(100L)
            .choices(List.of(UnifiedChoice.builder()
                .index(0)
                .message(UnifiedMessage.builder()
                    .role(UnifiedMessage.Role.ASSISTANT)
                    .content("hi")
                    .build())
                .finishReason("STOP")
                .build()))
            .build();

        byte[] out = adapter.fromUnifiedResponse(uResp);
        var json = new com.fasterxml.jackson.databind.ObjectMapper().readTree(out);
        String fr = json.path("choices").get(0).path("finish_reason").asText("");
        assertThat(fr).isEqualTo("stop");
    }
}
