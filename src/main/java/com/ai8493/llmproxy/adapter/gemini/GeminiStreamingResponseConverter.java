package com.ai8493.llmproxy.adapter.gemini;

import com.google.genai.types.*;
import com.ai8493.llmproxy.model.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public class GeminiStreamingResponseConverter {

    private final String chunkId;
    private final String model;

    public GeminiStreamingResponseConverter(String model) {
        this.chunkId = "chatcmpl-" + UUID.randomUUID();
        this.model = model;
    }

    public UnifiedChatResponse toUnifiedStreamChunk(GenerateContentResponse geminiChunk) {
        List<UnifiedChoice> choices = geminiChunk.candidates().orElse(List.of()).stream()
            .map(candidate -> {
                String deltaText = "";
                List<Part> parts = candidate.content()
                    .flatMap(c -> c.parts())
                    .orElse(List.of());
                if (!parts.isEmpty()) {
                    deltaText = parts.stream()
                        .filter(p -> p.text().isPresent())
                        .map(p -> p.text().get())
                        .findFirst()
                        .orElse("");
                }

                String finishReason = candidate.finishReason()
                    .map(fr -> mapFinishReason(fr))
                    .orElse(null);

                return new UnifiedChoice(
                    candidate.index().orElse(0),
                    null,
                    new UnifiedDelta(null, deltaText, null, null),
                    finishReason,
                    null
                );
            })
            .toList();

        return new UnifiedChatResponse(
            chunkId,
            model,
            "chat.completion.chunk",
            Instant.now().getEpochSecond(),
            choices,
            null,
            null
        );
    }

    private String mapFinishReason(FinishReason reason) {
        String value = reason.toString();
        return switch (value) {
            case "STOP" -> "stop";
            case "MAX_TOKENS" -> "length";
            case "SAFETY", "RECITATION" -> "content_filter";
            default -> null;
        };
    }
}
