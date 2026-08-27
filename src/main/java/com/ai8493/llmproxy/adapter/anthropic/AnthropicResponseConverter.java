package com.ai8493.llmproxy.adapter.anthropic;

import com.anthropic.models.messages.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ai8493.llmproxy.model.*;
import com.ai8493.llmproxy.model.extensions.AnthropicExtensions;
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
        String thinkingSignature = null;
        List<UnifiedToolCall> toolCalls = null;

        List<UnifiedPart> parts = new ArrayList<>();
        for (ContentBlock block : sdkResp.content()) {
            if (block.isText()) {
                textBuilder.append(block.asText().text());
            } else if (block.isThinking()) {
                // 第三方后端(如 ark-claude)可能不返回 thinking/signature 字段,用 _xxx().asKnown() 安全访问避免 SDK 抛 AnthropicInvalidDataException
                String thinking = block.asThinking()._thinking().asKnown().orElse(null);
                if (thinking != null) {
                    reasoningBuilder.append(thinking);
                }
                // signature 仅取最后一个 thinking block(多 block 场景少)
                String sig = block.asThinking()._signature().asKnown().orElse(null);
                if (sig != null && !sig.isEmpty()) {
                    thinkingSignature = sig;
                }
            } else if (block.isToolUse()) {
                ToolUseBlock tu = block.asToolUse();
                if (toolCalls == null) {
                    toolCalls = new ArrayList<>();
                }
                toolCalls.add(toUnifiedToolCall(tu));
            } else if (block.isRedactedThinking()) {
                var rt = block.asRedactedThinking();
                parts.add(new UnifiedPart.RedactedThinkingPart(
                    MAPPER.createObjectNode().put("data", rt.data())));
            } else {
                log.debug("非流式 block 未处理类型: {}", block);
            }
        }

        String text = textBuilder.isEmpty() ? null : textBuilder.toString();
        String reasoningContent = reasoningBuilder.isEmpty() ? null : reasoningBuilder.toString();

        // 2. 构建 UnifiedMessage
        UnifiedMessage message = UnifiedMessage.builder()
                .role(UnifiedMessage.Role.ASSISTANT)
                .content(text)
                .toolCalls(toolCalls)
                .reasoningContent(reasoningContent)
                .thinkingSignature(thinkingSignature)
                .parts(parts.isEmpty() ? null : parts)
                .build();

        // 3. StopReason 存 Anthropic 原值(spec 第 5 节:同协议零损失)
        String finishReason = sdkResp.stopReason()
                .map(StopReason::asString)
                .orElse(null);

        // 4. Choice
        UnifiedChoice choice = UnifiedChoice.builder()
                .index(0)
                .message(message)
                .finishReason(finishReason)
                .build();

        // 5. Usage
        UnifiedUsage usage = toUnifiedUsage(sdkResp.usage());

        // 6. Model
        String model = sdkResp.model().asString();

        // stopSequence -> AnthropicExtensions.matchedStopSequence + 原始响应 JSON 透传
        AnthropicExtensions.Builder extBuilder = AnthropicExtensions.builder();
        if (sdkResp.stopSequence().isPresent()) {
            extBuilder.matchedStopSequence(sdkResp.stopSequence().get());
        }
        // 原始响应 JSON 透传(anthropic->anthropic 同协议字段零损失)
        try {
            JsonNode rawMessage = com.anthropic.core.ObjectMappers.jsonMapper()
                .convertValue(sdkResp, JsonNode.class);
            extBuilder.responseRawMessage(rawMessage);
        } catch (Exception e) {
            log.warn("原始响应 JSON 序列化失败,跳过 responseRawMessage: {}", e.getMessage());
        }
        AnthropicExtensions anthropicExt = extBuilder.build();

        return UnifiedChatResponse.builder()
                .id(sdkResp.id())
                .model(model)
                .object("chat.completion")
                .created(Instant.now().getEpochSecond())
                .choices(List.of(choice))
                .usage(usage)
                .anthropic(anthropicExt)
                .build();
    }

    // ====== 内部方法 ======

    private UnifiedToolCall toUnifiedToolCall(ToolUseBlock tu) {
        JsonNode args = null;
        try {
            // tu._input() 返回 com.anthropic.core.JsonValue(子类 JsonObject)
            // 其 toString() 是 Map.toString() 格式 {key=value} 非合法 JSON,不能用 readTree
            // JsonObject.values() 有 @JsonValue 注解,convertValue 会正确序列化
            args = MAPPER.convertValue(tu._input(), JsonNode.class);
            log.debug("非流式 tool_use 转换: name={} id={} args={}", tu.name(), tu.id(), args);
        } catch (Exception e) {
            log.warn("tool_use input JSON 解析失败: id={} name={}", tu.id(), tu.name(), e);
        }
        return UnifiedToolCall.builder()
                .id(tu.id())
                .type("function")
                .function(UnifiedFunctionCall.builder()
                        .name(tu.name())
                        .arguments(args)
                        .build())
                .build();
    }

    private UnifiedUsage toUnifiedUsage(Usage usage) {
        // cache_read 和 cache_creation 是独立桶,应并存(非 else if 互斥)
        int cached = usage.cacheReadInputTokens().map(Long::intValue).orElse(0);
        int cacheCreation = usage.cacheCreationInputTokens().map(Long::intValue).orElse(0);
        int inputTokens = (int) usage.inputTokens();
        int outputTokens = (int) usage.outputTokens();
        return UnifiedUsage.builder()
                .promptTokens(inputTokens)
                .completionTokens(outputTokens)
                .totalTokens(inputTokens + outputTokens)
                .cachedTokens(cached)
                .cacheCreationTokens(cacheCreation)
                .build();
    }
}
