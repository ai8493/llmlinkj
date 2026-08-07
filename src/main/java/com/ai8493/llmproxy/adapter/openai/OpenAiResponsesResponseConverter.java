package com.ai8493.llmproxy.adapter.openai;

import com.ai8493.llmproxy.model.*;
import com.openai.models.responses.*;
import java.util.ArrayList;
import java.util.List;

public class OpenAiResponsesResponseConverter {

    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
        new com.fasterxml.jackson.databind.ObjectMapper();

    public UnifiedChatResponse convert(Response sdkResp) {
        List<UnifiedChoice> choices = new ArrayList<>();

        String content = null;
        String refusal = null;
        List<UnifiedToolCall> toolCalls = new ArrayList<>();
        String reasoningContent = null;

        if (sdkResp.output() != null) {
            for (ResponseOutputItem item : sdkResp.output()) {
                if (item.isMessage()) {
                    var msg = item.asMessage();
                    for (var c : msg.content()) {
                        if (c.isOutputText()) {
                            content = c.asOutputText().text();
                        } else if (c.isRefusal()) {
                            refusal = c.asRefusal().refusal();
                        }
                    }
                } else if (item.isFunctionCall()) {
                    var fc = item.asFunctionCall();
                    com.fasterxml.jackson.databind.JsonNode args = null;
                    try {
                        if (fc.arguments() != null && !fc.arguments().isEmpty()) {
                            args = MAPPER.readTree(fc.arguments());
                        }
                    } catch (Exception e) { /* 保留 null */ }
                    String tcId = fc.callId() != null ? fc.callId() : fc.id().orElse("");
                    toolCalls.add(UnifiedToolCall.builder()
                        .id(tcId)
                        .type("function")
                        .function(UnifiedFunctionCall.builder()
                            .name(fc.name())
                            .arguments(args)
                            .build())
                        .build());
                } else if (item.isReasoning()) {
                    var r = item.asReasoning();
                    if (r.summary() != null && !r.summary().isEmpty()) {
                        reasoningContent = r.summary().get(0).text();
                    }
                }
            }

            UnifiedMessage msg = UnifiedMessage.builder()
                .role(UnifiedMessage.Role.ASSISTANT)
                .content(content)
                .refusal(refusal)
                .toolCalls(toolCalls.isEmpty() ? null : toolCalls)
                .reasoningContent(reasoningContent)
                .build();

            // finishReason 存原值(spec 第 5 节),同协议直接复制
            String finishReason = sdkResp.status()
                .map(ResponseStatus::asString)
                .orElse("completed");
            choices.add(UnifiedChoice.builder()
                .index(0)
                .message(msg)
                .finishReason(finishReason)
                .build());
        }

        UnifiedUsage usage = sdkResp.usage().isPresent()
            ? toUnifiedUsage(sdkResp.usage().get()) : null;

        return UnifiedChatResponse.builder()
            .id(sdkResp.id())
            .model(modelToString(sdkResp.model()))
            .object("response")
            .created((long) sdkResp.createdAt())
            .choices(choices)
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

    private UnifiedUsage toUnifiedUsage(ResponseUsage u) {
        int cached = (int) u.inputTokensDetails().cachedTokens();
        int reasoning = (int) u.outputTokensDetails().reasoningTokens();
        return UnifiedUsage.builder()
            .promptTokens((int) u.inputTokens())
            .completionTokens((int) u.outputTokens())
            .totalTokens((int) u.totalTokens())
            .cachedTokens(cached)
            .reasoningTokens(reasoning)
            .build();
    }
}
