package com.ai8493.llmproxy.adapter.gemini;

import com.google.genai.types.*;
import com.ai8493.llmproxy.model.*;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GeminiProtocolAdapterImageTest {

    private final GeminiProtocolAdapter adapter = new GeminiProtocolAdapter();

    @Test
    void shouldConvertInlineDataToImagePart() {
        // Gemini inlineData -> IR ImagePart(data URL 格式)
        byte[] pngBytes = new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
        var blob = Blob.builder()
            .mimeType("image/png")
            .data(pngBytes)
            .build();
        var imagePart = Part.builder().inlineData(blob).build();
        var content = Content.builder()
            .role("user")
            .parts(List.of(imagePart))
            .build();

        var result = adapter.toUnifiedRequest(
            GenerateContentParameters.builder()
                .model("gemini-2.0-flash")
                .contents(List.of(content))
                .build(),
            null, null, null, null);

        var msg = result.messages().get(0);
        assertThat(msg.role()).isEqualTo(UnifiedMessage.Role.USER);
        assertThat(msg.parts()).isNotNull();
        assertThat(msg.parts()).hasSize(1);
        var imgPart = (UnifiedPart.ImagePart) msg.parts().get(0);
        String expectedDataUrl = "data:image/png;base64,"
            + Base64.getEncoder().encodeToString(pngBytes);
        assertThat(imgPart.imageData().get("url").asText()).isEqualTo(expectedDataUrl);
    }

    @Test
    void shouldCombineTextAndImageIntoParts() {
        // text + image -> parts 含 TextPart(在前) + ImagePart
        byte[] pngBytes = "fake-png".getBytes(StandardCharsets.UTF_8);
        var blob = Blob.builder()
            .mimeType("image/png")
            .data(pngBytes)
            .build();
        var content = Content.builder()
            .role("user")
            .parts(List.of(
                Part.builder().text("看这张图").build(),
                Part.builder().inlineData(blob).build()
            ))
            .build();

        var result = adapter.toUnifiedRequest(
            GenerateContentParameters.builder()
                .model("gemini-2.0-flash")
                .contents(List.of(content))
                .build(),
            null, null, null, null);

        var msg = result.messages().get(0);
        assertThat(msg.parts()).hasSize(2);
        assertThat(msg.parts().get(0)).isInstanceOf(UnifiedPart.TextPart.class);
        assertThat(((UnifiedPart.TextPart) msg.parts().get(0)).text()).isEqualTo("看这张图");
        assertThat(msg.parts().get(1)).isInstanceOf(UnifiedPart.ImagePart.class);
        // content 也保留(text 非空时同时设置 content + parts,下游优先用 parts)
        assertThat(msg.content()).isEqualTo("看这张图");
    }

    @Test
    void shouldUseDefaultMimeTypeWhenAbsent() {
        // mimeType 缺失时默认 image/png
        byte[] bytes = "img".getBytes(StandardCharsets.UTF_8);
        var blob = Blob.builder().data(bytes).build();
        var imagePart = Part.builder().inlineData(blob).build();
        var content = Content.builder()
            .role("user")
            .parts(List.of(imagePart))
            .build();

        var result = adapter.toUnifiedRequest(
            GenerateContentParameters.builder()
                .model("gemini-2.0-flash")
                .contents(List.of(content))
                .build(),
            null, null, null, null);

        var msg = result.messages().get(0);
        var imgPart = (UnifiedPart.ImagePart) msg.parts().get(0);
        assertThat(imgPart.imageData().get("url").asText()).startsWith("data:image/png;base64,");
    }
}
