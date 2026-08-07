package com.ai8493.llmproxy.adapter.openai;

import com.ai8493.llmproxy.model.*;
import com.openai.core.JsonValue;
import com.openai.models.responses.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;

class OpenAiResponsesResponseConverterTest {

    private final OpenAiResponsesResponseConverter converter = new OpenAiResponsesResponseConverter();

    // SDK Response.Builder 有大量必填字段,统一用此 helper 构造最小可用 Response
    private Response buildResponse(String id, List<ResponseOutputItem> output, ResponseUsage usage) {
        Response.Builder b = Response.builder()
            .id(id)
            .createdAt(1.0)
            .model("gpt-4o")
            .status(ResponseStatus.COMPLETED)
            .error(ResponseError.builder()
                .code(ResponseError.Code.SERVER_ERROR)
                .message("err")
                .build())
            .incompleteDetails(Optional.empty())
            .instructions(Optional.empty())
            .metadata(Optional.empty())
            .output(output)
            .parallelToolCalls(false)
            .temperature(1.0)
            .toolChoice(ToolChoiceOptions.AUTO)
            .tools(List.of())
            .topP(1.0);
        if (usage != null) {
            b.usage(usage);
        }
        return b.build();
    }

    @Test
    void convertsTextMessageOutput() {
        ResponseOutputMessage msg = ResponseOutputMessage.builder()
            .id("msg_1")
            .status(ResponseOutputMessage.Status.COMPLETED)
            .role(JsonValue.from("assistant"))
            .content(List.of(ResponseOutputMessage.Content.ofOutputText(
                ResponseOutputText.builder()
                    .text("Hello!")
                    .annotations(List.of())
                    .build())))
            .build();
        Response sdkResp = buildResponse("resp_1",
            List.of(ResponseOutputItem.ofMessage(msg)), null);

        UnifiedChatResponse uResp = converter.convert(sdkResp);

        assertThat(uResp.id()).isEqualTo("resp_1");
        assertThat(uResp.model()).isEqualTo("gpt-4o");
        assertThat(uResp.choices()).hasSize(1);
        assertThat(uResp.choices().get(0).message().content()).isEqualTo("Hello!");
        assertThat(uResp.choices().get(0).finishReason()).isEqualTo("completed");
    }

    @Test
    void convertsFunctionCallOutput() {
        ResponseFunctionToolCall fc = ResponseFunctionToolCall.builder()
            .id("fc_1")
            .callId("call_1")
            .name("get_weather")
            .arguments("{\"city\":\"SF\"}")
            .build();
        Response sdkResp = buildResponse("resp_2",
            List.of(ResponseOutputItem.ofFunctionCall(fc)), null);

        UnifiedChatResponse uResp = converter.convert(sdkResp);

        assertThat(uResp.choices().get(0).message().toolCalls()).hasSize(1);
        assertThat(uResp.choices().get(0).message().toolCalls().get(0).function().name())
            .isEqualTo("get_weather");
    }

    @Test
    void convertsUsageFields() {
        ResponseUsage usage = ResponseUsage.builder()
            .inputTokens(10)
            .outputTokens(5)
            .totalTokens(15)
            .inputTokensDetails(ResponseUsage.InputTokensDetails.builder()
                .cachedTokens(3).build())
            .outputTokensDetails(ResponseUsage.OutputTokensDetails.builder()
                .reasoningTokens(2).build())
            .build();
        Response sdkResp = buildResponse("resp_3", List.of(), usage);

        UnifiedChatResponse uResp = converter.convert(sdkResp);

        assertThat(uResp.usage()).isNotNull();
        assertThat(uResp.usage().promptTokens()).isEqualTo(10);
        assertThat(uResp.usage().completionTokens()).isEqualTo(5);
        assertThat(uResp.usage().totalTokens()).isEqualTo(15);
        assertThat(uResp.usage().cachedTokens()).isEqualTo(3);
        assertThat(uResp.usage().reasoningTokens()).isEqualTo(2);
    }
}
