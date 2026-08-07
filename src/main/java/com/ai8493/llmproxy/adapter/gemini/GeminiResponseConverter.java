package com.ai8493.llmproxy.adapter.gemini;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.google.genai.types.*;
import com.ai8493.llmproxy.converter.FunctionCallMapper;
import com.ai8493.llmproxy.model.*;
import com.ai8493.llmproxy.model.extensions.GeminiExtensions;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

public class GeminiResponseConverter {

    private final FunctionCallMapper functionCallMapper;
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new Jdk8Module());

    public GeminiResponseConverter(FunctionCallMapper functionCallMapper) {
        this.functionCallMapper = functionCallMapper;
    }

    public UnifiedChatResponse toUnifiedResponse(GenerateContentResponse geminiResp) {
        // Handle empty candidates (safety block)
        if (geminiResp.candidates().orElse(List.of()).isEmpty()) {
            return UnifiedChatResponse.builder()
                .id("chatcmpl-" + UUID.randomUUID())
                .model(geminiResp.modelVersion().orElse("unknown"))
                .object("chat.completion")
                .created(Instant.now().getEpochSecond())
                .choices(List.of(UnifiedChoice.builder()
                    .index(0)
                    .message(UnifiedMessage.builder().role(UnifiedMessage.Role.ASSISTANT).content("").build())
                    .finishReason("content_filter")
                    .build()))
                .build();
        }

        List<UnifiedChoice> choices = geminiResp.candidates().orElse(List.of()).stream()
            .map(candidate -> {
                List<Part> parts = candidate.content()
                    .flatMap(c -> c.parts())
                    .orElse(List.of());

                StringBuilder textBuf = new StringBuilder();
                StringBuilder thoughtBuf = new StringBuilder();
                List<UnifiedPart> irParts = new ArrayList<>();
                for (Part p : parts) {
                    boolean isThought = p.thought().isPresent() && p.thought().get();
                    if (p.text().isPresent()) {
                        if (isThought) {
                            thoughtBuf.append(p.text().get());
                        } else {
                            textBuf.append(p.text().get());
                        }
                    }
                    if (p.inlineData().isPresent()) {
                        irParts.add(convertInlineDataToImagePart(p.inlineData().get()));
                    }
                }

                String text = !textBuf.isEmpty() ? textBuf.toString() : null;
                String reasoningContent = !thoughtBuf.isEmpty() ? thoughtBuf.toString() : null;
                List<UnifiedPart> finalParts = irParts.isEmpty() ? null : irParts;

                String finishReason = candidate.finishReason()
                    .map(fr -> mapFinishReason(fr))
                    .orElse(null);

                return UnifiedChoice.builder()
                    .index(candidate.index().orElse(0))
                    .message(UnifiedMessage.builder()
                        .role(UnifiedMessage.Role.ASSISTANT)
                        .content(text)
                        .reasoningContent(reasoningContent)
                        .parts(finalParts)
                        .toolCalls(functionCallMapper.mapToolCalls(parts))
                        .build())
                    .finishReason(finishReason)
                    .build();
            })
            .collect(Collectors.toList());

        UnifiedUsage usage = null;
        if (geminiResp.usageMetadata().isPresent()) {
            var meta = geminiResp.usageMetadata().get();
            var usageBuilder = UnifiedUsage.builder()
                .promptTokens(meta.promptTokenCount().orElse(0))
                .completionTokens(meta.candidatesTokenCount().orElse(0))
                .totalTokens(meta.totalTokenCount().orElse(0));
            if (meta.thoughtsTokenCount().isPresent()) {
                usageBuilder.reasoningTokens(meta.thoughtsTokenCount().get());
            }
            usage = usageBuilder.build();
        }

        GeminiExtensions geminiExt = extractGeminiExtensions(geminiResp, geminiResp.candidates().orElse(List.of()));

        return UnifiedChatResponse.builder()
            .id("chatcmpl-" + UUID.randomUUID())
            .model(geminiResp.modelVersion().orElse("unknown"))
            .object("chat.completion")
            .created(Instant.now().getEpochSecond())
            .choices(choices)
            .usage(usage)
            .gemini(geminiExt)
            .build();
    }

    private GeminiExtensions extractGeminiExtensions(GenerateContentResponse resp, List<Candidate> candidates) {
        GeminiExtensions.Builder extBuilder = GeminiExtensions.builder();
        boolean hasAny = false;

        // promptFeedback(响应级)
        if (resp.promptFeedback().isPresent()) {
            extBuilder.promptFeedback(mapper.valueToTree(resp.promptFeedback().get()));
            hasAny = true;
        }

        // candidate 级 metadata(safetyRatings/citationMetadata/groundingMetadata)
        // 取第一个 candidate 的(多 candidate 场景留待后续)
        if (!candidates.isEmpty()) {
            Candidate c = candidates.get(0);
            if (c.safetyRatings().isPresent() && !c.safetyRatings().get().isEmpty()) {
                extBuilder.safetySettings(mapper.valueToTree(c.safetyRatings().get()));
                hasAny = true;
            }
            if (c.citationMetadata().isPresent()) {
                extBuilder.citationMetadata(mapper.valueToTree(c.citationMetadata().get()));
                hasAny = true;
            }
            if (c.groundingMetadata().isPresent()) {
                extBuilder.groundingMetadata(mapper.valueToTree(c.groundingMetadata().get()));
                hasAny = true;
            }
        }

        return hasAny ? extBuilder.build() : null;
    }

    private UnifiedPart convertInlineDataToImagePart(Blob blob) {
        String mimeType = blob.mimeType().orElse("image/png");
        byte[] bytes = blob.data().orElse(new byte[0]);
        String base64 = Base64.getEncoder().encodeToString(bytes);
        ObjectNode imageData = mapper.createObjectNode();
        imageData.put("url", "data:" + mimeType + ";base64," + base64);
        imageData.putNull("detail");
        return new UnifiedPart.ImagePart(imageData);
    }

    private String mapFinishReason(FinishReason reason) {
        // spec 第 5 节:存原值,Converter 按合法性判断
        // Gemini 合法值:STOP/MAX_TOKENS/SAFETY/RECITATION/OTHER/FINISH_REASON_UNSPECIFIED
        // 直接返回 toString(),同协议出站可直接用
        return reason.toString();
    }
}
