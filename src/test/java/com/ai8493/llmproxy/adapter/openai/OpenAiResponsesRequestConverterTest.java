package com.ai8493.llmproxy.adapter.openai;

import com.ai8493.llmproxy.model.*;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseInputItem;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class OpenAiResponsesRequestConverterTest {

    private final OpenAiResponsesRequestConverter converter = new OpenAiResponsesRequestConverter();

    @Test
    void convertsBasicUserMessageToInputItems() {
        UnifiedChatRequest req = UnifiedChatRequest.builder()
            .model("gpt-4o")
            .messages(List.of(UnifiedMessage.builder()
                .role(UnifiedMessage.Role.USER)
                .content("Hello")
                .build()))
            .stream(false)
            .build();

        ResponseCreateParams params = converter.convert(req);

        assertThat(params.model().get().asString()).isEqualTo("gpt-4o");
        assertThat(params.input().isPresent()).isTrue();
        var input = params.input().get();
        assertThat(input.isResponse()).isTrue();
        assertThat(input.asResponse()).hasSize(1);
        var first = input.asResponse().get(0);
        assertThat(first.isEasyInputMessage()).isTrue();
        assertThat(first.asEasyInputMessage().role().asString()).isEqualTo("user");
    }

    @Test
    void convertsSystemMessageToInstructions() {
        UnifiedChatRequest req = UnifiedChatRequest.builder()
            .model("gpt-4o")
            .messages(List.of(
                UnifiedMessage.builder()
                    .role(UnifiedMessage.Role.SYSTEM)
                    .content("Be concise")
                    .build(),
                UnifiedMessage.builder()
                    .role(UnifiedMessage.Role.USER)
                    .content("Hi")
                    .build()))
            .stream(false)
            .build();

        ResponseCreateParams params = converter.convert(req);

        assertThat(params.instructions().isPresent()).isTrue();
        assertThat(params.instructions().get()).isEqualTo("Be concise");
        // input 只剩 user 消息
        assertThat(params.input().get().asResponse()).hasSize(1);
    }

    @Test
    void convertsGenerationConfigFields() {
        UnifiedChatRequest req = UnifiedChatRequest.builder()
            .model("gpt-4o")
            .messages(List.of(UnifiedMessage.builder()
                .role(UnifiedMessage.Role.USER)
                .content("Hi")
                .build()))
            .config(UnifiedGenerationConfig.builder()
                .temperature(0.7)
                .topP(0.9)
                .maxOutputTokens(1024)
                .parallelToolCalls(false)
                .reasoningEffort("high")
                .user("user-123")
                .build())
            .stream(false)
            .build();

        ResponseCreateParams params = converter.convert(req);

        assertThat(params.temperature()).isPresent().hasValue(0.7);
        assertThat(params.topP()).isPresent().hasValue(0.9);
        assertThat(params.maxOutputTokens()).isPresent().hasValue(1024L);
        assertThat(params.parallelToolCalls()).isPresent().hasValue(false);
        assertThat(params.reasoning()).isPresent();
        assertThat(params.reasoning().get().effort()).isPresent();
        assertThat(params.user()).isPresent().hasValue("user-123");
    }

    @Test
    void convertsFunctionTools() {
        UnifiedChatRequest req = UnifiedChatRequest.builder()
            .model("gpt-4o")
            .messages(List.of(UnifiedMessage.builder()
                .role(UnifiedMessage.Role.USER)
                .content("What's the weather?")
                .build()))
            .tools(List.of(UnifiedTool.builder()
                .type("function")
                .function(UnifiedFunctionDefinition.builder()
                    .name("get_weather")
                    .description("Get weather")
                    .build())
                .build()))
            .build();

        ResponseCreateParams params = converter.convert(req);

        assertThat(params.tools()).isPresent();
        assertThat(params.tools().get()).hasSize(1);
        assertThat(params.tools().get().get(0).isFunction()).isTrue();
        assertThat(params.tools().get().get(0).asFunction().name()).isEqualTo("get_weather");
    }

    @Test
    void convertsToolChoiceRequired() {
        UnifiedChatRequest req = UnifiedChatRequest.builder()
            .model("gpt-4o")
            .messages(List.of(UnifiedMessage.builder()
                .role(UnifiedMessage.Role.USER)
                .content("Hi")
                .build()))
            .tools(List.of(UnifiedTool.builder()
                .type("function")
                .function(UnifiedFunctionDefinition.builder().name("f").build())
                .build()))
            .toolChoice(new UnifiedToolChoice.Required("f"))
            .build();

        ResponseCreateParams params = converter.convert(req);

        assertThat(params.toolChoice()).isPresent();
        assertThat(params.toolChoice().get().isFunction()).isTrue();
        assertThat(params.toolChoice().get().asFunction().name()).isEqualTo("f");
    }

    @Test
    void convertsAssistantToolCallsToFunctionCallItems() {
        UnifiedChatRequest req = UnifiedChatRequest.builder()
            .model("gpt-4o")
            .messages(List.of(
                UnifiedMessage.builder()
                    .role(UnifiedMessage.Role.USER)
                    .content("What's the weather?")
                    .build(),
                UnifiedMessage.builder()
                    .role(UnifiedMessage.Role.ASSISTANT)
                    .toolCalls(List.of(UnifiedToolCall.builder()
                        .id("call_1")
                        .type("function")
                        .function(UnifiedFunctionCall.builder()
                            .name("get_weather")
                            .arguments(new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode()
                                .put("city", "SF"))
                            .build())
                        .build()))
                    .build()))
            .build();

        ResponseCreateParams params = converter.convert(req);

        List<ResponseInputItem> items = params.input().get().asResponse();
        // user message + function_call item
        assertThat(items).hasSize(2);
        assertThat(items.get(1).isFunctionCall()).isTrue();
        assertThat(items.get(1).asFunctionCall().name()).isEqualTo("get_weather");
        assertThat(items.get(1).asFunctionCall().callId()).isEqualTo("call_1");
    }

    @Test
    void convertsOpenAiExtensionsMetadataAndStore() {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var meta = mapper.createObjectNode().put("session_id", "s1");
        UnifiedChatRequest req = UnifiedChatRequest.builder()
            .model("gpt-4o")
            .messages(List.of(UnifiedMessage.builder()
                .role(UnifiedMessage.Role.USER)
                .content("Hi")
                .build()))
            .stream(true)
            .openai(com.ai8493.llmproxy.model.extensions.OpenAiExtensions.builder()
                .metadata(meta)
                .store(false)
                .build())
            .build();

        ResponseCreateParams params = converter.convert(req);

        assertThat(params.store()).isPresent().hasValue(false);
        assertThat(params.metadata()).isPresent();
    }

    @Test
    void shouldMapPreviousResponseIdAndIncludeToParams() {
        UnifiedChatRequest req = UnifiedChatRequest.builder()
            .model("gpt-4o")
            .messages(List.of(UnifiedMessage.builder()
                .role(UnifiedMessage.Role.USER)
                .content("Hi")
                .build()))
            .openai(com.ai8493.llmproxy.model.extensions.OpenAiExtensions.builder()
                .previousResponseId("resp_abc123")
                .include(List.of("file_search_call.results"))
                .build())
            .build();

        ResponseCreateParams params = converter.convert(req);

        assertThat(params.previousResponseId()).isPresent().hasValue("resp_abc123");
        assertThat(params.include()).isPresent();
        assertThat(params.include().get()).hasSize(1);
        assertThat(params.include().get().get(0).asString()).isEqualTo("file_search_call.results");
    }
}
