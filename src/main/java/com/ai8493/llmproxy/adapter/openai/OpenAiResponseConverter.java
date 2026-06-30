package com.ai8493.llmproxy.adapter.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.models.chat.completions.*;
import com.openai.models.completions.CompletionUsage;
import com.ai8493.llmproxy.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public class OpenAiResponseConverter {

    private static final Logger log = LoggerFactory.getLogger(OpenAiResponseConverter.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public UnifiedChatResponse convert(ChatCompletion sdkResp) {
        UnifiedUsage usage = null;
        if (sdkResp.usage().isPresent()) {
            usage = toUnifiedUsage(sdkResp.usage().get());
        }

        List<UnifiedChoice> choices = sdkResp.choices().stream()
            .map(this::toUnifiedChoice)
            .toList();

        return new UnifiedChatResponse(
            sdkResp.id(),
            sdkResp.model(),
            "chat.completion",
            sdkResp.created(),
            choices,
            usage,
            sdkResp.systemFingerprint().orElse(null)
        );
    }

    private UnifiedChoice toUnifiedChoice(ChatCompletion.Choice c) {
        UnifiedDelta delta = null; // 非流式响应无 delta
        JsonNode logprobs = null;
        if (c.logprobs().isPresent()) {
            try { logprobs = MAPPER.valueToTree(c.logprobs().get()); }
            catch (Exception e) { /* 序列化失败则丢弃 */ }
        }
        return new UnifiedChoice(
            (int) c.index(),
            toUnifiedMessage(c.message()),
            delta,
            c.finishReason().asString(),
            logprobs
        );
    }

    private UnifiedMessage toUnifiedMessage(ChatCompletionMessage msg) {
        // 从 _additionalProperties 提取 reasoning_content
        String reasoningContent = null;
        if (msg._additionalProperties() != null) {
            var rc = msg._additionalProperties().get("reasoning_content");
            if (rc != null) {
                reasoningContent = (String) rc.asString().orElse(null);
            }
        }

        List<UnifiedToolCall> toolCalls = null;
        if (msg.toolCalls().isPresent()) {
            toolCalls = msg.toolCalls().get().stream()
                .map(this::toUnifiedToolCall)
                .toList();
        }

        return new UnifiedMessage(
            UnifiedMessage.Role.ASSISTANT,
            msg.content().orElse(null),
            null,  // parts
            toolCalls,
            null,  // toolCallId
            null,  // name
            reasoningContent  // reasoningContent（从 _additionalProperties 提取）
        );
    }

    private UnifiedToolCall toUnifiedToolCall(ChatCompletionMessageToolCall tc) {
        if (tc.isFunction()) {
            var fnTc = tc.asFunction();
            JsonNode args = null;
            try {
                String argsStr = fnTc.function().arguments();
                if (argsStr != null && !argsStr.isEmpty()) {
                    args = MAPPER.readTree(argsStr);
                }
            } catch (Exception e) {
                log.warn("tool_call arguments JSON 解析失败: id={}", fnTc.id(), e);
            }
            return new UnifiedToolCall(
                fnTc.id(),
                "function",
                new UnifiedFunctionCall(fnTc.function().name(), args)
            );
        }
        // 兜底：未知类型的 tool call
        return new UnifiedToolCall(
            "unknown",
            "unknown",
            new UnifiedFunctionCall("unknown", null)
        );
    }

    private UnifiedUsage toUnifiedUsage(CompletionUsage u) {
        int cached = 0;
        int reasoning = 0;
        if (u.promptTokensDetails().isPresent()) {
            cached = u.promptTokensDetails().get().cachedTokens().orElse(0L).intValue();
        }
        if (u.completionTokensDetails().isPresent()) {
            reasoning = u.completionTokensDetails().get().reasoningTokens().orElse(0L).intValue();
        }
        return new UnifiedUsage(
            u._promptTokens().asKnown().map(Number::intValue).orElse(0),
            u._completionTokens().asKnown().map(Number::intValue).orElse(0),
            u._totalTokens().asKnown().map(Number::intValue).orElse(0),
            cached,
            reasoning
        );
    }
}
