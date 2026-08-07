package com.ai8493.llmproxy.model;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class UnifiedToolChoiceTest {

    @Test
    void shouldCreateAny() {
        UnifiedToolChoice.Any any = UnifiedToolChoice.Any.builder().build();
        assertThat(any).isInstanceOf(UnifiedToolChoice.class);
    }

    @Test
    void shouldPreserveExistingTypes() {
        UnifiedToolChoice.None none = UnifiedToolChoice.None.builder().build();
        UnifiedToolChoice.Auto auto = UnifiedToolChoice.Auto.builder().build();
        UnifiedToolChoice.Required required = UnifiedToolChoice.Required.builder()
            .functionName("get_weather")
            .build();
        assertThat(none).isInstanceOf(UnifiedToolChoice.class);
        assertThat(auto).isInstanceOf(UnifiedToolChoice.class);
        assertThat(required.functionName()).isEqualTo("get_weather");
    }

    @Test
    void shouldBeExhaustiveInSwitch() {
        UnifiedToolChoice tc = UnifiedToolChoice.Any.builder().build();
        String result = switch (tc) {
            case UnifiedToolChoice.None n -> "none";
            case UnifiedToolChoice.Auto a -> "auto";
            case UnifiedToolChoice.Required r -> "required";
            case UnifiedToolChoice.Any a -> "any";
        };
        assertThat(result).isEqualTo("any");
    }
}
