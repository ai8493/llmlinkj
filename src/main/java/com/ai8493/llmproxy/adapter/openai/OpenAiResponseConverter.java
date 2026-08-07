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

        return UnifiedChatResponse.builder()
            .id(sdkResp.id())
            .model(sdkResp.model())
            .object("chat.completion")
            .created(sdkResp.created())
            .choices(choices)
            .usage(usage)
            .systemFingerprint(sdkResp.systemFingerprint().orElse(null))
            .build();
    }

    private UnifiedChoice toUnifiedChoice(ChatCompletion.Choice c) {
        JsonNode logprobs = null;
        if (c.logprobs().isPresent()) {
            try { logprobs = MAPPER.valueToTree(c.logprobs().get()); }
            catch (Exception e) { /* 序列化失败则丢弃 */ }
        }
        return UnifiedChoice.builder()
            .index((int) c.index())
            .message(toUnifiedMessage(c.message()))
            .finishReason(c.finishReason().asString())
            .logprobs(logprobs)
            .build();
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

        String content = msg.content().orElse(null);

        // 后端原生 reasoning_content 优先;否则尝试从 content 中拆分 <think>...</think> 块
        // 覆盖 MiniMax 等把 reasoning 塞进 content 标签的上游
        if (reasoningContent == null && content != null) {
            String[] split = InlineThinkSplitter.splitLeadingThinkBlock(content);
            if (split != null) {
                reasoningContent = split[0].isEmpty() ? null : split[0];
                content = split[1].isEmpty() ? null : split[1];
            }
        }

        List<UnifiedToolCall> toolCalls = null;
        if (msg.toolCalls().isPresent()) {
            toolCalls = msg.toolCalls().get().stream()
                .map(this::toUnifiedToolCall)
                .toList();
        }

        // refusal(SDK 原生字段)
        String refusal = msg.refusal().orElse(null);

        // audio/annotations(从 _additionalProperties 提取)
        JsonNode audio = null;
        JsonNode annotations = null;
        if (msg._additionalProperties() != null) {
            var audioVal = msg._additionalProperties().get("audio");
            if (audioVal != null) {
                try { audio = audioVal.convert(JsonNode.class); } catch (Exception __) {}
            }
            var annVal = msg._additionalProperties().get("annotations");
            if (annVal != null) {
                try { annotations = annVal.convert(JsonNode.class); } catch (Exception __) {}
            }
        }

        return UnifiedMessage.builder()
            .role(UnifiedMessage.Role.ASSISTANT)
            .content(content)
            .toolCalls(toolCalls)
            .reasoningContent(reasoningContent)
            .refusal(refusal)
            .audio(audio)
            .annotations(annotations)
            .build();
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
            return UnifiedToolCall.builder()
                .id(fnTc.id())
                .type("function")
                .function(UnifiedFunctionCall.builder()
                    .name(fnTc.function().name())
                    .arguments(args)
                    .build())
                .build();
        }
        // 兜底：未知类型的 tool call
        return UnifiedToolCall.builder()
            .id("unknown")
            .type("unknown")
            .function(UnifiedFunctionCall.builder()
                .name("unknown")
                .build())
            .build();
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
        // 计费恒等式: IR.promptTokens + IR.cachedTokens + IR.cacheCreationTokens == 原 promptTokens
        // OpenAI 后端不区分 cacheCreation,所以 cacheCreationTokens=0;promptTokens 扣减 cachedTokens
        int rawPromptTokens = u._promptTokens().asKnown().map(Number::intValue).orElse(0);
        return UnifiedUsage.builder()
            .promptTokens(Math.max(0, rawPromptTokens - cached))
            .completionTokens(u._completionTokens().asKnown().map(Number::intValue).orElse(0))
            .totalTokens(u._totalTokens().asKnown().map(Number::intValue).orElse(0))
            .cachedTokens(cached)
            .reasoningTokens(reasoning)
            .build();
    }
}
