package com.ai8493.llmproxy.adapter.openai;

import com.ai8493.llmproxy.model.UnifiedChatRequest;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证 ResponsesProtocolAdapter 从 raw JSON 解析 OpenAI 专属字段(logprobs/seed/n/response_format)
 * 填充 OpenAiExtensions —— 这些字段 SDK ResponseCreateParams.Body 不支持,但客户端可能误传。
 */
class ResponsesProtocolAdapterExtensionsTest {

    private final ResponsesProtocolAdapter adapter = new ResponsesProtocolAdapter(null);

    private static final String BASE_BODY = "{\"model\":\"gpt-4o\",\"input\":\"hi\"}";

    @Test
    void shouldParseSeedFromRawJsonEvenIfSdkUnsupported() {
        String body = "{\"model\":\"gpt-4o\",\"input\":\"hi\",\"seed\":42,\"n\":3}";
        UnifiedChatRequest req = adapter.toUnifiedRequest(body.getBytes(StandardCharsets.UTF_8), null);
        assertThat(req.openai()).isNotNull();
        assertThat(req.openai().seed()).isEqualTo(42L);
        assertThat(req.openai().n()).isEqualTo(3);
    }

    @Test
    void shouldParseLogprobsAndTopLogprobsFromRawJson() {
        String body = "{\"model\":\"gpt-4o\",\"input\":\"hi\",\"logprobs\":true,\"top_logprobs\":5}";
        UnifiedChatRequest req = adapter.toUnifiedRequest(body.getBytes(StandardCharsets.UTF_8), null);
        assertThat(req.openai()).isNotNull();
        assertThat(req.openai().logprobs()).isTrue();
        assertThat(req.openai().topLogprobs()).isEqualTo(5);
    }

    @Test
    void shouldParseResponseFormatFromRawJson() {
        String body = "{\"model\":\"gpt-4o\",\"input\":\"hi\",\"response_format\":{\"type\":\"json_object\"}}";
        UnifiedChatRequest req = adapter.toUnifiedRequest(body.getBytes(StandardCharsets.UTF_8), null);
        assertThat(req.openai()).isNotNull();
        assertThat(req.openai().responseFormat()).isNotNull();
        assertThat(req.openai().responseFormat().get("type").asText()).isEqualTo("json_object");
    }

    @Test
    void shouldDefaultExtensionsToNullWhenAbsent() {
        UnifiedChatRequest req = adapter.toUnifiedRequest(BASE_BODY.getBytes(StandardCharsets.UTF_8), null);
        assertThat(req.openai()).isNull();
    }
}
