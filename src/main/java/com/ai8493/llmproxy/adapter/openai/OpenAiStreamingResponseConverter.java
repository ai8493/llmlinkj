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
    // P3-15: tool_call 参数中连续空白达 500 字符视为异常,中止该 toolCall 参数转发
    private static final int WHITESPACE_LIMIT = 500;
    private final String model;
    private final Map<Integer, ToolCallAcc> toolCallAccs = new LinkedHashMap<>();
    // 跨 chunk 维护 <think>...</think> 拆分状态(覆盖 MiniMax 等把 reasoning 塞进 content 的上游)
    private final InlineThinkSplitter.State thinkState = new InlineThinkSplitter.State();
    // 流截断分类:跟踪是否已收到 finishReason + 是否有实质性输出
    private boolean hasFinishReason;
    private boolean hasSubstantiveOutput;

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
            // 计费恒等式: IR.promptTokens + IR.cachedTokens + IR.cacheCreationTokens == 原 promptTokens
            // OpenAI 后端不区分 cacheCreation,所以 cacheCreationTokens=0;promptTokens 扣减 cachedTokens
            int rawPromptTokens = u._promptTokens().asKnown().map(Number::intValue).orElse(0);
            usage = UnifiedUsage.builder()
                .promptTokens(Math.max(0, rawPromptTokens - cached))
                .completionTokens(u._completionTokens().asKnown().map(Number::intValue).orElse(0))
                .totalTokens(u._totalTokens().asKnown().map(Number::intValue).orElse(0))
                .cachedTokens(cached)
                .reasoningTokens(reasoning)
                .build();
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

            // 后端原生 reasoning_content 优先;否则用 thinkState 拆分 content 中的 <think> 块
            if (content != null && reasoningContent == null) {
                String[] split = thinkState.feed(content);
                if (split == null) {
                    content = null;
                } else {
                    reasoningContent = split[0].isEmpty() ? null : split[0];
                    content = split[1].isEmpty() ? null : split[1];
                }
            }

            List<UnifiedToolCall> deltaToolCalls = null;
            List<IndexedArgumentDelta> toolCallArgumentDeltas = new ArrayList<>();

            // 真流式:不累积 arguments,直接放到 delta.toolCallArgumentDeltas
            if (c.delta().toolCalls().isPresent()) {
                for (var tc : c.delta().toolCalls().get()) {
                    int tcIndex = (int) tc.index();
                    ToolCallAcc acc = toolCallAccs.computeIfAbsent(tcIndex, k -> new ToolCallAcc());
                    // 本 chunk 是否携带 id/name(用于判断是否输出 content_block_start 信号)
                    boolean hasIdThisChunk = tc.id().isPresent();
                    boolean hasNameThisChunk = tc.function().isPresent() && tc.function().get().name().isPresent();
                    // 累积 id/name(防御性,OpenAI 流式通常第一个 chunk 就有完整 id/name)
                    if (hasIdThisChunk) acc.id = tc.id().get();
                    if (hasNameThisChunk) acc.fnName = tc.function().get().name().get();
                    // 只有本 chunk 携带了 id 或 name,才输出 delta.toolCalls(content_block_start 信号,arguments 为 null)
                    if (hasIdThisChunk || hasNameThisChunk) {
                        deltaToolCalls = List.of(UnifiedToolCall.builder()
                            .index(tcIndex)  // 传 index 给下游,支持多 toolCall 按 index 区分
                            .id(acc.id)
                            .type("function")
                            .function(UnifiedFunctionCall.builder()
                                .name(acc.fnName)
                                .build())  // arguments 不设置,默认 null
                            .build());
                    }
                    // 如果有 arguments 增量,放到 delta.toolCallArgumentDeltas(真流式)
                    if (tc.function().isPresent() && tc.function().get().arguments().isPresent()) {
                        String argsDelta = tc.function().get().arguments().get();
                        // P3-15: 空白防护 - 检测连续空白,超额则中止该 toolCall 参数转发
                        if (shouldAbortForWhitespace(acc, argsDelta)) {
                            log.warn("流式 tool_call 因连续空白 >= {} 字符被中止: index={} name={}",
                                WHITESPACE_LIMIT, tcIndex, acc.fnName);
                        } else if (!acc.aborted) {
                            toolCallArgumentDeltas.add(new IndexedArgumentDelta(tcIndex, argsDelta));
                        }
                    }
                }
            }

            // finish_reason 时清空 toolCallAccs(不再组装完整 toolCalls)
            if (c.finishReason().isPresent()) {
                toolCallAccs.clear();
                hasFinishReason = true;
            }

            // 跟踪实质性输出(content / reasoningContent / toolCalls)
            if (content != null && !content.isEmpty()) hasSubstantiveOutput = true;
            if (reasoningContent != null && !reasoningContent.isEmpty()) hasSubstantiveOutput = true;
            if (deltaToolCalls != null && !deltaToolCalls.isEmpty()) hasSubstantiveOutput = true;
            if (!toolCallArgumentDeltas.isEmpty()) hasSubstantiveOutput = true;

            UnifiedDelta delta = UnifiedDelta.builder()
                .content(content)
                .toolCalls(deltaToolCalls)
                .reasoningContent(reasoningContent)
                .toolCallArgumentDeltas(toolCallArgumentDeltas.isEmpty() ? null : toolCallArgumentDeltas)
                .build();
            choices.add(UnifiedChoice.builder()
                .index((int) c.index())
                .delta(delta)
                .finishReason(c.finishReason().map(fr -> fr.asString()).orElse(null))
                .build());
        }

        return UnifiedChatResponse.builder()
            .id(chunkId)
            .model(model)
            .object("chat.completion.chunk")
            .created(created)
            .choices(choices)
            .usage(usage)
            .build();
    }

    // 流结束时 flush 残留的 <think> 状态 buffer。
    // 返回 null 表示无残留;非 null 时调用方应作为最后一个 chunk 发给客户端。
    public UnifiedChatResponse flush(String chunkId, long created) {
        String[] split = thinkState.flush();
        if (split == null) return null;
        String reasoning = split[0].isEmpty() ? null : split[0];
        String content = split[1].isEmpty() ? null : split[1];
        if (reasoning == null && content == null) return null;

        UnifiedDelta delta = UnifiedDelta.builder()
            .content(content)
            .reasoningContent(reasoning)
            .build();
        return UnifiedChatResponse.builder()
            .id(chunkId != null ? chunkId : "chatcmpl-flush-" + UUID.randomUUID().toString().substring(0, 8))
            .model(model)
            .object("chat.completion.chunk")
            .created(created)
            .choices(List.of(UnifiedChoice.builder()
                .index(0)
                .delta(delta)
                .build()))
            .build();
    }

    // 流截断分类:有 finishReason 视为正常完成,无需兜底
    public boolean isStreamCompleted() {
        return hasFinishReason;
    }

    // 流截断分类:是否有实质性输出(content/reasoning/toolCalls)
    public boolean hasSubstantiveOutput() {
        return hasSubstantiveOutput;
    }

    // 合成 incomplete 兜底 chunk:有输出但无 finishReason,标记 finish_reason=length
    public UnifiedChatResponse synthesizeIncompleteChunk(String chunkId, long created) {
        UnifiedDelta delta = UnifiedDelta.builder().build();
        UnifiedChoice choice = UnifiedChoice.builder()
            .index(0)
            .delta(delta)
            .finishReason("length")
            .build();
        return UnifiedChatResponse.builder()
            .id(chunkId != null ? chunkId : "chatcmpl-truncated-" + UUID.randomUUID().toString().substring(0, 8))
            .model(model)
            .object("chat.completion.chunk")
            .created(created)
            .choices(List.of(choice))
            .build();
    }

    private static class ToolCallAcc {
        String id, fnName;
        // 不再累积 argsBuilder(真流式,arguments 增量直接放到 delta.toolCallArgumentDeltas)
        // P3-15: 空白防护状态
        int consecutiveWhitespace;
        boolean aborted;
    }

    // P3-15: 检测 argsDelta 中的连续空白,达到阈值时标记 acc.aborted
    // 返回 true 表示本次 delta 触发了中止(调用方不应转发该 delta)
    private boolean shouldAbortForWhitespace(ToolCallAcc acc, String argsDelta) {
        if (acc.aborted) return true;  // 已中止,持续拦截
        for (int i = 0; i < argsDelta.length(); i++) {
            char c = argsDelta.charAt(i);
            if (Character.isWhitespace(c)) {
                acc.consecutiveWhitespace++;
                if (acc.consecutiveWhitespace >= WHITESPACE_LIMIT) {
                    acc.aborted = true;
                    return true;
                }
            } else {
                acc.consecutiveWhitespace = 0;
            }
        }
        return false;
    }
}
