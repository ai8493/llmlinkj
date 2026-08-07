package com.ai8493.llmproxy.converter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ai8493.llmproxy.model.*;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ToolMapperTest {

    private final ToolMapper mapper = new ToolMapper();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldMapToolToFunctionDeclaration() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("type", "object");
        ObjectNode properties = objectMapper.createObjectNode();
        ObjectNode location = objectMapper.createObjectNode();
        location.put("type", "string");
        location.put("description", "城市名称");
        properties.set("location", location);
        params.set("properties", properties);

        var fnDef = UnifiedFunctionDefinition.builder()
            .name("get_weather")
            .description("获取天气")
            .parameters(params)
            .build();
        var irTool = UnifiedTool.builder()
            .type("function")
            .function(fnDef)
            .build();

        var result = mapper.mapTools(List.of(irTool));

        assertThat(result).hasSize(1);
        var geminiTool = result.get(0);
        assertThat(geminiTool.functionDeclarations()).isPresent();
        var decls = geminiTool.functionDeclarations().get();
        assertThat(decls).hasSize(1);
        var decl = decls.get(0);
        assertThat(decl.name()).hasValue("get_weather");
        assertThat(decl.description()).hasValue("获取天气");
        assertThat(decl.parameters()).isPresent();
    }

    @Test
    void shouldMapNoneToolChoice() {
        var result = mapper.mapToolChoice(UnifiedToolChoice.None.builder().build());

        assertThat(result).isNotNull();
        assertThat(result.functionCallingConfig()).isPresent();
        assertThat(result.functionCallingConfig().get().mode()).isNotNull();
    }

    @Test
    void shouldMapAutoToolChoice() {
        var result = mapper.mapToolChoice(UnifiedToolChoice.Auto.builder().build());

        assertThat(result).isNotNull();
        assertThat(result.functionCallingConfig()).isPresent();
        assertThat(result.functionCallingConfig().get().mode()).isNotNull();
    }

    @Test
    void shouldMapRequiredToolChoice() {
        var result = mapper.mapToolChoice(UnifiedToolChoice.Required.builder()
            .functionName("get_weather")
            .build());

        assertThat(result).isNotNull();
        assertThat(result.functionCallingConfig()).isPresent();
        var config = result.functionCallingConfig().get();
        assertThat(config.mode()).isNotNull();
        assertThat(config.allowedFunctionNames()).isPresent();
        assertThat(config.allowedFunctionNames().get()).contains("get_weather");
    }

    @Test
    void mapToolsFromGeminiJson() throws Exception {
        // 模拟 Gemini CLI 真实 tools JSON（简化版含两个工具声明）
        String json = """
            [{
              "functionDeclarations": [
                {"name": "update_topic", "description": "Manages narrative flow",
                 "parameters": {"type": "object", "properties": {"title": {"type": "string"}}, "required": ["title"]}},
                {"name": "read_file", "description": "Reads a file",
                 "parameters": {"type": "object", "properties": {"file_path": {"type": "string"}}}}
              ]
            }]""";
        JsonNode toolsNode = new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);

        List<UnifiedTool> result = new ToolMapper().mapToolsFromGeminiJson(toolsNode);

        assertThat(result).isNotNull().hasSize(2);
        assertThat(result.get(0).type()).isEqualTo("function");
        assertThat(result.get(0).function().name()).isEqualTo("update_topic");
        assertThat(result.get(0).function().parameters()).isNotNull();
        assertThat(result.get(1).function().name()).isEqualTo("read_file");
    }

    @Test
    void mapToolsFromGeminiJsonNullAndEmpty() {
        ToolMapper mapper = new ToolMapper();
        assertThat(mapper.mapToolsFromGeminiJson(null)).isNull();
        assertThat(mapper.mapToolsFromGeminiJson(
            new com.fasterxml.jackson.databind.ObjectMapper().createArrayNode())).isNull();
    }
}
