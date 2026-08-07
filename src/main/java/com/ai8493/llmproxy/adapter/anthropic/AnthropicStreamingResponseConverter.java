package com.ai8493.llmproxy.adapter.anthropic;

import com.anthropic.models.messages.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ai8493.llmproxy.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.*;

/**
 * 将 Anthropic 流式事件（RawMessageStreamEvent）转换为 IR UnifiedChatResponse。
 * 状态机：累积 content block 信息，在 messageStop 时组装最终块。
 */
public class AnthropicStreamingResponseConverter {

    private static final Logger log = LoggerFactory.getLogger(AnthropicStreamingResponseConverter.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // Block 追踪
    private final Map<Integer, StringBuilder> blockTextBufs = new HashMap<>();
    private final Map<Integer, String> blockTypes = new HashMap<>();
    private final Map<Integer, String> toolUseIds = new HashMap<>();
    private final Map<Integer, String> toolUseNames = new HashMap<>();
    private final Map<Integer, StringBuilder> toolUseArgsBufs = new HashMap<>();
    private final Map<Integer, com.anthropic.core.JsonValue> toolUseInitialInputs = new HashMap<>();

    // 消息元信息
    private String msgId;
    private String model;
    private long createdAt;

    // Usage + stopReason（从 messageDelta 记录）
    private int inputTokens;
    private int outputTokens;
    private int cachedTokens;
    private int cacheCreationTokens;
    private String stopReason;

    // 流截断分类:跟踪是否已收到 stopReason + 是否有实质性输出
    private boolean hasSubstantiveOutput;

    private final String chunkId;

    public AnthropicStreamingResponseConverter() {
        this.chunkId = "chatcmpl-" + UUID.randomUUID().toString().substring(0, 8);
        this.createdAt = Instant.now().getEpochSecond();
    }

    /**
     * 转换单个流式事件为 IR UnifiedChatResponse。
     * 部分事件（如 inputJson delta）不产出数据 chunk，返回空 choices。
     */
    public UnifiedChatResponse convertEvent(RawMessageStreamEvent event) {
        if (event.isMessageStart()) {
            return handleMessageStart(event.asMessageStart());
        } else if (event.isContentBlockStart()) {
            return handleContentBlockStart(event.asContentBlockStart());
        } else if (event.isContentBlockDelta()) {
            return handleContentBlockDelta(event.asContentBlockDelta());
        } else if (event.isContentBlockStop()) {
            return handleContentBlockStop();
        } else if (event.isMessageDelta()) {
            return handleMessageDelta(event.asMessageDelta());
        } else if (event.isMessageStop()) {
            return handleMessageStop();
        }
        return emptyChunk();
    }

    // ====== 事件处理 ======

    private UnifiedChatResponse handleMessageStart(RawMessageStartEvent start) {
        Message msg = start.message();
        this.msgId = msg.id();
        this.model = msg.model().asString();
        return emptyChunk();
    }

    private UnifiedChatResponse handleContentBlockStart(RawContentBlockStartEvent blockStart) {
        int index = (int) blockStart.index();
        var contentBlock = blockStart.contentBlock();

        if (contentBlock.isText()) {
            blockTypes.put(index, "text");
            blockTextBufs.put(index, new StringBuilder());
            return emptyChunk();
        }
        if (contentBlock.thinking().isPresent()) {
            blockTypes.put(index, "thinking");
            blockTextBufs.put(index, new StringBuilder());
            log.debug("流式 thinking block_start: index={} thinking={}", index,
                contentBlock.thinking().get().thinking());
            return emptyChunk();
        }
        if (contentBlock.redactedThinking().isPresent()) {
            blockTypes.put(index, "redacted_thinking");
            var rt = contentBlock.redactedThinking().get();
            String initial = rt.data();
            blockTextBufs.put(index, new StringBuilder(initial));
            log.debug("流式 redacted_thinking block_start: index={} thinking={}", index, initial);
            if (!initial.isEmpty()) {
                hasSubstantiveOutput = true;
                return buildChunk(UnifiedDelta.builder().reasoningContent(initial).build(), null);
            }
            return emptyChunk();
        }
        if (contentBlock.isToolUse()) {
            blockTypes.put(index, "tool_use");
            ToolUseBlock tu = contentBlock.asToolUse();
            toolUseIds.put(index, tu.id());
            toolUseNames.put(index, tu.name());
            toolUseArgsBufs.put(index, new StringBuilder());
            toolUseInitialInputs.put(index, tu._input());
            hasSubstantiveOutput = true;
            var tc = UnifiedToolCall.builder()
                    .index(index)  // 传 index 给下游,支持多 toolCall 按 index 区分
                    .id(tu.id())
                    .type("function")
                    .function(UnifiedFunctionCall.builder()
                            .name(tu.name())
                            .build())
                    .build();
            return buildChunk(UnifiedDelta.builder().toolCalls(List.of(tc)).build(), null);
        }
        log.debug("流式 block_start 未处理类型: index={} type={}", index, contentBlock);
        return emptyChunk();
    }

    private UnifiedChatResponse handleContentBlockDelta(RawContentBlockDeltaEvent deltaEvent) {
        int index = (int) deltaEvent.index();
        var delta = deltaEvent.delta();

        if (delta.isText()) {
            String text = delta.asText().text();
            appendBlockText(index, text);
            hasSubstantiveOutput = true;
            return buildChunk(UnifiedDelta.builder().content(text).build(), null);
        } else if (delta.isThinking()) {
            String thinking = delta.asThinking().thinking();
            appendBlockText(index, thinking);
            hasSubstantiveOutput = true;
            return buildChunk(UnifiedDelta.builder().reasoningContent(thinking).build(), null);
        } else if (delta.isInputJson()) {
            // 仅累积到 buffer，在 messageStop 时一次性发送完整 tool_calls
            String partialJson = delta.asInputJson().partialJson();
            StringBuilder buf = toolUseArgsBufs.get(index);
            if (buf != null) {
                buf.append(partialJson);
                log.debug("流式 inputJson 累积: index={} name={} chunk={} total={}",
                    index, toolUseNames.get(index), partialJson, buf.toString());
            } else {
                log.warn("流式 inputJson 无对应 buffer: index={} json={}", index, partialJson);
            }
            return emptyChunk();
        } else if (delta.isSignature()) {
            String sig = delta.asSignature().signature();
            appendBlockText(index, sig);
            // signature 透传到 UnifiedDelta.thinkingSignature(支持多轮续接)
            hasSubstantiveOutput = true;
            return buildChunk(UnifiedDelta.builder().thinkingSignature(sig).build(), null);
        }
        // 其他 delta 类型（citations, signature 等），IR 暂无对应字段
        String deltaVariant = delta.isCitations() ? "citations"
            : delta.isSignature() ? "signature"
            : delta.isThinking() ? "thinking"
            : delta.isInputJson() ? "inputJson"
            : delta.isText() ? "text"
            : "unknown";
        log.debug("流式 delta 未处理类型: index={} variant={}", index, deltaVariant);
        return emptyChunk();
    }

    private UnifiedChatResponse handleContentBlockStop() {
        // contentBlockStop 不发射数据，等待 messageStop 统一组装 tool_use
        return emptyChunk();
    }

    private UnifiedChatResponse handleMessageDelta(RawMessageDeltaEvent md) {
        MessageDeltaUsage usage = md.usage();
        if (usage.inputTokens().isPresent()) {
            this.inputTokens = usage.inputTokens().get().intValue();
        }
        this.outputTokens = (int) usage.outputTokens();
        // cache_read 和 cache_creation 并存(非互斥,与响应侧 Task 6 一致)
        this.cachedTokens = usage.cacheReadInputTokens().map(Long::intValue).orElse(this.cachedTokens);
        this.cacheCreationTokens = usage.cacheCreationInputTokens().map(Long::intValue).orElse(this.cacheCreationTokens);
        // 记录真实 stopReason(Anthropic 原值,spec 第 5 节),避免 messageStop 硬编码
        md.delta().stopReason().ifPresent(r -> this.stopReason = r.asString());
        return emptyChunk();
    }

    private UnifiedChatResponse handleMessageStop() {
        // 仅组装 tool_calls（文本/推理已在增量 deltas 中流式输出，不重复发送）
        List<UnifiedToolCall> toolCalls = null;

        for (var entry : blockTypes.entrySet()) {
            int idx = entry.getKey();
            if (!"tool_use".equals(entry.getValue())) continue;

            String tuId = toolUseIds.get(idx);
            String tuName = toolUseNames.get(idx);
            if (tuId == null || tuName == null) continue;
            if (toolCalls == null) {
                toolCalls = new ArrayList<>();
            }
            StringBuilder argsBuf = toolUseArgsBufs.get(idx);
            JsonNode args = null;
            if (argsBuf != null && !argsBuf.isEmpty()) {
                try {
                    args = MAPPER.readTree(argsBuf.toString());
                    log.debug("流式 tool_use 组装完成: name={} id={} args={}", tuName, tuId, args);
                } catch (Exception e) {
                    log.warn("流式 tool_use arguments JSON 解析失败: name={} raw={}", tuName, argsBuf.toString(), e);
                    // 流式 inputJson 片段可能重叠，尝试从末尾截取合法 JSON 前缀
                    String raw = argsBuf.toString();
                    for (int end = raw.length(); end > 1; end--) {
                        try {
                            args = MAPPER.readTree(raw.substring(0, end));
                            log.warn("流式 tool_use 截取有效 JSON 前缀成功: name={} 原长={} 截断位={}", tuName, raw.length(), end);
                            break;
                        } catch (Exception ignored) {}
                    }
                }
            } else {
                log.debug("流式 tool_use 无参数(无 inputJson delta): name={} id={}", tuName, tuId);
                // 回退：部分后端模型（如 MiniMax）在 content_block_start 中就带完整参数
                com.anthropic.core.JsonValue initialInput = toolUseInitialInputs.get(idx);
                if (initialInput != null && !initialInput.isMissing() && !initialInput.isNull()) {
                    try {
                        args = MAPPER.convertValue(initialInput, JsonNode.class);
                        log.debug("流式 tool_use 使用初始 input 回退: name={} id={} args={}",
                            tuName, tuId, args);
                    } catch (Exception e) {
                        log.warn("流式 tool_use 初始 input JSON 解析失败: name={} raw={}",
                            tuName, initialInput, e);
                    }
                }
                if (args == null) {
                    args = MAPPER.createObjectNode(); // 兜底：确保下游不跳过
                    log.debug("流式 tool_use 使用空参数兜底: name={} id={}", tuName, tuId);
                }
            }
            toolCalls.add(UnifiedToolCall.builder()
                    .index(idx)  // 传 index 给下游,支持多 toolCall 按 index 区分
                    .id(tuId)
                    .type("function")
                    .function(UnifiedFunctionCall.builder()
                            .name(tuName)
                            .arguments(args)
                            .build())
                    .build());
        }

        // content/reasoningContent 置 null：它们已在 handleContentBlockDelta 中逐段流式输出
        UnifiedDelta finalDelta = UnifiedDelta.builder()
                .toolCalls((toolCalls != null && !toolCalls.isEmpty()) ? toolCalls : null)
                .build();

        int totalTokens = inputTokens + outputTokens;
        UnifiedUsage usage = UnifiedUsage.builder()
                .promptTokens(inputTokens)
                .completionTokens(outputTokens)
                .totalTokens(totalTokens)
                .cachedTokens(cachedTokens)
                .cacheCreationTokens(cacheCreationTokens)
                .build();

        String id = msgId != null ? msgId : chunkId;
        String m = model != null ? model : "unknown";
        String finishReason = this.stopReason != null ? this.stopReason : "stop";
        return UnifiedChatResponse.builder()
                .id(id)
                .model(m)
                .object("chat.completion.chunk")
                .created(createdAt)
                .choices(List.of(UnifiedChoice.builder()
                        .index(0)
                        .delta(finalDelta)
                        .finishReason(finishReason)
                        .build()))
                .usage(usage)
                .build();
    }

    // ====== 辅助方法 ======

    private void appendBlockText(int index, String text) {
        StringBuilder buf = blockTextBufs.get(index);
        if (buf != null) {
            buf.append(text);
        } else {
            buf = new StringBuilder(text);
            blockTextBufs.put(index, buf);
        }
    }

    private UnifiedChatResponse buildChunk(UnifiedDelta delta, String finishReason) {
        String id = msgId != null ? msgId : chunkId;
        String m = model != null ? model : "unknown";
        return UnifiedChatResponse.builder()
                .id(id)
                .model(m)
                .object("chat.completion.chunk")
                .created(createdAt)
                .choices(List.of(UnifiedChoice.builder()
                        .index(0)
                        .delta(delta)
                        .finishReason(finishReason)
                        .build()))
                .build();
    }

    private UnifiedChatResponse emptyChunk() {
        return UnifiedChatResponse.builder()
                .id(msgId != null ? msgId : chunkId)
                .model(model != null ? model : "unknown")
                .object("chat.completion.chunk")
                .created(createdAt)
                .choices(List.of())
                .build();
    }

    // 流截断分类:是否已收到 stopReason(从 messageDelta)
    public boolean isStreamCompleted() {
        return stopReason != null;
    }

    // 流截断分类:是否有实质性输出(content/thinking/toolCalls)
    public boolean hasSubstantiveOutput() {
        return hasSubstantiveOutput;
    }

    // 合成 incomplete 兜底 chunk:有输出但无 stopReason,标记 finish_reason=length
    public UnifiedChatResponse synthesizeIncompleteChunk() {
        UnifiedDelta delta = UnifiedDelta.builder().build();
        UnifiedChoice choice = UnifiedChoice.builder()
            .index(0)
            .delta(delta)
            .finishReason("length")
            .build();
        return UnifiedChatResponse.builder()
            .id(msgId != null ? msgId : chunkId)
            .model(model != null ? model : "unknown")
            .object("chat.completion.chunk")
            .created(createdAt)
            .choices(List.of(choice))
            .build();
    }
}
