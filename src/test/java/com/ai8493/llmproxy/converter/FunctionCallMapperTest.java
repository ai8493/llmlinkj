package com.ai8493.llmproxy.converter;

import com.google.genai.types.*;
import com.ai8493.llmproxy.model.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FunctionCallMapperTest {

    private final FunctionCallMapper mapper = new FunctionCallMapper();

    @Test
    void shouldMapFunctionCallToUnifiedToolCall() {
        var fc = FunctionCall.builder()
            .id("call_123")
            .name("get_weather")
            .args(Map.of("location", "Beijing"))
            .build();
        var part = Part.builder()
            .functionCall(fc)
            .build();

        var result = mapper.mapToolCalls(List.of(part));

        assertThat(result).hasSize(1);
        var call = result.get(0);
        assertThat(call.id()).isEqualTo("call_123");
        assertThat(call.type()).isEqualTo("function");
        assertThat(call.function().name()).isEqualTo("get_weather");
        assertThat(call.function().arguments()).isNotNull();
    }

    @Test
    void shouldMapToolMessageToFunctionResponse() {
        var toolMsg = UnifiedMessage.builder()
            .role(UnifiedMessage.Role.TOOL)
            .content("{\"temperature\": 25}")
            .toolCallId("call_456")
            .name("get_temperature")
            .build();

        var result = mapper.mapToolResults(List.of(toolMsg));

        assertThat(result).hasSize(1);
        var content = result.get(0);
        assertThat(content.role()).hasValue("user");
        assertThat(content.parts()).isPresent();
        assertThat(content.parts().get()).hasSize(1);
        var part = content.parts().get().get(0);
        assertThat(part.functionResponse()).isPresent();
        var fr = part.functionResponse().get();
        assertThat(fr.name()).isPresent();
    }

    @Test
    void shouldReturnEmptyListForNullInput() {
        var result = mapper.mapToolCalls(null);
        assertThat(result).isEmpty();
    }

    @Test
    void shouldReturnEmptyListForNullToolResults() {
        var result = mapper.mapToolResults(null);
        assertThat(result).isEmpty();
    }
}
