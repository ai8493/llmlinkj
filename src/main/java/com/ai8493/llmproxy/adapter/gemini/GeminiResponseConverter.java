package com.ai8493.llmproxy.adapter.gemini;

import com.google.genai.types.*;
import com.ai8493.llmproxy.converter.FunctionCallMapper;
import com.ai8493.llmproxy.model.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

public class GeminiResponseConverter {

    private final FunctionCallMapper functionCallMapper;

    public GeminiResponseConverter(FunctionCallMapper functionCallMapper) {
        this.functionCallMapper = functionCallMapper;
    }

    public UnifiedChatResponse toUnifiedResponse(GenerateContentResponse geminiResp) {
        // Handle empty candidates (safety block)
        if (geminiResp.candidates().orElse(List.of()).isEmpty()) {
            return new UnifiedChatResponse(
                "chatcmpl-" + UUID.randomUUID(),
                geminiResp.modelVersion().orElse("unknown"),
                "chat.completion",
                Instant.now().getEpochSecond(),
                List.of(new UnifiedChoice(0,
                    new UnifiedMessage(UnifiedMessage.Role.ASSISTANT, "", null, null, null, null, null),
                    null,
                    "content_filter",
                    null)),
                null,
                null
            );
        }

        List<UnifiedChoice> choices = geminiResp.candidates().orElse(List.of()).stream()
            .map(candidate -> {
                String text = "";
                List<Part> parts = candidate.content()
                    .flatMap(c -> c.parts())
                    .orElse(List.of());
                if (!parts.isEmpty()) {
                    text = parts.stream()
                        .filter(p -> p.text().isPresent())
                        .map(p -> p.text().get())
                        .collect(Collectors.joining());
                }

                String finishReason = candidate.finishReason()
                    .map(fr -> mapFinishReason(fr))
                    .orElse(null);

                return new UnifiedChoice(
                    candidate.index().orElse(0),
                    new UnifiedMessage(UnifiedMessage.Role.ASSISTANT, text,
                        null, functionCallMapper.mapToolCalls(parts), null, null, null),
                    null,
                    finishReason,
                    null
                );
            })
            .collect(Collectors.toList());

        UnifiedUsage usage = null;
        if (geminiResp.usageMetadata().isPresent()) {
            var meta = geminiResp.usageMetadata().get();
            usage = new UnifiedUsage(
                meta.promptTokenCount().orElse(0),
                meta.candidatesTokenCount().orElse(0),
                meta.totalTokenCount().orElse(0),
                0, 0
            );
        }

        return new UnifiedChatResponse(
            "chatcmpl-" + UUID.randomUUID(),
            geminiResp.modelVersion().orElse("unknown"),
            "chat.completion",
            Instant.now().getEpochSecond(),
            choices,
            usage,
            null
        );
    }

    private String mapFinishReason(FinishReason reason) {
        String value = reason.toString();
        return switch (value) {
            case "STOP" -> "stop";
            case "MAX_TOKENS" -> "length";
            case "SAFETY", "RECITATION" -> "content_filter";
            default -> "stop";
        };
    }
}
