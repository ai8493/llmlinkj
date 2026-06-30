package com.ai8493.llmproxy.adapter.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ai8493.llmproxy.model.UnifiedFunctionDefinition;
import com.ai8493.llmproxy.model.UnifiedTool;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class CodexToolCompat {

    private static final ObjectMapper mapper = new ObjectMapper();

    /**
     * 将 apply_patch custom 工具拆解为 5 个 function 代理工具
     */
    public static List<UnifiedTool> expandApplyPatch(String name, String description) {
        String desc = description != null && !description.isEmpty() ? description : null;
        return List.of(
                buildTool(name + "_add_file", proxyDesc(desc, "add_file", "Create one new file."),
                        addFileSchema()),
                buildTool(name + "_delete_file", proxyDesc(desc, "delete_file", "Delete one file."),
                        deleteFileSchema()),
                buildTool(name + "_update_file",
                        proxyDesc(desc, "update_file", "Edit one existing file with structured hunks."),
                        updateFileSchema()),
                buildTool(name + "_replace_file", proxyDesc(desc, "replace_file", "Replace one existing file."),
                        replaceFileSchema()),
                buildTool(name + "_batch",
                        proxyDesc(desc, "batch", "Edit files by providing structured JSON patch operations."),
                        batchSchema())
        );
    }

    /**
     * 通用 custom 工具代理（非 apply_patch）
     */
    public static List<UnifiedTool> expandCustom(String name, String description) {
        return List.of(buildTool(name, freestyleDesc(description), genericProxySchema()));
    }

    private static JsonNode addFileSchema() {
        ObjectNode params = mapper.createObjectNode();
        params.put("type", "object");
        params.put("additionalProperties", false);
        ObjectNode props = mapper.createObjectNode();
        props.set("path", stringProp("Target file path."));
        props.set("content", stringProp("Full file content."));
        params.set("properties", props);
        ArrayNode required = mapper.createArrayNode();
        required.add("path").add("content");
        params.set("required", required);
        return params;
    }

    private static JsonNode deleteFileSchema() {
        ObjectNode params = mapper.createObjectNode();
        params.put("type", "object");
        params.put("additionalProperties", false);
        ObjectNode props = mapper.createObjectNode();
        props.set("path", stringProp("Target file path."));
        params.set("properties", props);
        ArrayNode required = mapper.createArrayNode();
        required.add("path");
        params.set("required", required);
        return params;
    }

    private static JsonNode updateFileSchema() {
        ObjectNode params = mapper.createObjectNode();
        params.put("type", "object");
        params.put("additionalProperties", false);
        ObjectNode props = mapper.createObjectNode();
        props.set("path", stringProp("Target file path."));
        props.set("move_to", stringProp("Optional destination path for move operations."));

        ObjectNode lineItem = mapper.createObjectNode();
        lineItem.put("type", "object");
        lineItem.put("additionalProperties", false);
        ObjectNode lineProps = mapper.createObjectNode();
        ArrayNode opEnum = mapper.createArrayNode();
        opEnum.add("context").add("add").add("remove");
        lineProps.set("op", enumStringProp(opEnum));
        lineProps.set("text", stringProp(""));
        lineItem.set("properties", lineProps);
        ArrayNode lineRequired = mapper.createArrayNode();
        lineRequired.add("op").add("text");
        lineItem.set("required", lineRequired);

        ObjectNode hunksItem = mapper.createObjectNode();
        hunksItem.put("type", "object");
        hunksItem.put("additionalProperties", false);
        ObjectNode hunksProps = mapper.createObjectNode();
        hunksProps.set("context", stringProp("Optional @@ context header text."));
        ObjectNode linesArray = mapper.createObjectNode();
        linesArray.put("type", "array");
        linesArray.set("items", lineItem);
        hunksProps.set("lines", linesArray);
        hunksItem.set("properties", hunksProps);
        ArrayNode hunksRequired = mapper.createArrayNode();
        hunksRequired.add("lines");
        hunksItem.set("required", hunksRequired);

        ObjectNode hunks = mapper.createObjectNode();
        hunks.put("type", "array");
        hunks.put("description", "Structured update hunks.");
        hunks.set("items", hunksItem);
        props.set("hunks", hunks);

        params.set("properties", props);
        ArrayNode required = mapper.createArrayNode();
        required.add("path").add("hunks");
        params.set("required", required);
        return params;
    }

    private static JsonNode replaceFileSchema() {
        ObjectNode params = mapper.createObjectNode();
        params.put("type", "object");
        params.put("additionalProperties", false);
        ObjectNode props = mapper.createObjectNode();
        props.set("path", stringProp("Target file path."));
        props.set("content", stringProp("Full replacement content."));
        params.set("properties", props);
        ArrayNode required = mapper.createArrayNode();
        required.add("path").add("content");
        params.set("required", required);
        return params;
    }

    private static JsonNode batchSchema() {
        ObjectNode params = mapper.createObjectNode();
        params.put("type", "object");
        params.put("additionalProperties", false);

        ObjectNode opItem = mapper.createObjectNode();
        opItem.put("type", "object");
        opItem.put("additionalProperties", false);
        ObjectNode opProps = mapper.createObjectNode();
        ArrayNode typeEnum = mapper.createArrayNode();
        typeEnum.add("add_file").add("delete_file").add("update_file").add("replace_file");
        opProps.set("type", enumStringProp(typeEnum));
        opProps.set("path", stringProp(""));
        opProps.set("move_to", stringProp(""));
        opProps.set("content", stringProp(""));
        opProps.set("hunks", arrayProp(""));
        opItem.set("properties", opProps);
        ArrayNode opRequired = mapper.createArrayNode();
        opRequired.add("type").add("path");
        opItem.set("required", opRequired);

        ObjectNode operations = mapper.createObjectNode();
        operations.put("type", "array");
        operations.set("items", opItem);
        ObjectNode props = mapper.createObjectNode();
        props.set("operations", operations);
        params.set("properties", props);
        ArrayNode required = mapper.createArrayNode();
        required.add("operations");
        params.set("required", required);
        return params;
    }

    private static JsonNode genericProxySchema() {
        ObjectNode params = mapper.createObjectNode();
        params.put("type", "object");
        params.put("additionalProperties", false);
        ObjectNode props = mapper.createObjectNode();
        props.set("input", stringProp("Raw freeform input for this custom tool."));
        params.set("properties", props);
        ArrayNode required = mapper.createArrayNode();
        required.add("input");
        params.set("required", required);
        return params;
    }

    private static UnifiedTool buildTool(String name, String description, JsonNode params) {
        return new UnifiedTool("function", new UnifiedFunctionDefinition(name, description, params));
    }

    private static String proxyDesc(String desc, String action, String defaultDesc) {
        if (desc != null) return desc + " (proxy action: " + action + ")";
        return defaultDesc;
    }

    private static String freestyleDesc(String description) {
        if (description != null && !description.isEmpty())
            return description + "\n\nThis is a FREEFORM tool. Do not wrap the input in JSON or markdown.";
        return "FREEFORM custom tool. Put only the tool input text here.";
    }

    private static ObjectNode stringProp(String description) {
        ObjectNode prop = mapper.createObjectNode();
        prop.put("type", "string");
        prop.put("description", description);
        return prop;
    }

    private static ObjectNode enumStringProp(ArrayNode enumValues) {
        ObjectNode prop = mapper.createObjectNode();
        prop.put("type", "string");
        prop.set("enum", enumValues);
        return prop;
    }

    private static ObjectNode arrayProp(String description) {
        ObjectNode prop = mapper.createObjectNode();
        prop.put("type", "array");
        prop.put("description", description);
        return prop;
    }

    /**
     * 将 namespace 工具展平为独立 function 工具列表
     */
    public static List<UnifiedTool> expandNamespace(String namespace, String namespaceDesc,
                                                    List<UnifiedTool> children, String alias) {
        List<UnifiedTool> result = new ArrayList<>();
        if (children == null) return result;
        String cleanNs = namespace.replace("__", "_").replaceAll("_+$", "");
        String prefix = cleanNs + "_";

        for (UnifiedTool child : children) {
            if (child == null) continue;
            if (!"function".equals(child.type()) || child.function() == null) continue;
            UnifiedFunctionDefinition fn = child.function();
            if (fn.name() == null) continue;
            String flatName = prefix + fn.name();
            String desc = combineDesc(namespaceDesc, fn.description());
            JsonNode params = fn.parameters();
            if (params == null) params = emptyParams();
            JsonNode paramsWithNs = (alias != null && !alias.isEmpty())
                ? injectCustomNs(params.deepCopy(), alias)
                : params;

            result.add(new UnifiedTool("function",
                    new UnifiedFunctionDefinition(flatName, desc, paramsWithNs)));
        }
        return result;
    }

    private static String combineDesc(String nsDesc, String childDesc) {
        if (nsDesc == null || nsDesc.isEmpty()) return childDesc;
        if (childDesc == null || childDesc.isEmpty()) return nsDesc;
        // return nsDesc + "\n\n" + childDesc;
        return childDesc;
    }

    private static ObjectNode emptyParams() {
        ObjectNode params = mapper.createObjectNode();
        params.put("type", "object");
        return params;
    }

    private static JsonNode injectCustomNs(JsonNode params, String alias) {
        ObjectNode obj = (params instanceof ObjectNode) ? (ObjectNode) params : emptyParams();
        if (!(params instanceof ObjectNode)) {
            obj = (ObjectNode) obj.deepCopy();
        }

        JsonNode propsNode = obj.get("properties");
        ObjectNode props;
        if (propsNode instanceof ObjectNode) {
            props = (ObjectNode) propsNode;
        } else {
            props = mapper.createObjectNode();
            obj.set("properties", props);
        }

        ObjectNode nsProp = mapper.createObjectNode();
        nsProp.put("type", "string");
        nsProp.put("const", alias);
        // ArrayNode enumArr = mapper.createArrayNode();
        // enumArr.add(alias);
        // nsProp.set("enum", enumArr);
        nsProp.put("description", "Always use '" + alias + "' as the value.");
        props.set(ProxyConstants.MCP_SERVER_ROUTER_PARAM, nsProp);

        JsonNode reqNode = obj.get("required");
        ArrayNode required;
        if (reqNode instanceof ArrayNode) {
            required = (ArrayNode) reqNode;
        } else {
            required = mapper.createArrayNode();
            obj.set("required", required);
        }
        boolean hasNs = false;
        for (JsonNode r : required) {
            if (ProxyConstants.MCP_SERVER_ROUTER_PARAM.equals(r.asText())) { hasNs = true; break; }
        }
        if (!hasNs) {
            required.add(ProxyConstants.MCP_SERVER_ROUTER_PARAM);
        }

        return obj;
    }

    public static List<UnifiedTool> applyPatchTool() {
        var name = "apply_patch";
        var description = readResource("apply_patch_defination.md");

        ObjectNode params = mapper.createObjectNode();
        params.put("type", "object");
        params.put("additionalProperties", false);
        ObjectNode props = mapper.createObjectNode();
        props.set("input", stringProp(readResource("apply_patch_param_input.md")));
        params.set("properties", props);
        ArrayNode required = mapper.createArrayNode();
        required.add("input");
        params.set("required", required);

        return List.of(new UnifiedTool("function", new UnifiedFunctionDefinition(name, description, params)));
    }

    private static String readResource(String path) {
        try (InputStream in = CodexToolCompat.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                throw new RuntimeException("未找到 classpath 资源: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("读取 classpath 资源失败: " + path, e);
        }
    }
}
