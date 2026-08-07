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

    // ===== P3-14: custom 工具元数据保留 =====

    @Test
    void shouldEmbedOriginalDefinitionInCustomProxyDescription() {
        // P3-14: custom 工具代理的 description 应包含原始工具定义 JSON
        List<UnifiedTool> result = CodexToolCompat.expandCustom(
            "my_custom_tool", "A tool that does something special");

        assertThat(result).hasSize(1);
        String desc = result.get(0).function().description();
        // 应包含原始 name
        assertThat(desc).contains("my_custom_tool");
        // 应包含原始 description
        assertThat(desc).contains("A tool that does something special");
        // 应包含 "Original tool definition" 标记
        assertThat(desc).contains("Original tool definition");
        // 应包含 JSON 代码块
        assertThat(desc).contains("```json");
    }

    @Test
    void shouldPreserveFreeformNoteInCustomProxyDescription() {
        // P3-14: 保留原有 FREEFORM 提示
        List<UnifiedTool> result = CodexToolCompat.expandCustom(
            "my_tool", "description here");

        String desc = result.get(0).function().description();
        assertThat(desc).contains("FREEFORM");
    }

    @Test
    void shouldHandleNullDescriptionWhenEmbeddingOriginal() {
        // P3-14: 原始 description 为 null 时仍能正常嵌入
        List<UnifiedTool> result = CodexToolCompat.expandCustom("my_tool", null);

        String desc = result.get(0).function().description();
        assertThat(desc).contains("my_tool");
        assertThat(desc).contains("```json");
    }

    @Test
    void shouldFlattenNamespaceTools() {
        List<UnifiedTool> children = List.of(
            UnifiedTool.builder()
                .type("function")
                .function(UnifiedFunctionDefinition.builder()
                    .name("read_file")
                    .description("Read a file")
                    .parameters(emptyParams())
                    .build())
                .build(),
            UnifiedTool.builder()
                .type("function")
                .function(UnifiedFunctionDefinition.builder()
                    .name("write_file")
                    .description("Write a file")
                    .parameters(emptyParams())
                    .build())
                .build()
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
            UnifiedTool.builder()
                .type("function")
                .function(UnifiedFunctionDefinition.builder()
                    .name("search_files")
                    .description("Search files")
                    .parameters(emptyParams())
                    .build())
                .build()
        );

        List<UnifiedTool> result = CodexToolCompat.expandNamespace(
            "mcp__filesystem__", null, children, null);

        assertThat(result.get(0).function().name()).isEqualTo("mcp_filesystem_search_files");
    }

    @Test
    void shouldSkipNonFunctionChildrenInNamespace() {
        List<UnifiedTool> children = List.of(
            UnifiedTool.builder().type("custom").build(),
            UnifiedTool.builder()
                .type("function")
                .function(UnifiedFunctionDefinition.builder()
                    .name("valid")
                    .parameters(emptyParams())
                    .build())
                .build()
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
        children.add(UnifiedTool.builder()
            .type("function")
            .function(UnifiedFunctionDefinition.builder()
                .name("ok")
                .parameters(emptyParams())
                .build())
            .build());

        List<UnifiedTool> result = CodexToolCompat.expandNamespace("ns", null, children, null);
        assertThat(result).hasSize(1);
        assertThat(result.get(0).function().name()).isEqualTo("ns_ok");
    }

    @Test
    void shouldFallbackToEmptyParamsWhenNull() {
        List<UnifiedTool> children = List.of(
            UnifiedTool.builder()
                .type("function")
                .function(UnifiedFunctionDefinition.builder()
                    .name("no_params")
                    .build())
                .build()
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

        var children = List.of(UnifiedTool.builder()
            .type("function")
            .function(UnifiedFunctionDefinition.builder()
                .name("read_file")
                .description("Reads a file")
                .parameters(fileParams)
                .build())
            .build());

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

    // ===== P3-13: namespace 工具名称长度限制 =====

    @Test
    void shouldTruncateNamespaceFlatNameWhenExceeding64Chars() {
        // 超长 namespace + 超长 name -> flatName 必然 > 64,需要截断
        String longNs = "mcp__very_long_namespace_name_that_exceeds_the_limit__";
        String longName = "do_something_with_a_very_long_action_name";
        List<UnifiedTool> children = List.of(
            UnifiedTool.builder()
                .type("function")
                .function(UnifiedFunctionDefinition.builder()
                    .name(longName)
                    .description("test")
                    .parameters(emptyParams())
                    .build())
                .build()
        );

        List<UnifiedTool> result = CodexToolCompat.expandNamespace(longNs, null, children, null);

        assertThat(result).hasSize(1);
        String flatName = result.get(0).function().name();
        assertThat(flatName.length()).isLessThanOrEqualTo(64);
        // 截断后仍以 cleanNs 前缀开头
        String cleanNs = longNs.replace("__", "_").replaceAll("_+$", "");
        assertThat(flatName).startsWith(cleanNs + "_");
    }

    @Test
    void shouldNotTruncateWhenFlatNameWithin64Chars() {
        // 正常长度不截断
        List<UnifiedTool> children = List.of(
            UnifiedTool.builder()
                .type("function")
                .function(UnifiedFunctionDefinition.builder()
                    .name("read_file")
                    .description("Read a file")
                    .parameters(emptyParams())
                    .build())
                .build()
        );

        List<UnifiedTool> result = CodexToolCompat.expandNamespace(
            "mcp__filesystem__", null, children, null);

        assertThat(result.get(0).function().name()).isEqualTo("mcp_filesystem_read_file");
        assertThat(result.get(0).function().name().length()).isLessThanOrEqualTo(64);
    }

    @Test
    void shouldProduceStableTruncatedNameForSameInput() {
        // 相同输入应产生相同截断名(sha256 确定性)
        String longNs = "mcp__very_long_namespace_name_that_exceeds_the_limit__";
        String longName = "do_something_with_a_very_long_action_name";
        List<UnifiedTool> children = List.of(
            UnifiedTool.builder()
                .type("function")
                .function(UnifiedFunctionDefinition.builder()
                    .name(longName)
                    .description("test")
                    .parameters(emptyParams())
                    .build())
                .build()
        );

        List<UnifiedTool> r1 = CodexToolCompat.expandNamespace(longNs, null, children, null);
        List<UnifiedTool> r2 = CodexToolCompat.expandNamespace(longNs, null, children, null);

        assertThat(r1.get(0).function().name()).isEqualTo(r2.get(0).function().name());
    }

    @Test
    void shouldProduceDifferentTruncatedNamesForDifferentInputs() {
        // 不同输入应产生不同截断名(sha256 抗碰撞)
        String longNs = "mcp__very_long_namespace_name_that_exceeds_the_limit__";
        List<UnifiedTool> children1 = List.of(
            UnifiedTool.builder()
                .type("function")
                .function(UnifiedFunctionDefinition.builder()
                    .name("do_something_with_a_very_long_action_name_one")
                    .description("test")
                    .parameters(emptyParams())
                    .build())
                .build()
        );
        List<UnifiedTool> children2 = List.of(
            UnifiedTool.builder()
                .type("function")
                .function(UnifiedFunctionDefinition.builder()
                    .name("do_something_with_a_very_long_action_name_two")
                    .description("test")
                    .parameters(emptyParams())
                    .build())
                .build()
        );

        List<UnifiedTool> r1 = CodexToolCompat.expandNamespace(longNs, null, children1, null);
        List<UnifiedTool> r2 = CodexToolCompat.expandNamespace(longNs, null, children2, null);

        assertThat(r1.get(0).function().name()).isNotEqualTo(r2.get(0).function().name());
    }

    @Test
    void shouldExposeComputeFlatNameHelperForCallerSync() {
        // ResponsesProtocolAdapter 调用方需要用相同计算来记录映射,暴露公共 helper
        String cleanNs = "mcp_filesystem";
        String originalName = "read_file";
        String flatName = CodexToolCompat.computeFlatName(cleanNs, originalName);

        assertThat(flatName).isEqualTo("mcp_filesystem_read_file");
    }

    @Test
    void shouldTruncateViaComputeFlatNameHelper() {
        // cleanNs 本身已超 64 字符,必须同时截断 cleanNs 和 name
        String cleanNs = "mcp_very_long_namespace_that_will_exceed_sixty_four_chars_limit_here";
        String originalName = "do_something_very_long_action_name_here";
        String flatName = CodexToolCompat.computeFlatName(cleanNs, originalName);

        assertThat(flatName.length()).isLessThanOrEqualTo(64);
        // 仍以 _ 分隔,且末段为 8 位 hash
        String[] parts = flatName.split("_");
        assertThat(parts[parts.length - 1].length()).isEqualTo(8);
    }
}
