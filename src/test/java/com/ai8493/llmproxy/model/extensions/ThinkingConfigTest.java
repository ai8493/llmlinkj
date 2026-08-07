package com.ai8493.llmproxy.model.extensions;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ThinkingConfigTest {

    @Test
    void shouldBuildWithAllFields() {
        ThinkingConfig config = ThinkingConfig.builder()
            .type("enabled")
            .budgetTokens(10000)
            .build();
        assertThat(config.type()).isEqualTo("enabled");
        assertThat(config.budgetTokens()).isEqualTo(10000);
    }

    @Test
    void shouldBuildWithDefaults() {
        ThinkingConfig config = ThinkingConfig.builder().build();
        assertThat(config.type()).isNull();
        assertThat(config.budgetTokens()).isNull();
    }
}
