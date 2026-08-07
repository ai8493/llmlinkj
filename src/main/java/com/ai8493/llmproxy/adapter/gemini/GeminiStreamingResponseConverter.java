package com.ai8493.llmproxy.adapter.gemini;

import com.google.genai.types.*;
import com.ai8493.llmproxy.converter.FunctionCallMapper;
import com.ai8493.llmproxy.model.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public class GeminiStreamingResponseConverter {

    private final String chunkId;
    private final String model;
    private final FunctionCallMapper functionCallMapper = new FunctionCallMapper();

    public GeminiStreamingResponseConverter(String model) {
        this.chunkId = "chatcmpl-" + UUID.randomUUID();
        this.model = model;
    }

    public UnifiedChatResponse toUnifiedStreamChunk(GenerateContentResponse geminiChunk) {
        List<UnifiedChoice> choices = geminiChunk.candidates().orElse(List.of()).stream()
            .map(candidate -> {
                List<Part> parts = candidate.content()
                    .flatMap(c -> c.parts())
                    .orElse(List.of());

                StringBuilder textBuf = new StringBuilder();
                StringBuilder thoughtBuf = new StringBuilder();
                for (Part p : parts) {
                    boolean isThought = p.thought().isPresent() && p.thought().get();
                    if (p.text().isPresent()) {
                        if (isThought) {
                            thoughtBuf.append(p.text().get());
                        } else {
                            textBuf.append(p.text().get());
                        }
                    }
                }

                String deltaText = !textBuf.isEmpty() ? textBuf.toString() : null;
                String reasoningContent = !thoughtBuf.isEmpty() ? thoughtBuf.toString() : null;
                List<UnifiedToolCall> toolCalls = functionCallMapper.mapToolCalls(parts);
                if (toolCalls.isEmpty()) toolCalls = null;

                String finishReason = candidate.finishReason()
                    .map(fr -> mapFinishReason(fr))
                    .orElse(null);

                return UnifiedChoice.builder()
                    .index(candidate.index().orElse(0))
                    .delta(UnifiedDelta.builder()
                        .content(deltaText)
                        .reasoningContent(reasoningContent)
                        .toolCalls(toolCalls)
                        .build())
                    .finishReason(finishReason)
                    .build();
            })
            .toList();

        UnifiedUsage usage = null;
        if (geminiChunk.usageMetadata().isPresent()) {
            var meta = geminiChunk.usageMetadata().get();
            var usageBuilder = UnifiedUsage.builder()
                .promptTokens(meta.promptTokenCount().orElse(0))
                .completionTokens(meta.candidatesTokenCount().orElse(0))
                .totalTokens(meta.totalTokenCount().orElse(0));
            if (meta.thoughtsTokenCount().isPresent()) {
                usageBuilder.reasoningTokens(meta.thoughtsTokenCount().get());
            }
            usage = usageBuilder.build();
        }

        return UnifiedChatResponse.builder()
            .id(chunkId)
            .model(model)
            .object("chat.completion.chunk")
            .created(Instant.now().getEpochSecond())
            .choices(choices)
            .usage(usage)
            .build();
    }

    private String mapFinishReason(FinishReason reason) {
        // spec 第 5 节:存原值。流式 default 仍返回 null(未结束)
        String value = reason.toString();
        return switch (value) {
            case "FINISH_REASON_UNSPECIFIED", "STOP", "MAX_TOKENS", "SAFETY", "RECITATION",
                 "LANGUAGE", "OTHER", "BLOCKLIST", "PROHIBITED_CONTENT", "SPII",
                 "MALFORMED_FUNCTION_CALL", "IMAGE_SAFETY", "UNEXPECTED_TOOL_CALL",
                 "IMAGE_PROHIBITED_CONTENT", "NO_IMAGE", "IMAGE_RECITATION", "IMAGE_OTHER" -> value;
            default -> null;
        };
    }
}
