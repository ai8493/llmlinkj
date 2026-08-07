package com.ai8493.llmproxy.model;

import com.ai8493.llmproxy.model.extensions.ThinkingConfig;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class UnifiedGenerationConfigTest {

    @Test
    void shouldBuildWithNewFields() {
        ThinkingConfig thinking = ThinkingConfig.builder()
            .type("enabled")
            .budgetTokens(10000)
            .build();
        UnifiedGenerationConfig config = UnifiedGenerationConfig.builder()
            .topK(40)
            .thinkingConfig(thinking)
            .serviceTier("priority")
            .build();
        assertThat(config.topK()).isEqualTo(40);
        assertThat(config.thinkingConfig()).isEqualTo(thinking);
        assertThat(config.serviceTier()).isEqualTo("priority");
    }

    @Test
    void shouldBuildWithDefaultsForNewFields() {
        UnifiedGenerationConfig config = UnifiedGenerationConfig.builder().build();
        assertThat(config.topK()).isNull();
        assertThat(config.thinkingConfig()).isNull();
        assertThat(config.serviceTier()).isNull();
    }

    @Test
    void shouldPreserveExistingFields() {
        UnifiedGenerationConfig config = UnifiedGenerationConfig.builder()
            .temperature(0.7)
            .topP(0.9)
            .maxOutputTokens(1000)
            .build();
        assertThat(config.temperature()).isEqualTo(0.7);
        assertThat(config.topP()).isEqualTo(0.9);
        assertThat(config.maxOutputTokens()).isEqualTo(1000);
    }

    @Test
    void shouldBuildWithNewConfigFields() {
        UnifiedGenerationConfig config = UnifiedGenerationConfig.builder()
            .presencePenalty(0.5)
            .frequencyPenalty(0.3)
            .seed(42L)
            .maxCompletionTokens(2048)
            .mediaResolution("MEDIA_RESOLUTION_HIGH")
            .build();
        assertThat(config.presencePenalty()).isEqualTo(0.5);
        assertThat(config.frequencyPenalty()).isEqualTo(0.3);
        assertThat(config.seed()).isEqualTo(42L);
        assertThat(config.maxCompletionTokens()).isEqualTo(2048);
        assertThat(config.mediaResolution()).isEqualTo("MEDIA_RESOLUTION_HIGH");
    }

    @Test
    void shouldDefaultNewConfigFieldsToNull() {
        UnifiedGenerationConfig config = UnifiedGenerationConfig.builder().temperature(0.7).build();
        assertThat(config.presencePenalty()).isNull();
        assertThat(config.frequencyPenalty()).isNull();
        assertThat(config.seed()).isNull();
        assertThat(config.maxCompletionTokens()).isNull();
        assertThat(config.mediaResolution()).isNull();
    }
}
