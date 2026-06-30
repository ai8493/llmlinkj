package com.ai8493.llmproxy.adapter.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.core.JsonValue;
import com.openai.models.ChatModel;
import com.openai.models.FunctionDefinition;
import com.openai.models.FunctionParameters;
import com.openai.models.chat.completions.*;
import com.ai8493.llmproxy.model.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class OpenAiRequestConverter {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public ChatCompletionCreateParams convert(UnifiedChatRequest req) {
        // 预扫描：收集所有有效 tool_use ID，用于孤儿 tool_result 检测
        java.util.Set<String> validToolUseIds = new java.util.HashSet<>();
        if (req.messages() != null) {
            for (UnifiedMessage msg : req.messages()) {
                if (msg.role() == UnifiedMessage.Role.ASSISTANT && msg.toolCalls() != null) {
                    for (UnifiedToolCall tc : msg.toolCalls()) {
                        if (tc.id() != null && !tc.id().isEmpty()) {
                            validToolUseIds.add(tc.id());
                        }
                    }
                }
            }
        }

        var builder = ChatCompletionCreateParams.builder()
            .model(ChatModel.of(req.model()));

        if (req.messages() != null) {
            builder.messages(req.messages().stream()
                .map(msg -> toMessageParam(msg, validToolUseIds))
                .toList());
        }

        if (req.tools() != null && !req.tools().isEmpty()) {
            builder.tools(req.tools().stream()
                .map(this::toTool)
                .toList());
        }

        if (req.toolChoice() != null) {
            builder.toolChoice(toToolChoice(req.toolChoice()));
        }

        if (req.config() != null) {
            UnifiedGenerationConfig cfg = req.config();
            if (cfg.temperature() != null) builder.temperature(cfg.temperature());
            if (cfg.topP() != null) builder.topP(cfg.topP());
            if (cfg.maxOutputTokens() != null) builder.maxTokens(cfg.maxOutputTokens().longValue());
            if (cfg.stopSequences() != null && !cfg.stopSequences().isEmpty())
                builder.stop(ChatCompletionCreateParams.Stop.ofStrings(cfg.stopSequences()));
            // 新增字段映射
            if (cfg.user() != null) {
                builder.user(cfg.user());
            }
            if (cfg.parallelToolCalls() != null) {
                builder.parallelToolCalls(cfg.parallelToolCalls());
            }
        }

        return builder.build();
    }

    private ChatCompletionMessageParam toMessageParam(UnifiedMessage msg,
            java.util.Set<String> validToolUseIds) {
        return switch (msg.role()) {
            case SYSTEM -> {
                var b = ChatCompletionSystemMessageParam.builder()
                    .content(ChatCompletionSystemMessageParam.Content.ofText(msg.content()));
                if (msg.name() != null) b.name(msg.name());
                yield ChatCompletionMessageParam.ofSystem(b.build());
            }
            case USER -> {
                var b = ChatCompletionUserMessageParam.builder();
                if (msg.name() != null) b.name(msg.name());
                if (msg.parts() != null && !msg.parts().isEmpty()) {
                    // 多模态：从 parts 构建 content 数组
                    List<ChatCompletionContentPart> contentList = new ArrayList<>();
                    for (var part : msg.parts()) {
                        if ("text".equals(part.type()) && part.text() != null) {
                            contentList.add(ChatCompletionContentPart.ofText(
                                ChatCompletionContentPartText.builder()
                                    .text(part.text())
                                    .build()));
                        } else if ("image_url".equals(part.type()) && part.imageData() != null) {
                            var imageData = part.imageData();
                            var imageUrlBuilder = ChatCompletionContentPartImage.ImageUrl.builder()
                                .url(imageData.get("url").asText());
                            if (imageData.has("detail") && !imageData.get("detail").asText().isEmpty()) {
                                imageUrlBuilder.detail(
                                    ChatCompletionContentPartImage.ImageUrl.Detail.of(
                                        imageData.get("detail").asText()));
                            }
                            contentList.add(ChatCompletionContentPart.ofImageUrl(
                                ChatCompletionContentPartImage.builder()
                                    .imageUrl(imageUrlBuilder.build())
                                    .build()));
                        }
                    }
                    b.content(ChatCompletionUserMessageParam.Content.ofArrayOfContentParts(contentList));
                } else {
                    b.content(ChatCompletionUserMessageParam.Content.ofText(msg.content()));
                }
                yield ChatCompletionMessageParam.ofUser(b.build());
            }
            case ASSISTANT -> {
                var b = ChatCompletionAssistantMessageParam.builder();
                if (msg.name() != null) b.name(msg.name());
                if (msg.parts() != null && !msg.parts().isEmpty()) {
                    List<ChatCompletionAssistantMessageParam.Content.ChatCompletionRequestAssistantMessageContentPart> contentList = new ArrayList<>();
                    for (var part : msg.parts()) {
                        if ("text".equals(part.type()) && part.text() != null) {
                            contentList.add(ChatCompletionAssistantMessageParam.Content.ChatCompletionRequestAssistantMessageContentPart.ofText(
                                ChatCompletionContentPartText.builder()
                                    .text(part.text())
                                    .build()));
                        }
                    }
                    if (!contentList.isEmpty()) {
                        b.content(ChatCompletionAssistantMessageParam.Content.ofArrayOfContentParts(contentList));
                    }
                } else if (msg.content() != null) {
                    b.content(ChatCompletionAssistantMessageParam.Content.ofText(msg.content()));
                }
                if (msg.toolCalls() != null && !msg.toolCalls().isEmpty()) {
                    b.toolCalls(msg.toolCalls().stream()
                        .map(this::toMessageToolCall)
                        .toList());
                }
                if (msg.reasoningContent() != null) {
                    b.putAdditionalProperty("reasoning_content",
                        com.openai.core.JsonValue.from(msg.reasoningContent()));
                }
                // DeepSeek 要求 assistant 消息必须有 content 或 tool_calls
                if (msg.content() == null
                    && (msg.parts() == null || msg.parts().isEmpty())
                    && (msg.toolCalls() == null || msg.toolCalls().isEmpty())) {
                    b.content(ChatCompletionAssistantMessageParam.Content.ofText(""));
                }
                yield ChatCompletionMessageParam.ofAssistant(b.build());
            }
            case TOOL -> {
                String toolCallId = msg.toolCallId();
                // 孤儿 tool_result：引用的 tool_use 不在本次请求中，转为 user 文本避免 API 400
                if (toolCallId != null && !toolCallId.isEmpty()
                    && !validToolUseIds.isEmpty()
                    && !validToolUseIds.contains(toolCallId)) {
                    String label = msg.name() != null ? msg.name() : "tool";
                    String text = msg.content() != null ? msg.content() : "";
                    yield ChatCompletionMessageParam.ofUser(
                        ChatCompletionUserMessageParam.builder()
                            .content(ChatCompletionUserMessageParam.Content.ofText(
                                "[tool_result: " + label + " id=" + toolCallId + "] " + text))
                            .build());
                }
                yield ChatCompletionMessageParam.ofTool(
                    ChatCompletionToolMessageParam.builder()
                        .content(ChatCompletionToolMessageParam.Content.ofText(msg.content()))
                        .toolCallId(toolCallId)
                        .build()
                );
            }
        };
    }

    private ChatCompletionMessageToolCall toMessageToolCall(UnifiedToolCall tc) {
        return ChatCompletionMessageToolCall.ofFunction(
            ChatCompletionMessageFunctionToolCall.builder()
                .id(tc.id())
                .function(ChatCompletionMessageFunctionToolCall.Function.builder()
                    .name(tc.function().name())
                    .arguments(tc.function().arguments() != null
                        ? tc.function().arguments().toString() : "{}")
                    .build())
                .build()
        );
    }

    private ChatCompletionTool toTool(UnifiedTool tool) {
        if (tool.function() == null) {
            throw new IllegalArgumentException("仅支持 function 类型的 tool");
        }
        var fnBuilder = FunctionDefinition.builder()
            .name(tool.function().name())
            .description(tool.function().description());
        if (tool.function().parameters() != null) {
            fnBuilder.parameters(toFunctionParameters(tool.function().parameters()));
        }
        return ChatCompletionTool.ofFunction(
            ChatCompletionFunctionTool.builder()
                .function(fnBuilder.build())
                .build()
        );
    }

    private FunctionParameters toFunctionParameters(JsonNode node) {
        Map<String, JsonValue> props = new HashMap<>();
        if (node.isObject()) {
            node.fieldNames().forEachRemaining(fieldName ->
                props.put(fieldName, JsonValue.fromJsonNode(node.get(fieldName)))
            );
        }
        return FunctionParameters.builder()
            .additionalProperties(props)
            .build();
    }

    private ChatCompletionToolChoiceOption toToolChoice(UnifiedToolChoice tc) {
        return switch (tc) {
            case UnifiedToolChoice.None __ ->
                ChatCompletionToolChoiceOption.ofAuto(
                    ChatCompletionToolChoiceOption.Auto.NONE);
            case UnifiedToolChoice.Auto __ ->
                ChatCompletionToolChoiceOption.ofAuto(
                    ChatCompletionToolChoiceOption.Auto.AUTO);
            case UnifiedToolChoice.Required r ->
                ChatCompletionToolChoiceOption.ofNamedToolChoice(
                    ChatCompletionNamedToolChoice.builder()
                        .function(ChatCompletionNamedToolChoice.Function.builder()
                            .name(r.functionName())
                            .build())
                        .build());
        };
    }
}
