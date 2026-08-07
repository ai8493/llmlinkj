package com.ai8493.llmproxy.adapter.gemini;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.genai.types.*;
import com.ai8493.llmproxy.converter.ToolMapper;
import com.ai8493.llmproxy.model.*;
import com.ai8493.llmproxy.model.extensions.GeminiExtensions;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class GeminiRequestConverter {

    private final ToolMapper toolMapper;
    private final ObjectMapper mapper = new ObjectMapper();

    public GeminiRequestConverter(ToolMapper toolMapper) {
        this.toolMapper = toolMapper;
    }

    public GenerateContentParameters toGeminiRequest(UnifiedChatRequest uReq) {
        if (uReq.messages() == null || uReq.messages().isEmpty()) {
            throw new IllegalArgumentException("messages 不能为空");
        }

        List<Content> contents = uReq.messages().stream()
            .filter(m -> m.role() != UnifiedMessage.Role.SYSTEM)
            .map(this::mapMessage)
            .collect(Collectors.toList());

        // Build config
        GenerateContentConfig.Builder configBuilder = GenerateContentConfig.builder();

        // Extract system message as systemInstruction
        uReq.messages().stream()
            .filter(m -> m.role() == UnifiedMessage.Role.SYSTEM)
            .findFirst()
            .ifPresent(sysMsg -> configBuilder.systemInstruction(
                Content.builder()
                    .role("user")
                    .parts(List.of(Part.builder().text(
                        com.ai8493.llmproxy.util.BillingHeaderStripper.strip(sysMsg.content())).build()))
                    .build()
            ));

        if (uReq.config() != null) {
            if (uReq.config().temperature() != null)
                configBuilder.temperature(uReq.config().temperature().floatValue());
            if (uReq.config().topP() != null)
                configBuilder.topP(uReq.config().topP().floatValue());
            if (uReq.config().maxOutputTokens() != null)
                configBuilder.maxOutputTokens(uReq.config().maxOutputTokens());
            if (uReq.config().topK() != null)
                configBuilder.topK(uReq.config().topK().floatValue());
            if (uReq.config().stopSequences() != null && !uReq.config().stopSequences().isEmpty())
                configBuilder.stopSequences(uReq.config().stopSequences());
            if (uReq.config().presencePenalty() != null)
                configBuilder.presencePenalty(uReq.config().presencePenalty().floatValue());
            if (uReq.config().frequencyPenalty() != null)
                configBuilder.frequencyPenalty(uReq.config().frequencyPenalty().floatValue());
            if (uReq.config().seed() != null)
                configBuilder.seed(uReq.config().seed().intValue());
            if (uReq.config().mediaResolution() != null)
                configBuilder.mediaResolution(uReq.config().mediaResolution());
            if (uReq.config().thinkingConfig() != null) {
                var tc = uReq.config().thinkingConfig();
                com.google.genai.types.ThinkingConfig.Builder tcBuilder =
                    com.google.genai.types.ThinkingConfig.builder();
                if (tc.budgetTokens() != null) tcBuilder.thinkingBudget(tc.budgetTokens());
                if ("enabled".equalsIgnoreCase(tc.type())) tcBuilder.includeThoughts(true);
                configBuilder.thinkingConfig(tcBuilder.build());
            }
        }

        boolean hasTools = uReq.tools() != null && !uReq.tools().isEmpty();
        if (hasTools) {
            configBuilder.tools(toolMapper.mapTools(uReq.tools()));
        }
        // P3-12: 无 tools 时不发送 toolConfig,避免后端 400
        if (hasTools && uReq.toolChoice() != null) {
            configBuilder.toolConfig(toolMapper.mapToolChoice(uReq.toolChoice()));
        }

        // 从 GeminiExtensions 重建 Gemini 专属字段
        if (uReq.gemini() != null) {
            GeminiExtensions gExt = uReq.gemini();
            if (gExt.responseMimeType() != null && !gExt.responseMimeType().isEmpty()) {
                configBuilder.responseMimeType(gExt.responseMimeType());
            }
            if (gExt.responseSchema() != null) {
                configBuilder.responseSchema(toSchema(gExt.responseSchema()));
            }
            if (gExt.safetySettings() != null && gExt.safetySettings().isArray()) {
                configBuilder.safetySettings(toSafetySettings(gExt.safetySettings()));
            }
            if (gExt.candidateCount() != null) {
                configBuilder.candidateCount(gExt.candidateCount());
            }
            // gExt.tools 含 googleSearch/codeExecution 等内置工具,合并到 configBuilder.tools
            if (gExt.tools() != null && gExt.tools().isArray()) {
                List<com.google.genai.types.Tool> extTools = parseGeminiTools(gExt.tools());
                if (!extTools.isEmpty()) {
                    if (hasTools) {
                        List<com.google.genai.types.Tool> merged = new ArrayList<>(
                            configBuilder.build().tools().orElse(List.of()));
                        merged.addAll(extTools);
                        configBuilder.tools(merged);
                    } else {
                        configBuilder.tools(extTools);
                    }
                }
            }
        }

        return GenerateContentParameters.builder()
            .model(uReq.model())
            .contents(contents)
            .config(configBuilder.build())
            .build();
    }

    private Content mapMessage(UnifiedMessage msg) {
        String geminiRole = switch (msg.role()) {
            case USER -> "user";
            case ASSISTANT -> "model";
            case TOOL -> "user";
            default -> "user";
        };

        List<Part> parts = new ArrayList<>();

        if (msg.role() == UnifiedMessage.Role.TOOL) {
            parts.add(buildFunctionResponsePart(msg));
        } else {
            if (msg.role() == UnifiedMessage.Role.ASSISTANT && msg.reasoningContent() != null
                    && !msg.reasoningContent().isEmpty()) {
                parts.add(Part.builder().thought(true).text(msg.reasoningContent()).build());
            }
            if (msg.toolCalls() != null) {
                for (UnifiedToolCall tc : msg.toolCalls()) {
                    Part fcPart = buildFunctionCallPart(tc);
                    if (fcPart != null) parts.add(fcPart);
                }
            }
            if (msg.parts() != null) {
                for (UnifiedPart up : msg.parts()) {
                    Part p = mapUnifiedPart(up);
                    if (p != null) parts.add(p);
                }
            }
            if (msg.content() != null && !msg.content().isEmpty()) {
                parts.add(Part.builder().text(msg.content()).build());
            }
            if (parts.isEmpty()) {
                parts.add(Part.builder().text("").build());
            }
        }

        return Content.builder()
            .role(geminiRole)
            .parts(parts)
            .build();
    }

    private Part buildFunctionResponsePart(UnifiedMessage msg) {
        FunctionResponse.Builder frBuilder = FunctionResponse.builder()
            .name(msg.name() != null ? msg.name() : "");
        if (msg.content() != null) {
            try {
                Map<String, Object> response = mapper.readValue(msg.content(),
                    new TypeReference<Map<String, Object>>() {});
                frBuilder.response(response);
            } catch (Exception e) {
                frBuilder.response(Map.of("content", msg.content()));
            }
        }
        if (msg.toolCallId() != null) {
            frBuilder.id(msg.toolCallId());
        }
        return Part.builder().functionResponse(frBuilder.build()).build();
    }

    private Part buildFunctionCallPart(UnifiedToolCall tc) {
        if (tc.function() == null || tc.function().arguments() == null) return null;
        Map<String, Object> args = new HashMap<>();
        try {
            args = mapper.readValue(mapper.writeValueAsString(tc.function().arguments()),
                new TypeReference<Map<String, Object>>() {});
        } catch (Exception ignored) {}
        var fcBuilder = FunctionCall.builder()
            .name(tc.function().name())
            .args(args);
        if (tc.id() != null) fcBuilder.id(tc.id());
        return Part.builder().functionCall(fcBuilder.build()).build();
    }

    private Part mapUnifiedPart(UnifiedPart up) {
        if (up instanceof UnifiedPart.TextPart t) {
            return Part.builder().text(t.text()).build();
        }
        if (up instanceof UnifiedPart.ThinkingPart t && t.thinking() != null) {
            return Part.builder().thought(true).text(t.thinking()).build();
        }
        if (up instanceof UnifiedPart.ImagePart img) {
            return convertImagePartToInlineData(img);
        }
        return null;
    }

    private Part convertImagePartToInlineData(UnifiedPart.ImagePart img) {
        try {
            String url = img.imageData().path("url").asText("");
            if (!url.startsWith("data:")) return null;
            String[] parts = url.substring(5).split(",", 2);
            if (parts.length != 2) return null;
            String mimeType = parts[0].split(";")[0];
            byte[] bytes = java.util.Base64.getDecoder().decode(parts[1]);
            return Part.builder().inlineData(Blob.builder()
                .mimeType(mimeType)
                .data(bytes)
                .build()).build();
        } catch (Exception e) {
            return null;
        }
    }

    private List<com.google.genai.types.Tool> parseGeminiTools(JsonNode toolsNode) {
        List<com.google.genai.types.Tool> result = new ArrayList<>();
        for (JsonNode t : toolsNode) {
            com.google.genai.types.Tool.Builder tb = com.google.genai.types.Tool.builder();
            if (t.has("googleSearch")) {
                tb.googleSearch(com.google.genai.types.GoogleSearch.builder().build());
            }
            if (t.has("googleSearchRetrieval")) {
                tb.googleSearchRetrieval(
                    com.google.genai.types.GoogleSearchRetrieval.builder().build());
            }
            if (t.has("codeExecution")) {
                tb.codeExecution(com.google.genai.types.ToolCodeExecution.builder().build());
            }
            if (t.has("urlContext")) {
                tb.urlContext(com.google.genai.types.UrlContext.builder().build());
            }
            result.add(tb.build());
        }
        return result;
    }

    // 从 JsonNode 重建 Schema(仅支持 type + description,复杂 schema 留待后续扩展)
    private Schema toSchema(JsonNode node) {
        Schema.Builder b = Schema.builder();
        if (node.has("type")) {
            // Type 是 wrapper 类,String 构造器自动 fallback 到 TYPE_UNSPECIFIED
            b.type(new Type(node.path("type").asText()));
        }
        if (node.has("description")) {
            b.description(node.path("description").asText());
        }
        return b.build();
    }

    // 从 JsonNode 数组重建 SafetySetting 列表
    // HarmCategory / HarmBlockThreshold 是 wrapper 类,String 构造器自动 fallback
    private List<SafetySetting> toSafetySettings(JsonNode arr) {
        List<SafetySetting> result = new ArrayList<>();
        for (JsonNode ss : arr) {
            SafetySetting.Builder b = SafetySetting.builder();
            if (ss.has("category")) {
                b.category(ss.path("category").asText());
            }
            if (ss.has("threshold")) {
                b.threshold(ss.path("threshold").asText());
            }
            result.add(b.build());
        }
        return result;
    }
}
