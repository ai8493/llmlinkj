package com.ai8493.llmproxy.adapter.openai;

import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.ChatCompletionMessageParam;
import com.ai8493.llmproxy.model.*;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OpenAiRequestConverterTest {

    private final OpenAiRequestConverter converter = new OpenAiRequestConverter();

    @Test
    void shouldConvertUserMessage() {
        var req = new UnifiedChatRequest(
            "gpt-4o",
            List.of(new UnifiedMessage(UnifiedMessage.Role.USER, "你好", null, null, null, null, null)),
            null, null, null, false
        );

        ChatCompletionCreateParams params = converter.convert(req);

        assertThat(params.model().asString()).isEqualTo("gpt-4o");
        assertThat(params.messages()).hasSize(1);
        ChatCompletionMessageParam msg = params.messages().get(0);
        assertThat(msg.isUser()).isTrue();
        assertThat(msg.asUser().content().asText()).isEqualTo("你好");
    }

    @Test
    void shouldConvertAllRoleTypes() {
        var req = new UnifiedChatRequest(
            "gpt-4o",
            List.of(
                new UnifiedMessage(UnifiedMessage.Role.SYSTEM, "系统提示", null, null, null, null, null),
                new UnifiedMessage(UnifiedMessage.Role.USER, "用户问题", null, null, null, null, null),
                new UnifiedMessage(UnifiedMessage.Role.ASSISTANT, "助手回答", null, null, null, null, null),
                new UnifiedMessage(UnifiedMessage.Role.TOOL, "工具结果", null, null, "call_123", null, null)
            ),
            null, null, null, false
        );

        ChatCompletionCreateParams params = converter.convert(req);

        assertThat(params.messages()).hasSize(4);
        assertThat(params.messages().get(0).isSystem()).isTrue();
        assertThat(params.messages().get(1).isUser()).isTrue();
        assertThat(params.messages().get(2).isAssistant()).isTrue();
        assertThat(params.messages().get(3).isTool()).isTrue();
        assertThat(params.messages().get(3).asTool().toolCallId()).isEqualTo("call_123");
    }

    @Test
    void shouldConvertTools() {
        var req = new UnifiedChatRequest(
            "gpt-4o",
            List.of(new UnifiedMessage(UnifiedMessage.Role.USER, "查询天气", null, null, null, null, null)),
            null,
            List.of(new UnifiedTool("function",
                new UnifiedFunctionDefinition("get_weather", "获取天气", null))),
            null, false
        );

        ChatCompletionCreateParams params = converter.convert(req);

        assertThat(params.tools()).isPresent();
        assertThat(params.tools().get()).hasSize(1);
        var tool = params.tools().get().get(0);
        assertThat(tool.isFunction()).isTrue();
        assertThat(tool.asFunction().function().name()).isEqualTo("get_weather");
        assertThat(tool.asFunction().function().description()).hasValue("获取天气");
    }

    @Test
    void shouldConvertToolChoice() {
        var autoReq = new UnifiedChatRequest(
            "gpt-4o",
            List.of(new UnifiedMessage(UnifiedMessage.Role.USER, "hi", null, null, null, null, null)),
            null, null, new UnifiedToolChoice.Auto(), false
        );

        var params = converter.convert(autoReq);
        assertThat(params.toolChoice()).isPresent();
        assertThat(params.toolChoice().get().isAuto()).isTrue();
    }

    @Test
    void shouldConvertGenerationConfig() {
        var req = new UnifiedChatRequest(
            "gpt-4o",
            List.of(new UnifiedMessage(UnifiedMessage.Role.USER, "hi", null, null, null, null, null)),
            new UnifiedGenerationConfig(0.7, 0.9, 1024, List.of("END"), null, null, null, null),
            null, null, false
        );

        ChatCompletionCreateParams params = converter.convert(req);

        assertThat(params.temperature()).hasValue(0.7);
        assertThat(params.topP()).hasValue(0.9);
        assertThat(params.maxTokens()).hasValue(1024L);
        assertThat(params.stop()).isPresent();
    }

    @Test
    void shouldConvertAssistantMessageWithToolCalls() {
        var req = new UnifiedChatRequest(
            "gpt-4o",
            List.of(new UnifiedMessage(
                UnifiedMessage.Role.ASSISTANT, null, null,
                List.of(new UnifiedToolCall("call_abc", "function",
                    new UnifiedFunctionCall("get_weather", null))),
                null, null, null
            )),
            null, null, null, false
        );

        ChatCompletionCreateParams params = converter.convert(req);

        assertThat(params.messages()).hasSize(1);
        var msg = params.messages().get(0);
        assertThat(msg.isAssistant()).isTrue();
        assertThat(msg.asAssistant().toolCalls()).isPresent();
        assertThat(msg.asAssistant().toolCalls().get()).hasSize(1);
        assertThat(msg.asAssistant().toolCalls().get().get(0).asFunction().id()).isEqualTo("call_abc");
    }
}
