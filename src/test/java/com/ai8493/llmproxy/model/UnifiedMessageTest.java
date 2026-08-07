package com.ai8493.llmproxy.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class UnifiedMessageTest {

    @Test
    void shouldBuildWithSystemBlocks() {
        List<UnifiedPart> systemBlocks = List.of(
            new UnifiedPart.TextPart("system instruction"),
            new UnifiedPart.TextPart("cache: extra context")
        );
        UnifiedMessage msg = UnifiedMessage.builder()
            .role(UnifiedMessage.Role.SYSTEM)
            .systemBlocks(systemBlocks)
            .build();
        assertThat(msg.systemBlocks()).hasSize(2);
        assertThat(msg.systemBlocks().get(0)).isInstanceOf(UnifiedPart.TextPart.class);
    }

    @Test
    void shouldBuildWithoutSystemBlocks() {
        UnifiedMessage msg = UnifiedMessage.builder()
            .role(UnifiedMessage.Role.USER)
            .content("hello")
            .build();
        assertThat(msg.systemBlocks()).isNull();
        assertThat(msg.content()).isEqualTo("hello");
    }

    @Test
    void shouldPreserveExistingFields() {
        UnifiedMessage msg = UnifiedMessage.builder()
            .role(UnifiedMessage.Role.ASSISTANT)
            .content("response")
            .thinkingSignature("sig_123")
            .build();
        assertThat(msg.role()).isEqualTo(UnifiedMessage.Role.ASSISTANT);
        assertThat(msg.thinkingSignature()).isEqualTo("sig_123");
    }

    @Test
    void shouldBuildWithRefusalAudioAnnotations() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode audio = mapper.readTree("{\"id\":\"audio-1\",\"data\":\"base64...\"}");
        JsonNode annotations = mapper.readTree("[{\"type\":\"citation\",\"text\":\"ref\"}]");
        UnifiedMessage msg = UnifiedMessage.builder()
            .role(UnifiedMessage.Role.ASSISTANT)
            .refusal("I can't help with that")
            .audio(audio)
            .annotations(annotations)
            .build();
        assertThat(msg.refusal()).isEqualTo("I can't help with that");
        assertThat(msg.audio()).isEqualTo(audio);
        assertThat(msg.annotations()).isEqualTo(annotations);
    }

    @Test
    void shouldDefaultNewMessageFieldsToNull() {
        UnifiedMessage msg = UnifiedMessage.builder()
            .role(UnifiedMessage.Role.USER)
            .content("hi")
            .build();
        assertThat(msg.refusal()).isNull();
        assertThat(msg.audio()).isNull();
        assertThat(msg.annotations()).isNull();
    }
}
