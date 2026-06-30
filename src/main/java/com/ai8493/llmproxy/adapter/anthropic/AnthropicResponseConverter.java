package com.ai8493.llmproxy.adapter.anthropic;

import com.anthropic.models.messages.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ai8493.llmproxy.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 将 Anthropic SDK Message（非流式响应）转换为 IR UnifiedChatResponse。
 */
public class AnthropicResponseConverter {

    private static final Logger log = LoggerFactory.getLogger(AnthropicResponseConverter.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public UnifiedChatResponse convert(Message sdkResp) {
        // 1. 遍历 content blocks，分别提取 text、reasoning、tool_use
        StringBuilder textBuilder = new StringBuilder();
        StringBuilder reasoningBuilder = new StringBuilder();
        List<UnifiedToolCall> toolCalls = null;

        for (ContentBlock block : sdkResp.content()) {
            if (block.isText()) {
                textBuilder.append(block.asText().text());
            } else if (block.isThinking()) {
                reasoningBuilder.append(block.asThinking().thinking());
            } else if (block.isToolUse()) {
                ToolUseBlock tu = block.asToolUse();
                if (toolCalls == null) {
                    toolCalls = new ArrayList<>();
                }
                toolCalls.add(toUnifiedToolCall(tu));
            } else {
                log.debug("非流式 block 未处理类型: {}", block);
            }
        }

        String text = textBuilder.isEmpty() ? null : textBuilder.toString();
        String reasoningContent = reasoningBuilder.isEmpty() ? null : reasoningBuilder.toString();

        // 2. 构建 UnifiedMessage
        UnifiedMessage message = new UnifiedMessage(
                UnifiedMessage.Role.ASSISTANT,
                text,
                null,
                toolCalls,
                null,
                null,
                reasoningContent
        );

        // 3. StopReason 映射
        String finishReason = sdkResp.stopReason()
                .map(r -> mapStopReason(r.known()))
                .orElse("stop");

        // 4. Choice
        UnifiedChoice choice = new UnifiedChoice(0, message, null, finishReason, null);

        // 5. Usage
        UnifiedUsage usage = toUnifiedUsage(sdkResp.usage());

        // 6. Model
        String model = sdkResp.model().asString();

        return new UnifiedChatResponse(
                sdkResp.id(),
                model,
                "chat.completion",
                Instant.now().getEpochSecond(),
                List.of(choice),
                usage,
                null
        );
    }

    // ====== 内部方法 ======

    private String mapStopReason(StopReason.Known known) {
        if (known == null) return "stop";
        return switch (known) {
            case END_TURN, STOP_SEQUENCE, TOOL_USE -> "stop";
            case MAX_TOKENS -> "length";
            case REFUSAL -> "content_filter";
            default -> "stop";
        };
    }

    private UnifiedToolCall toUnifiedToolCall(ToolUseBlock tu) {
        JsonNode args = null;
        try {
            args = MAPPER.readTree(tu._input().toString());
            log.debug("非流式 tool_use 转换: name={} id={} args={}", tu.name(), tu.id(), args);
        } catch (Exception e) {
            log.warn("tool_use input JSON 解析失败: id={} name={}", tu.id(), tu.name(), e);
        }
        return new UnifiedToolCall(
                tu.id(),
                "function",
                new UnifiedFunctionCall(tu.name(), args)
        );
    }

    private UnifiedUsage toUnifiedUsage(Usage usage) {
        int cached = 0;
        if (usage.cacheReadInputTokens().isPresent()) {
            cached = usage.cacheReadInputTokens().get().intValue();
        } else if (usage.cacheCreationInputTokens().isPresent()) {
            cached = usage.cacheCreationInputTokens().get().intValue();
        }
        int inputTokens = (int) usage.inputTokens();
        int outputTokens = (int) usage.outputTokens();
        return new UnifiedUsage(
                inputTokens,
                outputTokens,
                inputTokens + outputTokens,
                cached,
                0
        );
    }
}
