package com.ai8493.llmproxy.adapter.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ai8493.llmproxy.model.UnifiedFunctionDefinition;
import com.ai8493.llmproxy.model.UnifiedTool;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class CodexToolCompatTest {

    @Disabled("apply_patch 不再拆解为 5 个代理工具")
    @Test
    void shouldExpandApplyPatchIntoFiveProxyTools() {
        List<UnifiedTool> result = CodexToolCompat.expandApplyPatch(
            "apply_patch", "Edit files using patch format");

        assertThat(result).hasSize(5);

        UnifiedTool addFile = result.get(0);
        assertThat(addFile.function().name()).isEqualTo("apply_patch_add_file");
        assertThat(addFile.function().description()).contains("add_file");
        assertThat(addFile.function().parameters().get("required").get(0).asText()).isEqualTo("path");
        assertThat(addFile.function().parameters().get("required").get(1).asText()).isEqualTo("content");

        UnifiedTool deleteFile = result.get(1);
        assertThat(deleteFile.function().name()).isEqualTo("apply_patch_delete_file");
        assertThat(deleteFile.function().parameters().get("required")).hasSize(1);

        UnifiedTool updateFile = result.get(2);
        assertThat(updateFile.function().name()).isEqualTo("apply_patch_update_file");
        assertThat(updateFile.function().parameters().get("properties").has("hunks")).isTrue();
        assertThat(updateFile.function().parameters().get("properties").has("move_to")).isTrue();

        UnifiedTool replaceFile = result.get(3);
        assertThat(replaceFile.function().name()).isEqualTo("apply_patch_replace_file");

        UnifiedTool batch = result.get(4);
        assertThat(batch.function().name()).isEqualTo("apply_patch_batch");
        assertThat(batch.function().parameters().get("properties").has("operations")).isTrue();
    }

    @Test
    void shouldGenerateGenericProxyForCustomTool() {
        List<UnifiedTool> result = CodexToolCompat.expandCustom(
            "some_tool", "A custom tool");

        assertThat(result).hasSize(1);
        UnifiedTool proxy = result.get(0);
        assertThat(proxy.type()).isEqualTo("function");
        assertThat(proxy.function().name()).isEqualTo("some_tool");
        assertThat(proxy.function().description()).contains("FREEFORM");
        assertThat(proxy.function().parameters().get("properties").has("input")).isTrue();
        assertThat(proxy.function().parameters().get("required").get(0).asText()).isEqualTo("input");
    }

    @Disabled("apply_patch 不再拆解为 5 个代理工具")
    @Test
    void shouldHandleNullDescriptionForApplyPatch() {
        List<UnifiedTool> result = CodexToolCompat.expandApplyPatch("apply_patch", null);
        assertThat(result).hasSize(5);
        assertThat(result.get(0).function().description()).doesNotContain("null");
    }

    @Test
    void shouldHandleEmptyDescriptionForCustom() {
        List<UnifiedTool> result = CodexToolCompat.expandCustom("my_tool", null);
        assertThat(result).hasSize(1);
        assertThat(result.get(0).function().description()).contains("FREEFORM custom tool");
    }

    @Test
    void shouldFlattenNamespaceTools() {
        List<UnifiedTool> children = List.of(
            new UnifiedTool("function", new UnifiedFunctionDefinition(
                "read_file", "Read a file", emptyParams())),
            new UnifiedTool("function", new UnifiedFunctionDefinition(
                "write_file", "Write a file", emptyParams()))
        );

        List<UnifiedTool> result = CodexToolCompat.expandNamespace(
            "mcp__filesystem__", "Filesystem operations", children, null);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).function().name()).isEqualTo("mcp_filesystem_read_file");
        // 新行为：combineDesc 丢弃 nsDesc，只保留 childDesc
        assertThat(result.get(0).function().description()).isEqualTo("Read a file");
        assertThat(result.get(1).function().name()).isEqualTo("mcp_filesystem_write_file");
    }

    @Test
    void shouldFlattenNamespaceWithTrailingUnderscores() {
        List<UnifiedTool> children = List.of(
            new UnifiedTool("function", new UnifiedFunctionDefinition(
                "search_files", "Search files", emptyParams()))
        );

        List<UnifiedTool> result = CodexToolCompat.expandNamespace(
            "mcp__filesystem__", null, children, null);

        assertThat(result.get(0).function().name()).isEqualTo("mcp_filesystem_search_files");
    }

    @Test
    void shouldSkipNonFunctionChildrenInNamespace() {
        List<UnifiedTool> children = List.of(
            new UnifiedTool("custom", null),
            new UnifiedTool("function", new UnifiedFunctionDefinition("valid", null, emptyParams()))
        );

        List<UnifiedTool> result = CodexToolCompat.expandNamespace("ns", null, children, null);
        assertThat(result).hasSize(1);
        assertThat(result.get(0).function().name()).isEqualTo("ns_valid");
    }

    @Test
    void shouldHandleNullChildrenInNamespace() {
        List<UnifiedTool> result = CodexToolCompat.expandNamespace("ns", null, null, null);
        assertThat(result).isEmpty();
    }

    @Test
    void shouldHandleNullElementInChildrenList() {
        List<UnifiedTool> children = new java.util.ArrayList<>();
        children.add(null);
        children.add(new UnifiedTool("function", new UnifiedFunctionDefinition("ok", null, emptyParams())));

        List<UnifiedTool> result = CodexToolCompat.expandNamespace("ns", null, children, null);
        assertThat(result).hasSize(1);
        assertThat(result.get(0).function().name()).isEqualTo("ns_ok");
    }

    @Test
    void shouldFallbackToEmptyParamsWhenNull() {
        List<UnifiedTool> children = List.of(
            new UnifiedTool("function", new UnifiedFunctionDefinition("no_params", null, null))
        );

        List<UnifiedTool> result = CodexToolCompat.expandNamespace("ns", null, children, null);
        assertThat(result).hasSize(1);
        assertThat(result.get(0).function().parameters().get("type").asText()).isEqualTo("object");
    }

    @Test
    void shouldRecordCustomProxyMapping() {
        ToolRemapContext ctx = new ToolRemapContext();
        ctx.putCustom("apply_patch", "apply_patch", ToolRemapContext.Kind.APPLY_PATCH);
        ctx.putCustom("my_tool", "my_tool", ToolRemapContext.Kind.RAW);

        assertThat(ctx.isEmpty()).isFalse();
        assertThat(ctx.isCustomProxy("apply_patch")).isTrue();
        assertThat(ctx.isCustomProxy("my_tool")).isTrue();
        assertThat(ctx.isCustomProxy("unknown")).isFalse();

        var spec = ctx.getCustomSpec("apply_patch");
        assertThat(spec.originalName()).isEqualTo("apply_patch");
        assertThat(spec.kind()).isEqualTo(ToolRemapContext.Kind.APPLY_PATCH);
    }

    @Test
    void shouldRecordNamespaceMapping() {
        ToolRemapContext ctx = new ToolRemapContext();
        ctx.putNamespace("mcp_filesystem_read", "read", "mcp__filesystem__", "ns0");
        ctx.putNamespace("mcp_filesystem_write", "write", "mcp__filesystem__", "ns0");

        assertThat(ctx.isEmpty()).isFalse();
        assertThat(ctx.isCustomProxy("mcp_filesystem_read")).isFalse();

        var spec = ctx.getNamespaceSpec("mcp_filesystem_read");
        assertThat(spec.originalName()).isEqualTo("read");
        assertThat(spec.namespace()).isEqualTo("mcp__filesystem__");

        assertThat(ctx.getNamespaceSpec("unknown")).isNull();

        assertThat(ctx.getNamespaceByAlias("ns0")).isEqualTo("mcp__filesystem__");
        assertThat(ctx.generateAlias("mcp__filesystem__")).isEqualTo("ns0"); // 复用
        assertThat(ctx.generateAlias("other_ns")).isEqualTo("ns1");          // 新分配
    }

    @Test
    void shouldReturnEmptyForNoMappings() {
        ToolRemapContext ctx = new ToolRemapContext();
        assertThat(ctx.isEmpty()).isTrue();
        assertThat(ctx.isCustomProxy("anything")).isFalse();
        assertThat(ctx.getCustomSpec("anything")).isNull();
        assertThat(ctx.getNamespaceSpec("anything")).isNull();
    }

    @Test
    void shouldInjectCustomNsIntoParams() {
        var mapper = new ObjectMapper();
        ObjectNode fileParams = mapper.createObjectNode();
        fileParams.put("type", "object");
        ObjectNode props = mapper.createObjectNode();
        props.set("path", mapper.createObjectNode().put("type", "string"));
        fileParams.set("properties", props);
        ArrayNode reqArr = mapper.createArrayNode();
        reqArr.add("path");
        fileParams.set("required", reqArr);

        var children = List.of(new UnifiedTool("function",
            new UnifiedFunctionDefinition("read_file", "Reads a file", fileParams)));

        var tools = CodexToolCompat.expandNamespace(
            "mcp__filesystem__", "Filesystem ops", children, "ns0");

        var params = tools.get(0).function().parameters();
        assertThat(params.get("properties").get(ProxyConstants.MCP_SERVER_ROUTER_PARAM).get("type").asText()).isEqualTo("string");
        assertThat(params.get("properties").get(ProxyConstants.MCP_SERVER_ROUTER_PARAM).get("const").asText()).isEqualTo("ns0");
        assertThat(params.get("required").toString()).contains(ProxyConstants.MCP_SERVER_ROUTER_PARAM);
        assertThat(params.get("required").toString()).contains("path");
    }

    private JsonNode emptyParams() {
        ObjectNode params = new ObjectMapper().createObjectNode();
        params.put("type", "object");
        return params;
    }

    @Test
    void shouldLoadDescriptionFromClasspathResource() {
        var result = CodexToolCompat.applyPatchTool();
        assertThat(result).hasSize(1);

        var tool = result.get(0);
        assertThat(tool.type()).isEqualTo("function");
        assertThat(tool.function().name()).isEqualTo("apply_patch");

        var desc = tool.function().description();
        assertThat(desc).isNotNull().isNotEmpty();
        // 验证内容来自 apply_patch_defination.md 而非硬编码
        assertThat(desc).contains("You are an expert code editor");
        assertThat(desc).contains("*** Begin Patch");
        assertThat(desc).contains("*** End Patch");
        assertThat(desc).contains("RULE 1: NO STANDARD GIT DIFF SYNTAX!");
        assertThat(desc).contains("RULE 6: BIG REWRITES = DELETE + ADD");
    }

    @Test
    void shouldLoadInputSchemaFromClasspathResource() {
        var result = CodexToolCompat.applyPatchTool();
        var params = result.get(0).function().parameters();

        assertThat(params.get("type").asText()).isEqualTo("object");
        assertThat(params.get("additionalProperties").asBoolean()).isFalse();

        var required = params.get("required");
        assertThat(required).hasSize(1);
        assertThat(required.get(0).asText()).isEqualTo("input");

        var inputProp = params.get("properties").get("input");
        assertThat(inputProp.get("type").asText()).isEqualTo("string");

        var inputDesc = inputProp.get("description").asText();
        assertThat(inputDesc).isNotNull().isNotEmpty();
        // 验证内容来自 apply_patch_param_input.md 而非硬编码
        assertThat(inputDesc).contains("Input Parameter Schema");
        assertThat(inputDesc).contains("Top-Level Envelope");
        assertThat(inputDesc).contains("Strict Line Prefix Rules");
        assertThat(inputDesc).contains("Context and Hunk Headers");
    }
}
