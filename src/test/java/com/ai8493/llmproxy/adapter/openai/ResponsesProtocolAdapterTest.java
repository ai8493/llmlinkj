package com.ai8493.llmproxy.adapter.openai;

import com.ai8493.llmproxy.model.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

class ResponsesProtocolAdapterTest {

    private final ResponsesProtocolAdapter adapter = new ResponsesProtocolAdapter();

    @Test
    void shouldParseStringInput() {
        String json = """
            {"model":"gpt-4o","input":"Hello"}""";

        UnifiedChatRequest req = adapter.toUnifiedRequest(
            json.getBytes(StandardCharsets.UTF_8), null);

        assertThat(req.model()).isEqualTo("gpt-4o");
        assertThat(req.messages()).hasSize(1);
        assertThat(req.messages().get(0).role()).isEqualTo(UnifiedMessage.Role.USER);
        assertThat(req.messages().get(0).content()).isEqualTo("Hello");
        assertThat(req.stream()).isFalse();
    }

    @Test
    void shouldParseArrayInput() {
        String json = """
            {"model":"gpt-4o","input":[{"role":"user","content":"Hello"},{"role":"assistant","content":"Hi"}]}""";

        UnifiedChatRequest req = adapter.toUnifiedRequest(
            json.getBytes(StandardCharsets.UTF_8), null);

        assertThat(req.messages()).hasSize(2);
        assertThat(req.messages().get(0).role()).isEqualTo(UnifiedMessage.Role.USER);
        assertThat(req.messages().get(0).content()).isEqualTo("Hello");
        assertThat(req.messages().get(1).role()).isEqualTo(UnifiedMessage.Role.ASSISTANT);
        assertThat(req.messages().get(1).content()).isEqualTo("Hi");
    }

    @Test
    void shouldMapInstructionsToSystemMessage() {
        String json = """
            {"model":"gpt-4o","instructions":"You are helpful.","input":"Hi"}""";

        UnifiedChatRequest req = adapter.toUnifiedRequest(
            json.getBytes(StandardCharsets.UTF_8), null);

        assertThat(req.messages()).hasSize(2);
        assertThat(req.messages().get(0).role()).isEqualTo(UnifiedMessage.Role.SYSTEM);
        assertThat(req.messages().get(0).content()).isEqualTo("You are helpful.");
        assertThat(req.messages().get(1).role()).isEqualTo(UnifiedMessage.Role.USER);
    }

    @Test
    void shouldParseGenerationConfig() {
        String json = """
            {"model":"gpt-4o","input":"Hi","temperature":0.7,"top_p":0.9,"max_output_tokens":1024,"stop":["END"]}""";

        UnifiedChatRequest req = adapter.toUnifiedRequest(
            json.getBytes(StandardCharsets.UTF_8), null);

        assertThat(req.config().temperature()).isEqualTo(0.7);
        assertThat(req.config().topP()).isEqualTo(0.9);
        assertThat(req.config().maxOutputTokens()).isEqualTo(1024);
        assertThat(req.config().stopSequences()).containsExactly("END");
    }

    @Test
    void shouldMapFunctionTools() {
        String json = """
            {"model":"gpt-4o","input":"Hi","tools":[{"type":"function","name":"get_weather","description":"Get weather","parameters":{"type":"object","properties":{"city":{"type":"string"}}}}]}""";

        UnifiedChatRequest req = adapter.toUnifiedRequest(
            json.getBytes(StandardCharsets.UTF_8), null);

        assertThat(req.tools()).hasSize(1);
        assertThat(req.tools().get(0).function().name()).isEqualTo("get_weather");
        assertThat(req.tools().get(0).function().description()).isEqualTo("Get weather");
    }

    @Test
    void shouldMapBuiltinToolsToFunctionCall() {
        String json = """
            {"model":"gpt-4o","input":"Hi","tools":[{"type":"web_search"}]}""";

        UnifiedChatRequest req = adapter.toUnifiedRequest(
            json.getBytes(StandardCharsets.UTF_8), null);

        // 内置工具（web_search 等）无 function name，跳过
        assertThat(req.tools()).isNull();
    }

    @Test
    void shouldRejectStoreTrue() {
        String json = """
            {"model":"gpt-4o","input":"Hi","store":true}""";

        assertThatThrownBy(() -> adapter.toUnifiedRequest(
            json.getBytes(StandardCharsets.UTF_8), null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("store=true");
    }

    @Test
    void shouldConvertPlainTextToResponse() {
        UnifiedChatResponse uResp = new UnifiedChatResponse(
            "resp_abc", "gpt-4o", null, 1715000000L,
            List.of(new UnifiedChoice(0,
                new UnifiedMessage(UnifiedMessage.Role.ASSISTANT, "Hello!", null, null, null, null, null),
                null, "stop", null)),
            new UnifiedUsage(10, 5, 15, 0, 0),
            null);

        byte[] raw = adapter.fromUnifiedResponse(uResp);
        String json = new String(raw, StandardCharsets.UTF_8);

        assertThat(json).contains("\"object\":\"response\"");
        assertThat(json).contains("\"status\":\"completed\"");
        assertThat(json).contains("\"type\":\"message\"");
        assertThat(json).contains("\"type\":\"output_text\"");
        assertThat(json).contains("\"text\":\"Hello!\"");
        assertThat(json).contains("\"input_tokens\":10");
        assertThat(json).contains("\"output_tokens\":5");
    }

    @Test
    void shouldConvertToolCallsToResponse() {
        UnifiedChatResponse uResp = new UnifiedChatResponse(
            "resp_def", "gpt-4o", null, 1715000000L,
            List.of(new UnifiedChoice(0,
                new UnifiedMessage(UnifiedMessage.Role.ASSISTANT, null, null,
                    List.of(new UnifiedToolCall("call_xyz", "function",
                        new UnifiedFunctionCall("get_weather",
                            new ObjectMapper().createObjectNode().put("city", "Beijing")))),
                    null, null, null),
                null, "tool_calls", null)),
            null, null);

        byte[] raw = adapter.fromUnifiedResponse(uResp);
        String json = new String(raw, StandardCharsets.UTF_8);

        assertThat(json).contains("\"type\":\"function_call\"");
        assertThat(json).contains("\"name\":\"get_weather\"");
    }

    @Test
    void shouldEmitLifecycleEventsOnFirstChunk() {
        UnifiedChatResponse chunk = new UnifiedChatResponse(
            "resp_1", "gpt-4o", null, 1715000000L,
            List.of(new UnifiedChoice(0, null,
                new UnifiedDelta("assistant", "Hello", null, null), null, null)),
            null, null);

        List<String> events = adapter.toStreamEvents(chunk, true, true);

        assertThat(events).hasSize(5); // created + in_progress + output_item.added + content_part.added + delta
        assertThat(events.get(0)).contains("response.created");
        assertThat(events.get(1)).contains("response.in_progress");
        assertThat(events.get(2)).contains("response.output_item.added");
        assertThat(events.get(3)).contains("response.content_part.added");
        assertThat(events.get(4)).contains("response.output_text.delta");
        assertThat(events.get(4)).contains("Hello");
    }

    @Test
    void shouldSkipLifecycleAfterFirstChunk() {
        UnifiedChatResponse chunk = new UnifiedChatResponse(
            "resp_1", "gpt-4o", null, 1715000000L,
            List.of(new UnifiedChoice(0, null,
                new UnifiedDelta("assistant", " world", null, null), null, null)),
            null, null);

        List<String> events = adapter.toStreamEvents(chunk, false, false);

        assertThat(events).hasSize(1); // 只有 delta
        assertThat(events.get(0)).contains("response.output_text.delta");
        assertThat(events.get(0)).contains(" world");
    }

    @Test
    void shouldEmitCompletionEventsOnFinishReason() {
        UnifiedChatResponse chunk = new UnifiedChatResponse(
            "resp_1", "gpt-4o", null, 1715000000L,
            List.of(new UnifiedChoice(0, null, null, "stop", null)),
            null, null);

        List<String> events = adapter.toStreamEvents(chunk, false, false);

        assertThat(events).hasSize(3); // output_text.done + content_part.done + output_item.done
        assertThat(events.get(0)).contains("response.output_text.done");
        assertThat(events.get(1)).contains("response.content_part.done");
        assertThat(events.get(2)).contains("response.output_item.done");
    }

    @Test
    void shouldWriteResponseCompletedEvent() {
        String event = adapter.completionEvent("resp_test_123", "gpt-4o", 1715000000L);

        assertThat(event).contains("response.completed");
        assertThat(event).contains("\"id\":\"resp_test_123\"");
        assertThat(event).contains("\"model\":\"gpt-4o\"");
        assertThat(event).contains("\"created_at\":");
        assertThat(event).contains("\"status\":\"completed\"");
    }

    @Test
    void shouldEscapeSpecialCharsInStreamDelta() {
        UnifiedChatResponse chunk = new UnifiedChatResponse(
            "resp_1", "gpt-4o", null, 1715000000L,
            List.of(new UnifiedChoice(0, null,
                new UnifiedDelta("assistant", "say \"hello\"\nnew line", null, null), null, null)),
            null, null);

        List<String> events = adapter.toStreamEvents(chunk, false, false);

        assertThat(events.get(0)).contains("say \\\"hello\\\"\\nnew line");
    }

    @Test
    void shouldParseCodexRealRequest() throws IOException {
        byte[] raw = Objects.requireNonNull(
            getClass().getClassLoader().getResourceAsStream(
                "json/responses-codex-real-request.json")).readAllBytes();

        UnifiedChatRequest req = adapter.toUnifiedRequest(raw, null);

        // 基本信息
        assertThat(req.model()).isEqualTo("Minimax 2.7");
        assertThat(req.stream()).isTrue();

        // 消息：instructions 独立 SYSTEM + developer 降级为 USER + 3 user = 5 条
        assertThat(req.messages()).hasSize(5);
        assertThat(req.messages().get(0).role()).isEqualTo(UnifiedMessage.Role.SYSTEM);
        assertThat(req.messages().get(0).content()).contains("coding agent");
        // instructions 独立为 SYSTEM，不再包含 developer 内容
        assertThat(req.messages().get(0).content()).doesNotContain("permissions");

        assertThat(req.messages().get(1).role()).isEqualTo(UnifiedMessage.Role.USER);
        assertThat(req.messages().get(1).content()).contains("permissions");

        assertThat(req.messages().get(2).role()).isEqualTo(UnifiedMessage.Role.USER);
        assertThat(req.messages().get(2).content()).contains("environment_context");

        assertThat(req.messages().get(3).role()).isEqualTo(UnifiedMessage.Role.USER);
        assertThat(req.messages().get(3).content()).contains("你好");

        assertThat(req.messages().get(4).role()).isEqualTo(UnifiedMessage.Role.USER);
        assertThat(req.messages().get(4).content()).contains("你好");

        // 工具：10 个输入（含 1 个 web_search + 9 个 function）→ web_search 被跳过
        assertThat(req.tools()).isNotNull();
        assertThat(req.tools()).hasSize(9);
        assertThat(req.tools()).allMatch(t -> "function".equals(t.type()));
        assertThat(req.tools()).allMatch(t -> t.function() != null);

        // tool_choice
        assertThat(req.toolChoice()).isNotNull();
        assertThat(req.toolChoice()).isInstanceOf(UnifiedToolChoice.Auto.class);

        // config
        assertThat(req.config()).isNotNull();
        assertThat(req.config().temperature()).isNull();
        assertThat(req.config().maxOutputTokens()).isNull();
    }

    @Test
    void shouldPreserveCustomAndNamespaceTools() throws IOException {
        byte[] raw = Objects.requireNonNull(
            getClass().getClassLoader().getResourceAsStream(
                "fixtures/responses-c-answer.json")).readAllBytes();

        UnifiedChatRequest req = adapter.toUnifiedRequest(raw, null);

        assertThat(req.tools()).isNotNull();
        List<String> toolNames = req.tools().stream()
            .map(t -> t.function().name()).toList();

        // apply_patch 作为单工具转换（走 expandCustom 路径）
        assertThat(toolNames).contains("apply_patch");
        // mcp__filesystem 子工具被展平
        assertThat(toolNames).anyMatch(n -> n.startsWith("mcp_filesystem_"));
        // 原有 function 工具保留
        assertThat(toolNames).contains("shell_command");
    }

    @Test
    void shouldExtractOutputTextFromAssistantMessages() throws IOException {
        byte[] raw = Objects.requireNonNull(
            getClass().getClassLoader().getResourceAsStream(
                "fixtures/responses-c-answer.json")).readAllBytes();

        UnifiedChatRequest req = adapter.toUnifiedRequest(raw, null);

        // 找到 assistant 角色消息（非 tool_call 的文本消息）
        List<UnifiedMessage> assistantMsgs = req.messages().stream()
            .filter(m -> m.role() == UnifiedMessage.Role.ASSISTANT && m.content() != null)
            .toList();

        assertThat(assistantMsgs).isNotEmpty();

        // 第一个 assistant 文本消息应包含 "我来先了解一下当前项目的状态"
        UnifiedMessage firstAssistant = assistantMsgs.get(0);
        assertThat(firstAssistant.content())
            .contains("我来先了解一下当前项目的状态");

        // 后面 assistant 消息应包含提问内容 "目标用户是谁"
        boolean hasQuestion = assistantMsgs.stream()
            .anyMatch(m -> m.content() != null && m.content().contains("目标用户是谁"));
        assertThat(hasQuestion).isTrue();

        // 最后一条 user 消息应该是 "C"
        List<UnifiedMessage> userMsgs = req.messages().stream()
            .filter(m -> m.role() == UnifiedMessage.Role.USER)
            .toList();
        assertThat(userMsgs.get(userMsgs.size() - 1).content()).isEqualTo("C");
    }

    @Test
    void shouldRemapCustomToolCallsToCustomToolCallInResponse() {
        UnifiedToolCall tc = new UnifiedToolCall("call_1", "function",
            new UnifiedFunctionCall("apply_patch",
                new ObjectMapper().createObjectNode()
                    .put("input", "*** Begin Patch\n*** Add File: hello.txt\n+Hello\n*** End Patch")));

        UnifiedChatResponse uResp = new UnifiedChatResponse(
            "resp_1", "gpt-4o", null, 1715000000L,
            List.of(new UnifiedChoice(0,
                new UnifiedMessage(UnifiedMessage.Role.ASSISTANT, null, null,
                    List.of(tc), null, null, null),
                null, "tool_calls", null)),
            null, null);

        ToolRemapContext ctx = new ToolRemapContext();
        ctx.putCustom("apply_patch", "apply_patch", ToolRemapContext.Kind.APPLY_PATCH);

        byte[] raw = adapter.fromUnifiedResponse(uResp, null, ctx);
        String json = new String(raw, StandardCharsets.UTF_8);

        assertThat(json).contains("\"type\":\"custom_tool_call\"");
        assertThat(json).contains("\"name\":\"apply_patch\"");
        assertThat(json).contains("*** Begin Patch");
        assertThat(json).doesNotContain("\"type\":\"function_call\"");
    }

    @Test
    void shouldRemapGenericCustomToolCallInResponse() {
        UnifiedToolCall tc = new UnifiedToolCall("call_2", "function",
            new UnifiedFunctionCall("my_custom",
                new ObjectMapper().createObjectNode().put("input", "freeform text")));

        UnifiedChatResponse uResp = new UnifiedChatResponse(
            "resp_2", "gpt-4o", null, 1715000000L,
            List.of(new UnifiedChoice(0,
                new UnifiedMessage(UnifiedMessage.Role.ASSISTANT, null, null,
                    List.of(tc), null, null, null),
                null, "tool_calls", null)),
            null, null);

        ToolRemapContext ctx = new ToolRemapContext();
        ctx.putCustom("my_custom", "my_custom", ToolRemapContext.Kind.RAW);

        byte[] raw = adapter.fromUnifiedResponse(uResp, null, ctx);
        String json = new String(raw, StandardCharsets.UTF_8);

        assertThat(json).contains("\"type\":\"custom_tool_call\"");
        assertThat(json).contains("\"name\":\"my_custom\"");
        assertThat(json).contains("freeform text");
    }

    @Test
    void shouldRemapNamespaceFunctionCallInResponse() {
        UnifiedToolCall tc = new UnifiedToolCall("call_3", "function",
            new UnifiedFunctionCall("mcp_filesystem_read",
                new ObjectMapper().createObjectNode().put("path", "/tmp/test")));

        UnifiedChatResponse uResp = new UnifiedChatResponse(
            "resp_3", "gpt-4o", null, 1715000000L,
            List.of(new UnifiedChoice(0,
                new UnifiedMessage(UnifiedMessage.Role.ASSISTANT, null, null,
                    List.of(tc), null, null, null),
                null, "tool_calls", null)),
            null, null);

        ToolRemapContext ctx = new ToolRemapContext();
        ctx.putNamespace("mcp_filesystem_read", "read", "mcp__filesystem__", "ns0");

        byte[] raw = adapter.fromUnifiedResponse(uResp, null, ctx);
        String json = new String(raw, StandardCharsets.UTF_8);

        assertThat(json).contains("\"type\":\"function_call\"");
        assertThat(json).contains("\"name\":\"read\"");
        assertThat(json).contains("\"namespace\":\"mcp__filesystem__");
    }

    @Test
    void shouldResolveNamespaceFromCustomNsInResponse() throws Exception {
        var ctx = new ToolRemapContext();
        ctx.putNamespace("mcp_filesystem_read", "read", "mcp__filesystem__", "ns0");
        ctx.putNamespace("mcp_filesystem_write", "write", "mcp__filesystem__", "ns0");

        // LLM 返回短名（flatName 查不到），但 args 含 custom_ns
        var tc = new UnifiedToolCall("call_1", "function",
            new UnifiedFunctionCall("read",
                new ObjectMapper().createObjectNode()
                    .put("path", "/etc/hosts")
                    .put(ProxyConstants.MCP_SERVER_ROUTER_PARAM, "ns0")));

        var uResp = new UnifiedChatResponse(
            "resp_1", "test-model", null, 1715000000L,
            List.of(new UnifiedChoice(0,
                new UnifiedMessage(UnifiedMessage.Role.ASSISTANT, null, null,
                    List.of(tc), null, null, null),
                null, "tool_calls", null)),
            null, null);

        byte[] out = adapter.fromUnifiedResponse(uResp, null, ctx);
        var root = new ObjectMapper().readTree(out);
        JsonNode output = root.get("output");
        // output[0]=function_call, output[1]=message
        assertThat(output).hasSize(2);
        var item = output.get(0);
        assertThat(item.get("type").asText()).isEqualTo("function_call");
        assertThat(item.get("name").asText()).isEqualTo("read");
        assertThat(item.get("namespace").asText()).isEqualTo("mcp__filesystem__");
        // 验证 custom_ns 已被剥离
        assertThat(item.get("arguments").asText()).doesNotContain(ProxyConstants.MCP_SERVER_ROUTER_PARAM);
    }

    @Test
    void parseRequestNamespaceKeysMustMatchExpandedToolNames() {
        // 验证 parseSdkTools 注册的 ToolRemapContext key
        // 与 expandNamespace 生成的实际工具名一致
        String json = """
            {
              "model": "gpt-5.5",
              "input": "test",
              "tools": [
                {
                  "type": "namespace",
                  "name": "mcp__filesystem__",
                  "description": "MCP filesystem tools",
                  "tools": [
                    {
                      "type": "function",
                      "name": "create_directory",
                      "description": "Create directory"
                    },
                    {
                      "type": "function",
                      "name": "list_allowed_directories",
                      "description": "List allowed directories"
                    }
                  ]
                }
              ]
            }""";

        var result = adapter.parseRequest(json.getBytes(StandardCharsets.UTF_8));
        var ctx = result.toolRemapContext();

        assertThat(ctx).isNotNull();
        assertThat(ctx.isEmpty()).isFalse();

        // 每个 namespace 子工具的名称必须在 ToolRemapContext 中可查
        List<String> nsToolNames = result.request().tools().stream()
            .map(t -> t.function().name())
            .filter(n -> ctx.getNamespaceSpec(n) != null || n.contains("filesystem"))
            .toList();

        assertThat(nsToolNames).isNotEmpty();
        for (String name : nsToolNames) {
            assertThat(name).as("工具名含 __ : %s", name).doesNotContain("__");
            assertThat(ctx.getNamespaceSpec(name))
                .as("ToolRemapContext 未注册 namespace 工具: %s", name)
                .isNotNull();
        }
    }

    @Test
    void shouldPreserveOriginalFunctionCallWhenNoRemap() {
        UnifiedToolCall tc = new UnifiedToolCall("call_4", "function",
            new UnifiedFunctionCall("get_weather",
                new ObjectMapper().createObjectNode().put("city", "Beijing")));

        UnifiedChatResponse uResp = new UnifiedChatResponse(
            "resp_4", "gpt-4o", null, 1715000000L,
            List.of(new UnifiedChoice(0,
                new UnifiedMessage(UnifiedMessage.Role.ASSISTANT, null, null,
                    List.of(tc), null, null, null),
                null, "tool_calls", null)),
            null, null);

        ToolRemapContext ctx = new ToolRemapContext();

        byte[] raw = adapter.fromUnifiedResponse(uResp, null, ctx);
        String json = new String(raw, StandardCharsets.UTF_8);

        assertThat(json).contains("\"type\":\"function_call\"");
        assertThat(json).contains("\"name\":\"get_weather\"");
        assertThat(json).doesNotContain("custom_tool_call");
    }

    @Test
    void shouldRemapCustomToolCallInStreaming() {
        ToolRemapContext ctx = new ToolRemapContext();
        ctx.putCustom("apply_patch", "apply_patch", ToolRemapContext.Kind.APPLY_PATCH);
        ResponsesProtocolAdapter.StreamState st = new ResponsesProtocolAdapter.StreamState(ctx);

        var args = new ObjectMapper().createObjectNode()
            .put("input", "*** Begin Patch\n*** Add File: hello.txt\n+Hello\n*** End Patch");
        UnifiedChatResponse chunk1 = new UnifiedChatResponse(
            "resp_1", "gpt-4o", null, 1715000000L,
            List.of(new UnifiedChoice(0, null,
                new UnifiedDelta("assistant", null,
                    List.of(new UnifiedToolCall("call_1", "function",
                        new UnifiedFunctionCall("apply_patch", args))),
                    null),
                null, null)),
            null, null);

        List<String> events = adapter.toStreamEvents(chunk1, st);

        String joined = String.join("\n", events);
        // output_item.added 应为 custom_tool_call 类型
        assertThat(joined).contains("\"type\":\"custom_tool_call\"");
        assertThat(joined).contains("\"name\":\"apply_patch\"");
        // 不应有 function_call_arguments.delta（custom 工具跳过）
        assertThat(joined).doesNotContain("function_call_arguments.delta");

        // completion 事件
        String completion = adapter.completionEvent(st, null);
        assertThat(completion).contains("\"type\":\"custom_tool_call\"");
        assertThat(completion).contains("*** Begin Patch");
    }

    @Test
    void shouldRemapNamespaceFunctionCallInStreaming() {
        ToolRemapContext ctx = new ToolRemapContext();
        ctx.putNamespace("mcp_filesystem_read", "read", "mcp__filesystem__", "ns0");
        ResponsesProtocolAdapter.StreamState st = new ResponsesProtocolAdapter.StreamState(ctx);

        var args = new ObjectMapper().createObjectNode().put("path", "/tmp/test");
        UnifiedChatResponse chunk1 = new UnifiedChatResponse(
            "resp_2", "gpt-4o", null, 1715000000L,
            List.of(new UnifiedChoice(0, null,
                new UnifiedDelta("assistant", null,
                    List.of(new UnifiedToolCall("call_2", "function",
                        new UnifiedFunctionCall("mcp_filesystem_read", args))),
                    null),
                null, null)),
            null, null);

        List<String> events = adapter.toStreamEvents(chunk1, st);

        String joined = String.join("\n", events);
        assertThat(joined).contains("\"type\":\"function_call\"");
        assertThat(joined).contains("\"name\":\"read\"");
        // namespace 工具应有 arguments delta（output_item.done 中的 namespace
        // 在 closeFuncBlocks 中构建，当前 chunk 无 finishReason 时不会触发）
        assertThat(joined).contains("function_call_arguments.delta");

        // completion 事件包含修复后的 name 和 namespace
        String completion = adapter.completionEvent(st, null);
        assertThat(completion).contains("\"name\":\"read\"");
        assertThat(completion).contains("\"namespace\":\"mcp__filesystem__\"");
    }

    @Test
    void shouldResolveNamespaceFromCustomNsInStreaming() {
        ToolRemapContext ctx = new ToolRemapContext();
        ctx.putNamespace("mcp_filesystem_read", "read", "mcp__filesystem__", "ns0");
        ResponsesProtocolAdapter.StreamState state = new ResponsesProtocolAdapter.StreamState(ctx);

        var args = new ObjectMapper().createObjectNode()
            .put("path", "/x")
            .put(ProxyConstants.MCP_SERVER_ROUTER_PARAM, "ns0");
        UnifiedChatResponse chunk = new UnifiedChatResponse(
            "resp_ns", "test-model", null, 1715000000L,
            List.of(new UnifiedChoice(0, null,
                new UnifiedDelta("assistant", null,
                    List.of(new UnifiedToolCall("call_ns", "function",
                        new UnifiedFunctionCall("read", args))),
                    null),
                null, null)),
            null, null);

        List<String> events = adapter.toStreamEvents(chunk, state);

        String joined = String.join("\n", events);
        assertThat(joined).contains("\"name\":\"read\"");
        assertThat(joined).contains("function_call_arguments.delta");

        String completion = adapter.completionEvent(state, null);
        assertThat(completion).contains("\"name\":\"read\"");
        assertThat(completion).contains("\"namespace\":\"mcp__filesystem__\"");
        assertThat(completion).doesNotContain(ProxyConstants.MCP_SERVER_ROUTER_PARAM);
    }

    @Test
    void shouldFallbackWhenArgsInputMissing() {
        ToolRemapContext ctx = new ToolRemapContext();
        ctx.putCustom("apply_patch", "apply_patch", ToolRemapContext.Kind.APPLY_PATCH);

        // 参数中没有 input 字段
        UnifiedToolCall tc = new UnifiedToolCall("call_fb", "function",
            new UnifiedFunctionCall("apply_patch",
                new ObjectMapper().createObjectNode().put("wrong_field", "value")));

        UnifiedChatResponse uResp = new UnifiedChatResponse(
            "resp_fb", "gpt-4o", null, 1715000000L,
            List.of(new UnifiedChoice(0,
                new UnifiedMessage(UnifiedMessage.Role.ASSISTANT, null, null,
                    List.of(tc), null, null, null),
                null, "tool_calls", null)),
            null, null);

        byte[] raw = adapter.fromUnifiedResponse(uResp, null, ctx);
        String json = new String(raw, StandardCharsets.UTF_8);

        // 应降级为 custom_tool_call 但 input 为空字符串（不抛异常）
        assertThat(json).contains("\"type\":\"custom_tool_call\"");
        assertThat(json).contains("\"input\":\"\"");
    }

    @Test
    void shouldParseCustomToolCallInputItem() {
        String json = """
            {"model":"gpt-4o","input":[{"type":"custom_tool_call","call_id":"call_123","name":"apply_patch","input":"*** Begin Patch\\n*** Add File: test.java\\n+code\\n*** End Patch"}]}""";

        UnifiedChatRequest req = adapter.toUnifiedRequest(
            json.getBytes(StandardCharsets.UTF_8), null);

        assertThat(req.messages()).hasSize(1);
        var msg = req.messages().get(0);
        assertThat(msg.role()).isEqualTo(UnifiedMessage.Role.ASSISTANT);
        assertThat(msg.toolCalls()).isNotNull().hasSize(1);
        var tc = msg.toolCalls().get(0);
        assertThat(tc.id()).isEqualTo("call_123");
        assertThat(tc.type()).isEqualTo("function");
        assertThat(tc.function().name()).isEqualTo("apply_patch");
        assertThat(tc.function().arguments().get("input").asText())
            .isEqualTo("*** Begin Patch\n*** Add File: test.java\n+code\n*** End Patch");
    }

    @Test
    void shouldParseCustomToolCallOutputInputItem() {
        String json = """
            {"model":"gpt-4o","input":[{"type":"custom_tool_call_output","call_id":"call_123","output":"工具执行结果"}]}""";

        UnifiedChatRequest req = adapter.toUnifiedRequest(
            json.getBytes(StandardCharsets.UTF_8), null);

        assertThat(req.messages()).hasSize(1);
        var msg = req.messages().get(0);
        assertThat(msg.role()).isEqualTo(UnifiedMessage.Role.TOOL);
        assertThat(msg.toolCallId()).isEqualTo("call_123");
        assertThat(msg.content()).isEqualTo("工具执行结果");
    }

}
