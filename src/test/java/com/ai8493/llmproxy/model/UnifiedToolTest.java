package com.ai8493.llmproxy.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class UnifiedToolTest {

    @Test
    void shouldBuildWithFunctionType() {
        UnifiedTool tool = UnifiedTool.builder()
            .type("function")
            .function(UnifiedFunctionDefinition.builder()
                .name("get_weather")
                .description("Get weather")
                .build())
            .build();
        assertThat(tool.type()).isEqualTo("function");
        assertThat(tool.function().name()).isEqualTo("get_weather");
        assertThat(tool.rawTool()).isNull();
    }

    @Test
    void shouldBuildWithRawToolForBuiltinTools() throws Exception {
        JsonNode rawTool = new ObjectMapper().readTree("{\"type\":\"computer_20241022\",\"name\":\"computer\",\"display_width_px\":1024}");
        UnifiedTool tool = UnifiedTool.builder()
            .type("computer_20241022")
            .rawTool(rawTool)
            .build();
        assertThat(tool.type()).isEqualTo("computer_20241022");
        assertThat(tool.rawTool()).isEqualTo(rawTool);
    }

    @Test
    void shouldDefaultRawToolToNull() {
        UnifiedTool tool = UnifiedTool.builder().type("function").build();
        assertThat(tool.rawTool()).isNull();
    }
}
