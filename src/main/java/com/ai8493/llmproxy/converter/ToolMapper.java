package com.ai8493.llmproxy.converter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.genai.types.*;
import com.ai8493.llmproxy.model.*;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

public class ToolMapper {

    private static final ObjectMapper mapper = new ObjectMapper();

    /** IR UnifiedTool list → Gemini Tool list */
    public List<com.google.genai.types.Tool> mapTools(List<UnifiedTool> irTools) {
        if (irTools == null || irTools.isEmpty()) return List.of();
        List<FunctionDeclaration> declarations = irTools.stream()
            .filter(t -> "function".equals(t.type()))
            .map(this::mapFunctionDeclaration)
            .toList();
        return List.of(com.google.genai.types.Tool.builder()
            .functionDeclarations(declarations)
            .build());
    }

    private FunctionDeclaration mapFunctionDeclaration(UnifiedTool tool) {
        return FunctionDeclaration.builder()
            .name(tool.function().name())
            .description(tool.function().description())
            .parameters(mapJsonSchema(tool.function().parameters()))
            .build();
    }

    private Schema mapJsonSchema(JsonNode params) {
        Schema.Builder builder = Schema.builder().type(Type.Known.OBJECT);
        if (params != null) {
            if (params.has("required")) {
                builder.required(toList(params.get("required")));
            }
            if (params.has("properties")) {
                Map<String, Schema> props = new HashMap<>();
                params.get("properties").fields().forEachRemaining(e ->
                    props.put(e.getKey(), buildPropertySchema(e.getValue())));
                builder.properties(props);
            }
        }
        return builder.build();
    }

    private Schema buildPropertySchema(JsonNode prop) {
        Type.Known type = mapType(prop.get("type").asText());
        Schema.Builder builder = Schema.builder().type(type);
        if (prop.has("description")) builder.description(prop.get("description").asText());
        if (prop.has("enum")) builder.enum_(toList(prop.get("enum")));
        return builder.build();
    }

    private Type.Known mapType(String t) {
        return switch (t) {
            case "string" -> Type.Known.STRING;
            case "number" -> Type.Known.NUMBER;
            case "integer" -> Type.Known.INTEGER;
            case "boolean" -> Type.Known.BOOLEAN;
            case "array" -> Type.Known.ARRAY;
            case "object" -> Type.Known.OBJECT;
            default -> throw new IllegalArgumentException("不支持的类型: " + t);
        };
    }

    /** IR UnifiedToolChoice → Gemini ToolConfig */
    public com.google.genai.types.ToolConfig mapToolChoice(UnifiedToolChoice toolChoice) {
        if (toolChoice == null) return null;
        FunctionCallingConfig config;
        if (toolChoice instanceof UnifiedToolChoice.None) {
            config = FunctionCallingConfig.builder()
                .mode(FunctionCallingConfigMode.Known.NONE).build();
        } else if (toolChoice instanceof UnifiedToolChoice.Auto) {
            config = FunctionCallingConfig.builder()
                .mode(FunctionCallingConfigMode.Known.AUTO).build();
        } else if (toolChoice instanceof UnifiedToolChoice.Required r) {
            config = FunctionCallingConfig.builder()
                .mode(FunctionCallingConfigMode.Known.ANY)
                .allowedFunctionNames(List.of(r.functionName()))
                .build();
        } else {
            config = FunctionCallingConfig.builder()
                .mode(FunctionCallingConfigMode.Known.AUTO).build();
        }
        return com.google.genai.types.ToolConfig.builder()
            .functionCallingConfig(config)
            .build();
    }

    private List<String> toList(JsonNode node) {
        return StreamSupport.stream(node.spliterator(), false)
            .map(JsonNode::asText)
            .collect(Collectors.toList());
    }

    // ===== Gemini → IR 反向映射 =====

    /** Gemini Tool JSON → IR UnifiedTool list（绕过 SDK Tool 类型反序列化） */
    public List<UnifiedTool> mapToolsFromGeminiJson(JsonNode toolsNode) {
        if (toolsNode == null || !toolsNode.isArray()) return null;
        List<UnifiedTool> result = new ArrayList<>();
        for (JsonNode tool : toolsNode) {
            JsonNode decls = tool.get("functionDeclarations");
            if (decls == null || !decls.isArray()) continue;
            for (JsonNode decl : decls) {
                String name = decl.has("name") ? decl.get("name").asText() : "";
                String desc = decl.has("description") ? decl.get("description").asText() : null;
                JsonNode params = decl.has("parameters") ? decl.get("parameters")
                    : decl.has("parametersJsonSchema") ? decl.get("parametersJsonSchema") : null;
                result.add(new UnifiedTool("function",
                    new UnifiedFunctionDefinition(name, desc, params)));
            }
        }
        return result.isEmpty() ? null : result;
    }

    /** Gemini Tool list → IR UnifiedTool list */
    public List<UnifiedTool> mapToolsFromGemini(List<com.google.genai.types.Tool> geminiTools) {
        if (geminiTools == null || geminiTools.isEmpty()) return null;
        List<UnifiedTool> result = new ArrayList<>();
        for (var tool : geminiTools) {
            tool.functionDeclarations().ifPresent(decls -> {
                for (var decl : decls) {
                    JsonNode params = decl.parameters().map(this::schemaToJson).orElse(null);
                    result.add(new UnifiedTool("function",
                        new UnifiedFunctionDefinition(
                            decl.name().orElse(""),
                            decl.description().orElse(null),
                            params)));
                }
            });
        }
        return result.isEmpty() ? null : result;
    }

    /** Gemini ToolConfig → IR UnifiedToolChoice */
    public UnifiedToolChoice mapToolChoiceFromGemini(ToolConfig toolConfig) {
        if (toolConfig == null) return null;
        return toolConfig.functionCallingConfig()
            .map(fcc -> {
                var mode = fcc.mode().orElse(null);
                if (mode == null) return new UnifiedToolChoice.Auto();
                return switch (mode.toString()) {
                    case "NONE" -> new UnifiedToolChoice.None();
                    case "ANY" -> {
                        var names = fcc.allowedFunctionNames().orElse(List.of());
                        yield names.isEmpty()
                            ? new UnifiedToolChoice.Auto()
                            : new UnifiedToolChoice.Required(names.get(0));
                    }
                    default -> new UnifiedToolChoice.Auto();
                };
            })
            .orElse(null);
    }

    /** Gemini Schema → Jackson JsonNode */
    private JsonNode schemaToJson(Schema schema) {
        ObjectNode node = mapper.createObjectNode();
        schema.type().ifPresent(t -> node.put("type", t.toString().toLowerCase()));
        schema.description().ifPresent(d -> node.put("description", d));
        schema.required().ifPresent(r -> {
            ArrayNode arr = mapper.createArrayNode();
            r.forEach(arr::add);
            node.set("required", arr);
        });
        schema.properties().ifPresent(props -> {
            ObjectNode propNode = mapper.createObjectNode();
            props.forEach((key, value) -> propNode.set(key, schemaToJson(value)));
            node.set("properties", propNode);
        });
        schema.enum_().ifPresent(e -> {
            ArrayNode arr = mapper.createArrayNode();
            e.forEach(arr::add);
            node.set("enum", arr);
        });
        return node;
    }
}
