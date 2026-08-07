package com.ai8493.llmproxy.adapter.openai;

import com.ai8493.llmproxy.model.*;
import com.ai8493.llmproxy.model.extensions.OpenAiExtensions;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class OpenAiRequestConverterExtensionsTest {

    private final OpenAiRequestConverter converter = new OpenAiRequestConverter();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void shouldRebuildLogprobsSeedNFromExtensions() {
        OpenAiExtensions ext = OpenAiExtensions.builder()
            .logprobs(true)
            .topLogprobs(5)
            .seed(42L)
            .n(3)
            .build();
        UnifiedChatRequest req = UnifiedChatRequest.builder()
            .model("gpt-4")
            .messages(List.of(UnifiedMessage.builder().role(UnifiedMessage.Role.USER).content("hi").build()))
            .openai(ext)
            .build();

        ChatCompletionCreateParams params = converter.convert(req);

        assertThat(params.logprobs()).hasValue(true);
        assertThat(params.topLogprobs()).hasValue(5L);
        assertThat(params.seed()).hasValue(42L);
        assertThat(params.n()).hasValue(3L);
    }

    @Test
    void shouldRebuildResponseFormatJsonObject() {
        ObjectNode rf = mapper.createObjectNode().put("type", "json_object");
        OpenAiExtensions ext = OpenAiExtensions.builder().responseFormat(rf).build();
        UnifiedChatRequest req = UnifiedChatRequest.builder()
            .model("gpt-4")
            .messages(List.of(UnifiedMessage.builder().role(UnifiedMessage.Role.USER).content("hi").build()))
            .openai(ext)
            .build();

        ChatCompletionCreateParams params = converter.convert(req);

        assertThat(params.responseFormat()).isPresent();
        assertThat(params.responseFormat().get().isJsonObject()).isTrue();
    }

    @Test
    void shouldRebuildResponseFormatText() {
        ObjectNode rf = mapper.createObjectNode().put("type", "text");
        OpenAiExtensions ext = OpenAiExtensions.builder().responseFormat(rf).build();
        UnifiedChatRequest req = UnifiedChatRequest.builder()
            .model("gpt-4")
            .messages(List.of(UnifiedMessage.builder().role(UnifiedMessage.Role.USER).content("hi").build()))
            .openai(ext)
            .build();

        ChatCompletionCreateParams params = converter.convert(req);

        assertThat(params.responseFormat()).isPresent();
        assertThat(params.responseFormat().get().isText()).isTrue();
    }

    @Test
    void shouldNotSetExtensionsFieldsWhenAbsent() {
        UnifiedChatRequest req = UnifiedChatRequest.builder()
            .model("gpt-4")
            .messages(List.of(UnifiedMessage.builder().role(UnifiedMessage.Role.USER).content("hi").build()))
            .build();

        ChatCompletionCreateParams params = converter.convert(req);

        assertThat(params.logprobs()).isEmpty();
        assertThat(params.seed()).isEmpty();
        assertThat(params.n()).isEmpty();
        assertThat(params.responseFormat()).isEmpty();
    }

    @Test
    void shouldNotDowngradeJsonSchemaResponseFormat() throws Exception {
        var rfNode = mapper.readTree("""
            {
              "type": "json_schema",
              "json_schema": {
                "name": "weather",
                "strict": true,
                "schema": {
                  "type": "object",
                  "properties": {"temp": {"type": "number"}},
                  "required": ["temp"]
                }
              }
            }
            """);
        var ext = OpenAiExtensions.builder().responseFormat(rfNode).build();
        var uReq = UnifiedChatRequest.builder()
            .model("gpt-4o")
            .messages(List.of(UnifiedMessage.builder()
                .role(UnifiedMessage.Role.USER)
                .content("天气")
                .build()))
            .openai(ext)
            .stream(false)
            .build();

        var converter = new OpenAiRequestConverter();
        var params = converter.convert(uReq);
        // 应保留 json_schema,非降级为 json_object
        var rf = params.responseFormat().get();
        assertThat(rf.isJsonSchema()).isTrue();
    }

    @Test
    void shouldMapAllExtensionsFieldsToParams() throws Exception {
        var mapper = new ObjectMapper();
        var ext = OpenAiExtensions.builder()
            .logitBias(mapper.readTree("{\"-123\": 5}"))
            .metadata(mapper.readTree("{\"user_id\": \"u123\"}"))
            .store(false)
            .audio(mapper.readTree("{\"voice\": \"alloy\", \"format\": \"wav\"}"))
            .modalities(List.of("text", "audio"))
            .prediction(mapper.readTree("{\"type\": \"content\", \"content\": \"预测\"}"))
            .webSearchOptions(mapper.readTree("{\"search_context_size\": \"medium\"}"))
            .build();
        var uReq = UnifiedChatRequest.builder()
            .model("gpt-4o")
            .messages(List.of(UnifiedMessage.builder()
                .role(UnifiedMessage.Role.USER)
                .content("hi")
                .build()))
            .openai(ext)
            .stream(false)
            .build();

        var converter = new OpenAiRequestConverter();
        var params = converter.convert(uReq);
        // store
        assertThat(params.store()).hasValue(false);
        // modalities
        assertThat(params.modalities()).isPresent();
        // 其余字段通过 additionalProperties 或 SDK builder 验证
    }
}
