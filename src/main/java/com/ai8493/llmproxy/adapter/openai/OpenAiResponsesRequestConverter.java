package com.ai8493.llmproxy.adapter.openai;

import com.ai8493.llmproxy.config.BackendConfig;
import com.ai8493.llmproxy.model.UnifiedChatRequest;
import com.ai8493.llmproxy.model.UnifiedGenerationConfig;
import com.ai8493.llmproxy.model.UnifiedMessage;
import com.ai8493.llmproxy.model.UnifiedTool;
import com.ai8493.llmproxy.model.UnifiedToolCall;
import com.ai8493.llmproxy.model.UnifiedToolChoice;
import com.openai.models.responses.EasyInputMessage;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseFunctionToolCall;
import com.openai.models.responses.ResponseInputItem;

import java.util.ArrayList;
import java.util.List;

/**
 * 将 UnifiedChatRequest 转换为 OpenAI Responses API 的 ResponseCreateParams。
 * 当前 Task 3 仅实现 model/input/instructions 基础映射,后续 Task 4-6 补充 config/tools/extensions。
 */
public class OpenAiResponsesRequestConverter {

    public ResponseCreateParams convert(UnifiedChatRequest req) {
        return convert(req, null);
    }

    public ResponseCreateParams convert(UnifiedChatRequest req, BackendConfig backendConfig) {
        // 收集 SYSTEM 消息 -> instructions,其他消息 -> input items
        List<String> systemTexts = new ArrayList<>();
        List<ResponseInputItem> inputItems = new ArrayList<>();

        if (req.messages() != null) {
            for (UnifiedMessage msg : req.messages()) {
                if (msg.role() == UnifiedMessage.Role.SYSTEM) {
                    if (msg.content() != null && !msg.content().isEmpty()) {
                        systemTexts.add(msg.content());
                    }
                    continue;
                }
                // assistant 仅 toolCalls 无文本时,不创建空 EasyInputMessage
                boolean assistantToolCallOnly = msg.role() == UnifiedMessage.Role.ASSISTANT
                    && (msg.content() == null || msg.content().isEmpty())
                    && msg.toolCalls() != null && !msg.toolCalls().isEmpty();
                ResponseInputItem item = assistantToolCallOnly ? null : toInputItem(msg);
                if (item != null) inputItems.add(item);
                // assistant + toolCalls:追加 FunctionCall items(Responses API 要求拆分)
                if (msg.role() == UnifiedMessage.Role.ASSISTANT && msg.toolCalls() != null) {
                    for (UnifiedToolCall tc : msg.toolCalls()) {
                        String argsStr = tc.function().arguments() != null
                            ? tc.function().arguments().toString() : "{}";
                        inputItems.add(ResponseInputItem.ofFunctionCall(
                            ResponseFunctionToolCall.builder()
                                .callId(tc.id() != null ? tc.id() : "")
                                .name(tc.function().name())
                                .arguments(argsStr)
                                .build()));
                    }
                }
            }
        }

        var builder = ResponseCreateParams.builder()
            .model(req.model());

        if (!systemTexts.isEmpty()) {
            builder.instructions(String.join("\n\n", systemTexts));
        }
        if (!inputItems.isEmpty()) {
            builder.input(ResponseCreateParams.Input.ofResponse(inputItems));
        }

        if (req.config() != null) {
            UnifiedGenerationConfig cfg = req.config();
            if (cfg.temperature() != null) builder.temperature(cfg.temperature());
            if (cfg.topP() != null) builder.topP(cfg.topP());
            if (cfg.maxOutputTokens() != null) builder.maxOutputTokens(cfg.maxOutputTokens().longValue());
            if (cfg.parallelToolCalls() != null) builder.parallelToolCalls(cfg.parallelToolCalls());
            if (cfg.user() != null) builder.user(cfg.user());
            if (cfg.reasoningEffort() != null) {
                builder.reasoning(com.openai.models.Reasoning.builder()
                    .effort(com.openai.models.ReasoningEffort.of(cfg.reasoningEffort()))
                    .build());
            }
        }

        if (req.tools() != null && !req.tools().isEmpty()) {
            builder.tools(req.tools().stream()
                .map(this::toResponseTool)
                .toList());
        }
        boolean hasTools = req.tools() != null && !req.tools().isEmpty();
        if (hasTools && req.toolChoice() != null) {
            builder.toolChoice(toResponseToolChoice(req.toolChoice()));
        }

        if (req.openai() != null) {
            var ext = req.openai();
            if (ext.store() != null) builder.store(ext.store());
            if (ext.metadata() != null && ext.metadata().isObject()) {
                var metaBuilder = ResponseCreateParams.Metadata.builder();
                ext.metadata().fieldNames().forEachRemaining(name ->
                    metaBuilder.putAdditionalProperty(name,
                        com.openai.core.JsonValue.fromJsonNode(ext.metadata().get(name))));
                builder.metadata(metaBuilder.build());
            }
            if (ext.previousResponseId() != null && !ext.previousResponseId().isEmpty()) {
                builder.previousResponseId(ext.previousResponseId());
            }
            if (ext.include() != null && !ext.include().isEmpty()) {
                builder.include(ext.include().stream()
                    .map(com.openai.models.responses.ResponseIncludable::of)
                    .toList());
            }
        }

        return builder.build();
    }

    private ResponseInputItem toInputItem(UnifiedMessage msg) {
        return switch (msg.role()) {
            case USER -> ResponseInputItem.ofEasyInputMessage(EasyInputMessage.builder()
                .role(EasyInputMessage.Role.USER)
                .content(msg.content() != null ? msg.content() : "")
                .build());
            case ASSISTANT -> ResponseInputItem.ofEasyInputMessage(EasyInputMessage.builder()
                .role(EasyInputMessage.Role.ASSISTANT)
                .content(msg.content() != null ? msg.content() : "")
                .build());
            case TOOL -> ResponseInputItem.ofFunctionCallOutput(
                ResponseInputItem.FunctionCallOutput.builder()
                    .callId(msg.toolCallId() != null ? msg.toolCallId() : "")
                    .output(msg.content() != null ? msg.content() : "")
                    .build());
            case SYSTEM -> null; // 已合并到 instructions
        };
    }

    private com.openai.models.responses.Tool toResponseTool(UnifiedTool tool) {
        if (tool.function() != null) {
            var fnBuilder = com.openai.models.responses.FunctionTool.builder()
                .name(tool.function().name())
                .strict(true);
            if (tool.function().description() != null) {
                fnBuilder.description(tool.function().description());
            }
            // SDK 要求 parameters 必填,null 时由 toFunctionToolParameters 产生空对象兜底
            fnBuilder.parameters(toFunctionToolParameters(tool.function().parameters()));
            return com.openai.models.responses.Tool.ofFunction(fnBuilder.build());
        }
        // rawTool 承接内置工具(web_search/file_search/computer_use 等)
        if (tool.rawTool() != null) {
            return toBuiltinTool(tool.rawTool());
        }
        throw new IllegalArgumentException("不支持的 tool 类型");
    }

    private com.openai.models.responses.Tool toBuiltinTool(com.fasterxml.jackson.databind.JsonNode raw) {
        String type = raw.path("type").asText("");
        return switch (type) {
            case "web_search" -> com.openai.models.responses.Tool.ofWebSearch(
                com.openai.models.responses.WebSearchTool.builder().build());
            case "file_search" -> com.openai.models.responses.Tool.ofFileSearch(
                com.openai.models.responses.FileSearchTool.builder().build());
            case "computer_use_preview", "computer_use" -> com.openai.models.responses.Tool.ofComputerUsePreview(
                com.openai.models.responses.ComputerUsePreviewTool.builder().build());
            default -> throw new IllegalArgumentException("不支持的内置工具类型: " + type);
        };
    }

    private com.openai.models.responses.FunctionTool.Parameters toFunctionToolParameters(
            com.fasterxml.jackson.databind.JsonNode params) {
        var builder = com.openai.models.responses.FunctionTool.Parameters.builder();
        if (params != null && params.isObject()) {
            params.fieldNames().forEachRemaining(name ->
                builder.putAdditionalProperty(name,
                    com.openai.core.JsonValue.fromJsonNode(params.get(name))));
        }
        return builder.build();
    }

    private ResponseCreateParams.ToolChoice toResponseToolChoice(UnifiedToolChoice tc) {
        return switch (tc) {
            case UnifiedToolChoice.None __ ->
                ResponseCreateParams.ToolChoice.ofOptions(
                    com.openai.models.responses.ToolChoiceOptions.NONE);
            case UnifiedToolChoice.Auto __ ->
                ResponseCreateParams.ToolChoice.ofOptions(
                    com.openai.models.responses.ToolChoiceOptions.AUTO);
            case UnifiedToolChoice.Required r ->
                ResponseCreateParams.ToolChoice.ofFunction(
                    com.openai.models.responses.ToolChoiceFunction.builder()
                        .name(r.functionName()).build());
            case UnifiedToolChoice.Any __ ->
                ResponseCreateParams.ToolChoice.ofOptions(
                    com.openai.models.responses.ToolChoiceOptions.REQUIRED);
        };
    }
}
