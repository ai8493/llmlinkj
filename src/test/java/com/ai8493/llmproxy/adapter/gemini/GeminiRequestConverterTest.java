package com.ai8493.llmproxy.adapter.gemini;

import com.google.genai.types.*;
import com.ai8493.llmproxy.converter.ToolMapper;
import com.ai8493.llmproxy.model.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
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
        var uReq = new UnifiedChatRequest(
            "gemini-pro",
            List.of(new UnifiedMessage(UnifiedMessage.Role.USER, "你好", null, null, null, null, null)),
            null, null, null, false);

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
        var uReq = new UnifiedChatRequest(
            "gemini-pro",
            List.of(new UnifiedMessage(UnifiedMessage.Role.ASSISTANT, "我是AI助手", null, null, null, null, null)),
            null, null, null, false);

        var result = converter.toGeminiRequest(uReq);

        assertThat(result.contents()).isPresent();
        var contents = result.contents().get();
        assertThat(contents).hasSize(1);
        assertThat(contents.get(0).role()).hasValue("model");
    }

    @Test
    void shouldMapSystemMessageAsSystemInstruction() {
        var uReq = new UnifiedChatRequest(
            "gemini-pro",
            List.of(
                new UnifiedMessage(UnifiedMessage.Role.SYSTEM, "你是一个助手", null, null, null, null, null),
                new UnifiedMessage(UnifiedMessage.Role.USER, "hi", null, null, null, null, null)
            ),
            null, null, null, false);

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
        var uReq = new UnifiedChatRequest(
            "gemini-pro",
            List.of(),
            null, null, null, false);

        assertThatThrownBy(() -> converter.toGeminiRequest(uReq))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("messages");
    }

    @Test
    void shouldMapGenerationConfig() {
        var config = new UnifiedGenerationConfig(0.7, 0.9, 100, null, null, null, null, null);
        var uReq = new UnifiedChatRequest(
            "gemini-pro",
            List.of(new UnifiedMessage(UnifiedMessage.Role.USER, "hi", null, null, null, null, null)),
            config, null, null, false);

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
            null, null, null);

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
}
