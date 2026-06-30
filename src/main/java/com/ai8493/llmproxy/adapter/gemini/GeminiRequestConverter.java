package com.ai8493.llmproxy.adapter.gemini;

import com.google.genai.types.*;
import com.ai8493.llmproxy.converter.ToolMapper;
import com.ai8493.llmproxy.model.*;
import java.util.List;
import java.util.stream.Collectors;

public class GeminiRequestConverter {

    private final ToolMapper toolMapper;

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
                    .parts(List.of(Part.builder().text(sysMsg.content()).build()))
                    .build()
            ));

        if (uReq.config() != null) {
            if (uReq.config().temperature() != null)
                configBuilder.temperature(uReq.config().temperature().floatValue());
            if (uReq.config().topP() != null)
                configBuilder.topP(uReq.config().topP().floatValue());
            if (uReq.config().maxOutputTokens() != null)
                configBuilder.maxOutputTokens(uReq.config().maxOutputTokens());
        }

        if (uReq.tools() != null && !uReq.tools().isEmpty()) {
            configBuilder.tools(toolMapper.mapTools(uReq.tools()));
        }
        if (uReq.toolChoice() != null) {
            configBuilder.toolConfig(toolMapper.mapToolChoice(uReq.toolChoice()));
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
            case TOOL -> "function";
            default -> "user";
        };

        Part part = Part.builder().text(msg.content()).build();
        return Content.builder()
            .role(geminiRole)
            .parts(List.of(part))
            .build();
    }
}
