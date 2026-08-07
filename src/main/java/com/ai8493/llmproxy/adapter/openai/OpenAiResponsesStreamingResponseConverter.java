package com.ai8493.llmproxy.adapter.openai;

import com.ai8493.llmproxy.model.*;
import com.openai.models.responses.*;
import java.util.List;

public class OpenAiResponsesStreamingResponseConverter {

    private final String model;

    public OpenAiResponsesStreamingResponseConverter(String model) {
        this.model = model;
    }

    public UnifiedChatResponse convert(ResponseStreamEvent evt) {
        if (evt.isOutputTextDelta()) {
            return textDelta(evt.asOutputTextDelta().delta());
        }
        if (evt.isReasoningSummaryTextDelta()) {
            return reasoningDelta(evt.asReasoningSummaryTextDelta().delta());
        }
        if (evt.isFunctionCallArgumentsDelta()) {
            var d = evt.asFunctionCallArgumentsDelta();
            return functionCallDelta(d.itemId(), d.delta());
        }
        if (evt.isCompleted()) {
            return completed(evt.asCompleted());
        }
        // 其他事件暂不产生 IR chunk,返回 null(上游过滤)
        return null;
    }

    private UnifiedChatResponse textDelta(String delta) {
        return UnifiedChatResponse.builder()
            .model(model)
            .choices(List.of(UnifiedChoice.builder()
                .index(0)
                .delta(UnifiedDelta.builder().content(delta).build())
                .build()))
            .build();
    }

    private UnifiedChatResponse reasoningDelta(String delta) {
        return UnifiedChatResponse.builder()
            .model(model)
            .choices(List.of(UnifiedChoice.builder()
                .index(0)
                .delta(UnifiedDelta.builder().reasoningContent(delta).build())
                .build()))
            .build();
    }

    private UnifiedChatResponse functionCallDelta(String itemId, String argsDelta) {
        // arguments 增量塞到 function.arguments(字符串增量,下游 ProtocolAdapter 累积)
        com.fasterxml.jackson.databind.JsonNode argsNode =
            com.fasterxml.jackson.databind.node.TextNode.valueOf(argsDelta);
        UnifiedToolCall tc = UnifiedToolCall.builder()
            .id(itemId)
            .type("function")
            .function(UnifiedFunctionCall.builder()
                .arguments(argsNode)
                .build())
            .build();
        return UnifiedChatResponse.builder()
            .model(model)
            .choices(List.of(UnifiedChoice.builder()
                .index(0)
                .delta(UnifiedDelta.builder()
                    .toolCalls(List.of(tc))
                    .build())
                .build()))
            .build();
    }

    private UnifiedChatResponse completed(ResponseCompletedEvent evt) {
        Response resp = evt.response();
        UnifiedUsage usage = null;
        if (resp.usage().isPresent()) {
            var u = resp.usage().get();
            int cached = (int) u.inputTokensDetails().cachedTokens();
            int reasoning = (int) u.outputTokensDetails().reasoningTokens();
            usage = UnifiedUsage.builder()
                .promptTokens((int) u.inputTokens())
                .completionTokens((int) u.outputTokens())
                .totalTokens((int) u.totalTokens())
                .cachedTokens(cached)
                .reasoningTokens(reasoning)
                .build();
        }
        // finishReason 存原值(spec 第 5 节)
        String finishReason = resp.status()
            .map(ResponseStatus::asString)
            .orElse("completed");
        return UnifiedChatResponse.builder()
            .id(resp.id())
            .model(modelToString(resp.model()))
            .choices(List.of(UnifiedChoice.builder()
                .index(0)
                .delta(UnifiedDelta.builder().build())
                .finishReason(finishReason)
                .build()))
            .usage(usage)
            .build();
    }

    // ResponsesModel 是联合类型(string/chat/only),JSON 反序列化时若模型名匹配
    // ChatModel 枚举(如 "gpt-4o"),SDK 优先选 chat 变体,asString() 会抛异常。
    // 按变体分别取值,保证所有模型名都能拿到字符串。
    private static String modelToString(com.openai.models.ResponsesModel model) {
        if (model.isString()) return model.asString();
        if (model.isChat()) return model.asChat().asString();
        if (model.isOnly()) return model.asOnly().asString();
        return "unknown";
    }
}
