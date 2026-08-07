package com.ai8493.llmproxy.adapter.openai;

import com.ai8493.llmproxy.model.*;
import com.openai.core.JsonValue;
import com.openai.models.responses.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;

class OpenAiResponsesStreamingResponseConverterTest {

    private final OpenAiResponsesStreamingResponseConverter converter =
        new OpenAiResponsesStreamingResponseConverter("gpt-4o");

    // SDK Response.Builder 有 13 个必填字段,统一用此 helper(同 Task 7)
    private Response buildResponse(String id, ResponseUsage usage) {
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
            .output(List.of())
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
    void convertsTextDeltaEvent() {
        ResponseTextDeltaEvent evt = ResponseTextDeltaEvent.builder()
            .itemId("msg_1")
            .outputIndex(0)
            .contentIndex(0)
            .delta("Hello")
            .logprobs(List.of())
            .sequenceNumber(1L)
            .type(JsonValue.from("response.output_text.delta"))
            .build();

        UnifiedChatResponse chunk = converter.convert(ResponseStreamEvent.ofOutputTextDelta(evt));

        assertThat(chunk.choices()).hasSize(1);
        assertThat(chunk.choices().get(0).delta().content()).isEqualTo("Hello");
    }

    @Test
    void convertsReasoningDeltaEvent() {
        ResponseReasoningSummaryTextDeltaEvent evt = ResponseReasoningSummaryTextDeltaEvent.builder()
            .itemId("rs_1")
            .outputIndex(0)
            .summaryIndex(0)
            .delta("thinking...")
            .sequenceNumber(1L)
            .type(JsonValue.from("response.reasoning_summary_text.delta"))
            .build();

        UnifiedChatResponse chunk = converter.convert(ResponseStreamEvent.ofReasoningSummaryTextDelta(evt));

        assertThat(chunk.choices().get(0).delta().reasoningContent()).isEqualTo("thinking...");
    }

    @Test
    void convertsCompletedEventWithUsageAndFinishReason() {
        ResponseUsage usage = ResponseUsage.builder()
            .inputTokens(10)
            .outputTokens(5)
            .totalTokens(15)
            .inputTokensDetails(ResponseUsage.InputTokensDetails.builder()
                .cachedTokens(0).build())
            .outputTokensDetails(ResponseUsage.OutputTokensDetails.builder()
                .reasoningTokens(0).build())
            .build();
        Response resp = buildResponse("resp_1", usage);
        ResponseCompletedEvent evt = ResponseCompletedEvent.builder()
            .response(resp)
            .sequenceNumber(10L)
            .type(JsonValue.from("response.completed"))
            .build();

        UnifiedChatResponse chunk = converter.convert(ResponseStreamEvent.ofCompleted(evt));

        assertThat(chunk.choices().get(0).finishReason()).isEqualTo("completed");
        assertThat(chunk.usage()).isNotNull();
        assertThat(chunk.usage().totalTokens()).isEqualTo(15);
    }
}
