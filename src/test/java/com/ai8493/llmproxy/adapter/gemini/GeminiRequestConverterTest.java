package com.ai8493.llmproxy.adapter.gemini;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.genai.types.*;
import com.ai8493.llmproxy.converter.ToolMapper;
import com.ai8493.llmproxy.model.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GeminiRequestConverterTest {

    private final ToolMapper toolMapper = new ToolMapper();
    private final GeminiRequestConverter converter = new GeminiRequestConverter(toolMapper);

    @Test
    void shouldMapUserMessageToUserRole() {
        var uReq = UnifiedChatRequest.builder()
            .model("gemini-pro")
            .messages(List.of(UnifiedMessage.builder()
                .role(UnifiedMessage.Role.USER)
                .content("你好")
                .build()))
            .stream(false)
            .build();

        var result = converter.toGeminiRequest(uReq);

        assertThat(result.contents()).isPresent();
        var contents = result.contents().get();
        assertThat(contents).hasSize(1);
        assertThat(contents.get(0).role()).hasValue("user");
        assertThat(contents.get(0).parts()).isPresent();
        assertThat(contents.get(0).parts().get())
            .hasSize(1)
            .first()
            .satisfies(p -> assertThat(p.text()).hasValue("你好"));
    }

    @Test
    void shouldMapAssistantMessageToModelRole() {
        var uReq = UnifiedChatRequest.builder()
            .model("gemini-pro")
            .messages(List.of(UnifiedMessage.builder()
                .role(UnifiedMessage.Role.ASSISTANT)
                .content("我是AI助手")
                .build()))
            .stream(false)
            .build();

        var result = converter.toGeminiRequest(uReq);

        assertThat(result.contents()).isPresent();
        var contents = result.contents().get();
        assertThat(contents).hasSize(1);
        assertThat(contents.get(0).role()).hasValue("model");
    }

    @Test
    void shouldMapSystemMessageAsSystemInstruction() {
        var uReq = UnifiedChatRequest.builder()
            .model("gemini-pro")
            .messages(List.of(
                UnifiedMessage.builder()
                    .role(UnifiedMessage.Role.SYSTEM)
                    .content("你是一个助手")
                    .build(),
                UnifiedMessage.builder()
                    .role(UnifiedMessage.Role.USER)
                    .content("hi")
                    .build()
            ))
            .stream(false)
            .build();

        var result = converter.toGeminiRequest(uReq);

        // Contents should exclude SYSTEM message
        assertThat(result.contents()).isPresent();
        assertThat(result.contents().get()).hasSize(1);

        // System message should be in systemInstruction
        assertThat(result.config()).isPresent();
        var config = result.config().get();
        assertThat(config.systemInstruction()).isPresent();
        var si = config.systemInstruction().get();
        assertThat(si.role()).hasValue("user");
        assertThat(si.parts()).isPresent();
        assertThat(si.parts().get())
            .hasSize(1)
            .first()
            .satisfies(p -> assertThat(p.text()).hasValue("你是一个助手"));
    }

    @Test
    void shouldRejectEmptyMessages() {
        var uReq = UnifiedChatRequest.builder()
            .model("gemini-pro")
            .messages(List.of())
            .stream(false)
            .build();

        assertThatThrownBy(() -> converter.toGeminiRequest(uReq))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("messages");
    }

    @Test
    void shouldMapGenerationConfig() {
        var config = UnifiedGenerationConfig.builder()
            .temperature(0.7)
            .topP(0.9)
            .maxOutputTokens(100)
            .build();
        var uReq = UnifiedChatRequest.builder()
            .model("gemini-pro")
            .messages(List.of(UnifiedMessage.builder()
                .role(UnifiedMessage.Role.USER)
                .content("hi")
                .build()))
            .config(config)
            .stream(false)
            .build();

        var result = converter.toGeminiRequest(uReq);

        assertThat(result.config()).isPresent();
        var gc = result.config().get();
        assertThat(gc.temperature()).hasValue(0.7f);
        assertThat(gc.topP()).hasValue(0.9f);
        assertThat(gc.maxOutputTokens()).hasValue(100);
    }

    @Test
    void shouldGenerateIdForFunctionResponseWithoutIdAndMatchFunctionCall() {
        var adapter = new GeminiProtocolAdapter();
        // functionCall 无 id，functionResponse 也无 id
        var fc = FunctionCall.builder()
            .name("get_weather")
            .args(Map.of("city", "NYC"))
            .build();
        var fr = FunctionResponse.builder()
            .name("get_weather")
            .response(Map.of("temp", 72))
            .build();
        var modelContent = Content.builder()
            .role("model")
            .parts(List.of(Part.builder().functionCall(fc).build()))
            .build();
        var toolContent = Content.builder()
            .role("user")
            .parts(List.of(Part.builder().functionResponse(fr).build()))
            .build();

        var result = adapter.toUnifiedRequest(
            GenerateContentParameters.builder()
                .model("deepseek-v4-flash")
                .contents(List.of(modelContent, toolContent))
                .build(),
            null, null, null, null);

        var messages = result.messages();
        assertThat(messages).hasSize(2);
        // 第一条：assistant 带 toolCalls
        var assistant = messages.get(0);
        assertThat(assistant.role()).isEqualTo(UnifiedMessage.Role.ASSISTANT);
        assertThat(assistant.toolCalls()).isNotNull().hasSize(1);
        String toolCallId = assistant.toolCalls().get(0).id();
        assertThat(toolCallId).isNotNull().isNotEmpty();
        // 第二条：tool 消息
        var tool = messages.get(1);
        assertThat(tool.role()).isEqualTo(UnifiedMessage.Role.TOOL);
        assertThat(tool.toolCallId()).isEqualTo(toolCallId);
    }

    @Test
    void shouldMapToolMessageToFunctionResponsePart() throws Exception {
        var mapper = new ObjectMapper();
        var uReq = UnifiedChatRequest.builder()
            .model("gemini-pro")
            .messages(List.of(
                UnifiedMessage.builder()
                    .role(UnifiedMessage.Role.USER)
                    .content("天气如何")
                    .build(),
                UnifiedMessage.builder()
                    .role(UnifiedMessage.Role.ASSISTANT)
                    .toolCalls(List.of(UnifiedToolCall.builder()
                        .id("call_123")
                        .type("function")
                        .function(UnifiedFunctionCall.builder()
                            .name("get_weather")
                            .arguments(mapper.readTree("{\"city\":\"NYC\"}"))
                            .build())
                        .build()))
                    .build(),
                UnifiedMessage.builder()
                    .role(UnifiedMessage.Role.TOOL)
                    .toolCallId("call_123")
                    .name("get_weather")
                    .content("{\"temp\":72}")
                    .build()
            ))
            .stream(false)
            .build();

        var result = converter.toGeminiRequest(uReq);
        var contents = result.contents().get();
        assertThat(contents).hasSize(3);
        // 第一条:user + text
        assertThat(contents.get(0).role()).hasValue("user");
        // 第二条:model + functionCall part(非 text part)
        assertThat(contents.get(1).role()).hasValue("model");
        assertThat(contents.get(1).parts().get()).hasSize(1);
        assertThat(contents.get(1).parts().get().get(0).functionCall()).isPresent();
        assertThat(contents.get(1).parts().get().get(0).functionCall().get().name()).hasValue("get_weather");
        // 第三条:user + functionResponse part(不是 function role + text part)
        assertThat(contents.get(2).role()).hasValue("user");
        assertThat(contents.get(2).parts().get()).hasSize(1);
        assertThat(contents.get(2).parts().get().get(0).functionResponse()).isPresent();
        assertThat(contents.get(2).parts().get().get(0).functionResponse().get().name()).hasValue("get_weather");
    }

    @Test
    void shouldMapAssistantReasoningContentToThoughtPart() {
        var uReq = UnifiedChatRequest.builder()
            .model("gemini-pro")
            .messages(List.of(
                UnifiedMessage.builder()
                    .role(UnifiedMessage.Role.USER)
                    .content("hi")
                    .build(),
                UnifiedMessage.builder()
                    .role(UnifiedMessage.Role.ASSISTANT)
                    .reasoningContent("我在思考")
                    .content("你好")
                    .build()
            ))
            .stream(false)
            .build();

        var result = converter.toGeminiRequest(uReq);
        var contents = result.contents().get();
        // assistant 消息应有 2 个 part:thought + text
        var modelContent = contents.get(1);
        assertThat(modelContent.role()).hasValue("model");
        assertThat(modelContent.parts().get()).hasSize(2);
        assertThat(modelContent.parts().get().get(0).thought()).hasValue(true);
        assertThat(modelContent.parts().get().get(0).text()).hasValue("我在思考");
        assertThat(modelContent.parts().get().get(1).text()).hasValue("你好");
    }
}
