package com.ai8493.llmproxy.model;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class UnifiedUsageTest {

    @Test
    void shouldBuildWithCacheCreationTokens() {
        UnifiedUsage usage = UnifiedUsage.builder()
            .promptTokens(700)
            .completionTokens(50)
            .cachedTokens(300)
            .cacheCreationTokens(100)
            .reasoningTokens(20)
            .build();
        assertThat(usage.cacheCreationTokens()).isEqualTo(100);
        assertThat(usage.cachedTokens()).isEqualTo(300);
        assertThat(usage.promptTokens()).isEqualTo(700);
    }

    @Test
    void shouldDefaultCacheCreationTokensToZero() {
        UnifiedUsage usage = UnifiedUsage.builder()
            .promptTokens(100)
            .completionTokens(50)
            .build();
        assertThat(usage.cacheCreationTokens()).isEqualTo(0);
    }

    @Test
    void shouldPreserveExistingFields() {
        UnifiedUsage usage = UnifiedUsage.builder()
            .promptTokens(100)
            .completionTokens(50)
            .totalTokens(150)
            .cachedTokens(30)
            .reasoningTokens(10)
            .build();
        assertThat(usage.totalTokens()).isEqualTo(150);
        assertThat(usage.cachedTokens()).isEqualTo(30);
        assertThat(usage.reasoningTokens()).isEqualTo(10);
    }
}
