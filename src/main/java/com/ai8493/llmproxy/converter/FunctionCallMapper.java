package com.ai8493.llmproxy.converter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.genai.types.*;
import com.ai8493.llmproxy.model.*;
import java.util.*;

public class FunctionCallMapper {

    private final ObjectMapper mapper = new ObjectMapper();

    /** Gemini FunctionCall part → IR UnifiedToolCall list */
    public List<UnifiedToolCall> mapToolCalls(List<Part> parts) {
        if (parts == null) return List.of();
        return parts.stream()
            .filter(p -> p.functionCall().isPresent())
            .map(part -> {
                FunctionCall fc = part.functionCall().get();
                JsonNode argsNode = null;
                if (fc.args().isPresent()) {
                    argsNode = mapper.valueToTree(fc.args().get());
                }
                return UnifiedToolCall.builder()
                    .id(fc.id().orElse(null))
                    .type("function")
                    .function(UnifiedFunctionCall.builder()
                        .name(fc.name().orElse(""))
                        .arguments(argsNode)
                        .build())
                    .build();
            })
            .toList();
    }

    /** IR TOOL messages → Gemini FunctionResponse Content list (multi-turn) */
    public List<Content> mapToolResults(List<UnifiedMessage> toolMessages) {
        if (toolMessages == null) return List.of();
        return toolMessages.stream()
            .filter(m -> m.role() == UnifiedMessage.Role.TOOL)
            .map(m -> Content.builder()
                .role("user")
                .parts(List.of(Part.builder()
                    .functionResponse(FunctionResponse.builder()
                        .name(m.name() != null ? m.name() : "")
                        .response(Map.of("content", m.content() != null ? m.content() : ""))
                        .build())
                    .build()))
                .build())
            .toList();
    }
}
