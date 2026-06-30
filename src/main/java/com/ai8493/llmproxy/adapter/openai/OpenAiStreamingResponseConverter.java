package com.ai8493.llmproxy.adapter.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.models.chat.completions.*;
import com.ai8493.llmproxy.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

public class OpenAiStreamingResponseConverter {

    private static final Logger log = LoggerFactory.getLogger(OpenAiStreamingResponseConverter.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private final String model;
    private final Map<Integer, ToolCallAcc> toolCallAccs = new LinkedHashMap<>();

    public OpenAiStreamingResponseConverter(String model) {
        this.model = model;
    }

    public UnifiedChatResponse convertChunk(ChatCompletionChunk chunk) {
        String chunkId = chunk.id() != null ? chunk.id()
            : "chatcmpl-" + UUID.randomUUID().toString().substring(0, 8);
        long created = chunk.created();

        UnifiedUsage usage = null;
        if (chunk.usage().isPresent()) {
            var u = chunk.usage().get();
            int cached = 0;
            int reasoning = 0;
            if (u.promptTokensDetails().isPresent()) {
                cached = u.promptTokensDetails().get().cachedTokens().orElse(0L).intValue();
            }
            if (u.completionTokensDetails().isPresent()) {
                reasoning = u.completionTokensDetails().get().reasoningTokens().orElse(0L).intValue();
            }
            usage = new UnifiedUsage(
                u._promptTokens().asKnown().map(Number::intValue).orElse(0),
                u._completionTokens().asKnown().map(Number::intValue).orElse(0),
                u._totalTokens().asKnown().map(Number::intValue).orElse(0),
                cached, reasoning);
        }

        List<UnifiedChoice> choices = new ArrayList<>();
        for (var c : chunk.choices()) {
            String content = c.delta().content().orElse(null);
            // 从 delta._additionalProperties() 提取 reasoning_content
            String reasoningContent = null;
            if (c.delta()._additionalProperties() != null) {
                var rc = c.delta()._additionalProperties().get("reasoning_content");
                if (rc != null) {
                    reasoningContent = (String) rc.asString().orElse(null);
                }
            }
            List<UnifiedToolCall> deltaToolCalls = null;

            // 累积流式 tool_calls
            if (c.delta().toolCalls().isPresent()) {
                for (var tc : c.delta().toolCalls().get()) {
                    int tcIndex = (int) tc.index();
                    ToolCallAcc acc = toolCallAccs.computeIfAbsent(tcIndex, k -> new ToolCallAcc());
                    if (tc.id().isPresent()) acc.id = tc.id().get();
                    if (tc.function().isPresent()) {
                        var fn = tc.function().get();
                        if (fn.name().isPresent()) acc.fnName = fn.name().get();
                        if (fn.arguments().isPresent()) acc.argsBuilder.append(fn.arguments().get());
                    }
                }
            }

            // finish_reason 时组装累积的 tool_calls
            if (c.finishReason().isPresent() && !toolCallAccs.isEmpty()) {
                List<UnifiedToolCall> assembled = new ArrayList<>();
                for (ToolCallAcc acc : toolCallAccs.values()) {
                    if (acc.fnName != null) {
                        JsonNode argsNode = null;
                        String argsStr = acc.argsBuilder.toString();
                        if (!argsStr.isEmpty()) {
                            try { argsNode = mapper.readTree(argsStr); }
                            catch (Exception e) { log.warn("流式 tool_call arguments JSON 解析失败: name={}", acc.fnName, e); }
                        }
                        assembled.add(new UnifiedToolCall(acc.id, "function",
                            new UnifiedFunctionCall(acc.fnName, argsNode)));
                    }
                }
                deltaToolCalls = assembled.isEmpty() ? null : assembled;
                toolCallAccs.clear();
            }

            UnifiedDelta delta = new UnifiedDelta(null, content, deltaToolCalls, reasoningContent);
            choices.add(new UnifiedChoice((int) c.index(), null, delta,
                c.finishReason().map(fr -> fr.asString()).orElse(null), null));
        }

        return new UnifiedChatResponse(chunkId, model, "chat.completion.chunk",
            created, choices, usage, null);
    }

    private static class ToolCallAcc {
        String id, fnName;
        StringBuilder argsBuilder = new StringBuilder();
    }
}
