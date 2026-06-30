package com.ai8493.llmproxy.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ai8493.llmproxy.adapter.gemini.GeminiProtocolAdapter;
import com.ai8493.llmproxy.adapter.openai.OpenAiRequestConverter;
import com.ai8493.llmproxy.adapter.openai.OpenAiResponseConverter;
import com.ai8493.llmproxy.adapter.openai.OpenAiStreamingResponseConverter;
import com.ai8493.llmproxy.model.*;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionChunk;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Gemini CLI → OpenAI 后端全链路报文转换完整性测试。
 * 使用 {@code fixtures/gemini-cli-real-request.json} 等真实报文夹具，
 * 逐字段验证转换无遗漏、无乱码、参数保真。
 */
@DisplayName("Gemini CLI ↔ OpenAI 全链路报文转换")
class GeminiToOpenAiConversionTest {

    private static final ObjectMapper mapper = new ObjectMapper();
    private static GeminiProtocolAdapter geminiAdapter;
    private static OpenAiRequestConverter requestConverter;
    private static OpenAiResponseConverter responseConverter;
    private static final String TEST_MODEL = "gpt-4";

    @BeforeAll
    static void setUp() {
        geminiAdapter = new GeminiProtocolAdapter();
        requestConverter = new OpenAiRequestConverter();
        responseConverter = new OpenAiResponseConverter();
    }

    private UnifiedChatRequest geminiToIR(byte[] raw) {
        UnifiedChatRequest ir = geminiAdapter.toUnifiedRequest(raw, Map.of());
        return new UnifiedChatRequest(TEST_MODEL, ir.messages(), ir.config(),
            ir.tools(), ir.toolChoice(), ir.stream());
    }

    // ================================================================
    // 阶段一：真实 Gemini CLI 请求 → IR（使用真实报文）
    // ================================================================

    @Nested
    @DisplayName("阶段一：真实 Gemini CLI 请求 → IR")
    class RealGeminiRequestToIR {

        @Test
        @DisplayName("真实报文解析：消息数量、角色、systemInstruction")
        void realRequestMessages() throws Exception {
            byte[] raw = readFixture("fixtures/gemini-cli-real-request.json");
            UnifiedChatRequest ir = geminiToIR(raw);

            // systemInstruction 转为 system 消息 + 2 条 user 消息
            assertThat(ir.messages()).hasSize(3);
            assertThat(ir.messages().get(0).role()).isEqualTo(UnifiedMessage.Role.SYSTEM);
            assertThat(ir.messages().get(0).content()).isNotEmpty();
            assertThat(ir.messages().get(1).role()).isEqualTo(UnifiedMessage.Role.USER);
            assertThat(ir.messages().get(2).role()).isEqualTo(UnifiedMessage.Role.USER);
        }

        @Test
        @DisplayName("真实报文解析：12+ 工具定义全部保留，含深层嵌套参数")
        void realRequestTools() throws Exception {
            byte[] raw = readFixture("fixtures/gemini-cli-real-request.json");
            UnifiedChatRequest ir = geminiToIR(raw);

            assertThat(ir.tools()).isNotNull();
            assertThat(ir.tools().size()).isGreaterThanOrEqualTo(12);

            // 验证 update_topic 工具的嵌套参数（含 boolean 类型）
            Optional<UnifiedTool> updateTopic = ir.tools().stream()
                .filter(t -> t.function().name().equals("update_topic")).findFirst();
            assertThat(updateTopic).isPresent();
            JsonNode params = updateTopic.get().function().parameters();
            assertThat(params.get("required").get(0).asText()).isEqualTo("strategic_intent");
            assertThat(params.get("properties").get("wait_for_previous").get("type").asText())
                .isEqualTo("boolean");

            // 验证 replace 工具（含 old_string/new_string required 字段）
            Optional<UnifiedTool> replaceTool = ir.tools().stream()
                .filter(t -> t.function().name().equals("replace")).findFirst();
            assertThat(replaceTool).isPresent();
            JsonNode replaceParams = replaceTool.get().function().parameters();
            assertThat(replaceParams.get("required").toString())
                .contains("new_string").contains("old_string");
        }

        @Test
        @DisplayName("真实报文解析：中文内容不乱码")
        void realRequestChineseNoGarbling() throws Exception {
            byte[] raw = readFixture("fixtures/gemini-cli-real-request.json");
            UnifiedChatRequest ir = geminiToIR(raw);

            String userText = ir.messages().get(2).content();
            assertThat(userText).contains("执行 java -version");
        }

        @Test
        @DisplayName("多 functionResponse 拆分：每个 functionResponse 独立为一条 TOOL 消息")
        void multiFunctionResponseSplitIntoIndividualToolMessages() throws Exception {
            byte[] raw = readFixture("json/gemini-request-real-multi-fr.json");
            UnifiedChatRequest ir = geminiAdapter.toUnifiedRequest(raw, Map.of());

            // 收集 assistant(tool_calls) 的所有 tool_call_id
            Set<String> asstTcIds = new java.util.LinkedHashSet<>();
            for (UnifiedMessage msg : ir.messages()) {
                if (msg.role() == UnifiedMessage.Role.ASSISTANT && msg.toolCalls() != null) {
                    for (UnifiedToolCall tc : msg.toolCalls()) {
                        if (tc.id() != null) asstTcIds.add(tc.id());
                    }
                }
            }

            // 收集所有 TOOL 消息
            List<UnifiedMessage> toolMsgs = ir.messages().stream()
                .filter(m -> m.role() == UnifiedMessage.Role.TOOL)
                .toList();

            // 与原始 JSON 中 functionResponse 数量一致
            assertThat(toolMsgs).as("TOOL 消息数与 functionResponse 数一致").hasSize(50);

            // 每条 TOOL 消息的 toolCallId 都能在 assistant(tool_calls) 中找到
            for (UnifiedMessage toolMsg : toolMsgs) {
                assertThat(toolMsg.toolCallId())
                    .as("TOOL 消息 toolCallId 非空且在 assistant 中有对应")
                    .isNotNull()
                    .isIn(asstTcIds);
            }
        }
    }

    // ================================================================
    // 阶段二：IR → OpenAI SDK 请求（验证工具参数保真）
    // ================================================================

    @Nested
    @DisplayName("阶段二：IR → OpenAI SDK 请求")
    class IRToOpenAIRequest {

        @Test
        @DisplayName("真实工具定义 → OpenAI tools 嵌套结构完整")
        void realToolsToOpenAI() throws Exception {
            byte[] raw = readFixture("fixtures/gemini-cli-real-request.json");
            UnifiedChatRequest ir = geminiToIR(raw);
            ChatCompletionCreateParams params = requestConverter.convert(ir);

            assertThat(params.tools()).isPresent();
            var tools = params.tools().get();
            assertThat(tools.size()).isGreaterThanOrEqualTo(12);

            // replace 工具的嵌套参数结构
            var replaceFn = tools.stream()
                .filter(t -> t.asFunction().function().name().equals("replace"))
                .findFirst().orElseThrow();
            assertThat(replaceFn.asFunction().function().parameters().isPresent()).isTrue();
        }

        @Test
        @DisplayName("systemInstruction → system 消息内容完整（非截断）")
        void systemInstructionNotTruncated() throws Exception {
            byte[] raw = readFixture("fixtures/gemini-cli-real-request.json");
            UnifiedChatRequest ir = geminiToIR(raw);
            ChatCompletionCreateParams params = requestConverter.convert(ir);

            var systemMsg = params.messages().get(0);
            assertThat(systemMsg.isSystem()).isTrue();
            // 真实 systemInstruction 超过 10000 字符
            assertThat(systemMsg.asSystem().content().asText().length()).isGreaterThan(10000);
        }
    }

    // ================================================================
    // 阶段三：OpenAI SDK 响应 → IR（真实后端响应报文）
    // ================================================================

    @Nested
    @DisplayName("阶段三：真实后端响应 → IR")
    class RealBackendResponseToIR {

        @Test
        @DisplayName("DeepSeek 工具调用响应：arguments 含反斜杠路径保真")
        void deepseekToolCallsArguments() throws Exception {
            byte[] raw = readFixture("fixtures/deepseek-tool-calls-response.json");
            ChatCompletion sdkResp = mapper.readValue(raw, ChatCompletion.class);
            UnifiedChatResponse ir = responseConverter.convert(sdkResp);

            UnifiedMessage msg = ir.choices().get(0).message();
            assertThat(msg.toolCalls()).hasSize(1);
            JsonNode args = msg.toolCalls().get(0).function().arguments();
            assertThat(args.get("command").asText()).isEqualTo("java -version");
            assertThat(args.get("dir_path").asText()).isEqualTo("D:\\AI\\gemini-work");
        }

        @Test
        @DisplayName("JSON 序列化往返：arguments → String → JsonNode 一致")
        void argumentsRoundTripThroughString() throws Exception {
            byte[] raw = readFixture("fixtures/deepseek-tool-calls-response.json");
            ChatCompletion sdkResp = mapper.readValue(raw, ChatCompletion.class);
            UnifiedChatResponse ir = responseConverter.convert(sdkResp);

            JsonNode original = ir.choices().get(0).message().toolCalls().get(0).function().arguments();
            // 重新序列化再解析，验证不丢失
            JsonNode reparsed = mapper.readTree(mapper.writeValueAsString(original));
            assertThat(reparsed.get("command").asText()).isEqualTo("java -version");
            assertThat(reparsed.get("dir_path").asText()).isEqualTo("D:\\AI\\gemini-work");
        }

        @Test
        @DisplayName("文本响应：所有基础字段完整")
        void textResponseAllFields() throws Exception {
            byte[] raw = readFixture("fixtures/openai-text-response.json");
            ChatCompletion sdkResp = mapper.readValue(raw, ChatCompletion.class);
            UnifiedChatResponse ir = responseConverter.convert(sdkResp);

            assertThat(ir.id()).isEqualTo("chatcmpl-test-123");
            assertThat(ir.model()).isEqualTo("gpt-4");
            assertThat(ir.object()).isEqualTo("chat.completion");
            assertThat(ir.created()).isPositive();
            assertThat(ir.choices().get(0).finishReason()).isEqualTo("stop");
            assertThat(ir.usage().promptTokens()).isEqualTo(10);
            assertThat(ir.usage().completionTokens()).isEqualTo(5);
            assertThat(ir.usage().totalTokens()).isEqualTo(15);
        }
    }

    // ================================================================
    // 阶段四：IR → Gemini 响应（验证 Gemini CLI 兼容性）
    // ================================================================

    @Nested
    @DisplayName("阶段四：IR → Gemini CLI 响应")
    class IRToGeminiResponse {

        @Test
        @DisplayName("工具调用 → Gemini functionCall：id/name/args 全部保留")
        void toolCallToGeminiFunctionCall() throws Exception {
            byte[] raw = readFixture("fixtures/deepseek-tool-calls-response.json");
            ChatCompletion sdkResp = mapper.readValue(raw, ChatCompletion.class);
            UnifiedChatResponse ir = responseConverter.convert(sdkResp);

            byte[] geminiResp = geminiAdapter.fromUnifiedResponse(ir);
            JsonNode geminiJson = mapper.readTree(geminiResp);

            JsonNode parts = geminiJson.get("candidates").get(0)
                .get("content").get("parts");
            JsonNode fc = null;
            for (JsonNode p : parts) {
                if (p.has("functionCall")) { fc = p.get("functionCall"); break; }
            }
            assertThat(fc).isNotNull();
            assertThat(fc.get("name").asText()).isEqualTo("run_shell_command");
            assertThat(fc.has("id")).isTrue();
            assertThat(fc.get("id").asText()).isNotEmpty();
            // args 含反斜杠路径
            assertThat(fc.get("args").get("command").asText()).isEqualTo("java -version");
            assertThat(fc.get("args").get("dir_path").asText()).isEqualTo("D:\\AI\\gemini-work");
        }

        @Test
        @DisplayName("safe 响应 → finishReason=content_filter")
        void safetyResponseFinishReason() throws Exception {
            byte[] raw = readFixture("fixtures/openai-safety-response.json");
            ChatCompletion sdkResp = mapper.readValue(raw, ChatCompletion.class);
            UnifiedChatResponse ir = responseConverter.convert(sdkResp);

            byte[] geminiResp = geminiAdapter.fromUnifiedResponse(ir);
            JsonNode geminiJson = mapper.readTree(geminiResp);

            assertThat(geminiJson.get("candidates").get(0).get("finishReason").asText())
                .isEqualTo("SAFETY");
        }
    }

    // ================================================================
    // 阶段五：全链路——真实报文往返
    // ================================================================

    @Nested
    @DisplayName("阶段五：真实报文全链路往返")
    class RealFullRoundTrip {

        @Test
        @DisplayName("Gemini CLI 请求 → IR → OpenAI → DeepSeek 响应 → IR → Gemini 响应")
        void realGeminiCLIRequestToDeepseekResponse() throws Exception {
            // 1. 真实 Gemini CLI 请求 → IR
            byte[] reqRaw = readFixture("fixtures/gemini-cli-real-request.json");
            UnifiedChatRequest reqIR = geminiToIR(reqRaw);

            // 2. IR → OpenAI SDK 请求
            ChatCompletionCreateParams params = requestConverter.convert(reqIR);
            assertThat(params.messages()).hasSize(3);
            assertThat(params.tools()).isPresent();

            // 3. 真实 DeepSeek 响应 → IR
            byte[] respRaw = readFixture("fixtures/deepseek-tool-calls-response.json");
            ChatCompletion sdkResp = mapper.readValue(respRaw, ChatCompletion.class);
            UnifiedChatResponse respIR = responseConverter.convert(sdkResp);

            // 4. IR → Gemini CLI 响应
            byte[] geminiResp = geminiAdapter.fromUnifiedResponse(respIR);
            JsonNode geminiJson = mapper.readTree(geminiResp);

            // 验证 Gemini CLI 能正确解析
            JsonNode fc = geminiJson.get("candidates").get(0)
                .get("content").get("parts").get(0).get("functionCall");
            assertThat(fc.get("name").asText()).isEqualTo("run_shell_command");
            assertThat(fc.has("id")).isTrue();
            assertThat(fc.get("args").get("command").asText()).isEqualTo("java -version");
            assertThat(fc.get("args").get("dir_path").asText()).isEqualTo("D:\\AI\\gemini-work");
        }

        @Test
        @DisplayName("参数双向比对：入站 tool schema vs 出站 functionCall args 类型一致")
        void toolSchemaMatchesFunctionCallArgs() throws Exception {
            byte[] reqRaw = readFixture("fixtures/gemini-cli-real-request.json");
            UnifiedChatRequest reqIR = geminiToIR(reqRaw);

            // 找到 run_shell_command 的参数 schema
            UnifiedTool shellTool = reqIR.tools().stream()
                .filter(t -> t.function().name().equals("run_shell_command"))
                .findFirst().orElseThrow();
            JsonNode schema = shellTool.function().parameters();
            assertThat(schema.get("required").toString()).contains("command");

            // 模拟后端返回的 run_shell_command 调用与实际 schema 匹配
            byte[] respRaw = readFixture("fixtures/deepseek-tool-calls-response.json");
            ChatCompletion sdkResp = mapper.readValue(respRaw, ChatCompletion.class);
            UnifiedChatResponse respIR = responseConverter.convert(sdkResp);

            byte[] geminiResp = geminiAdapter.fromUnifiedResponse(respIR);
            JsonNode geminiJson = mapper.readTree(geminiResp);
            JsonNode fc = geminiJson.get("candidates").get(0)
                .get("content").get("parts").get(0).get("functionCall");

            // 返回的 args 包含 schema 要求的 command 字段
            assertThat(fc.get("args").has("command")).isTrue();
            // 返回的 args 是 String → String 映射（与 schema command.type=string 一致）
            assertThat(fc.get("args").get("command").isTextual()).isTrue();
        }
    }

    // ================================================================
    // 阶段六：流式工具调用
    // ================================================================

    @Nested
    @DisplayName("阶段六：流式转换")
    class StreamingConversion {

        @Test
        @DisplayName("流式工具调用累积：id/name/args 正确组装")
        void streamingToolCallAccumulation() {
            var converter = new OpenAiStreamingResponseConverter("gpt-4");

            var optEmpty = java.util.Optional.<ChatCompletionChunk.Choice.FinishReason>empty();

            converter.convertChunk(choiceChunk(0L, optEmpty,
                deltaWithToolCall(0L, "call_001", "run_shell_command", null)));
            converter.convertChunk(choiceChunk(0L, optEmpty,
                deltaWithToolCall(0L, null, null, "{\"command\": \"java -version\"}")));

            UnifiedChatResponse assembled = converter.convertChunk(choiceChunk(0L,
                Optional.of(ChatCompletionChunk.Choice.FinishReason.TOOL_CALLS), emptyDelta()));

            var tc = assembled.choices().get(0).delta().toolCalls().get(0);
            assertThat(tc.id()).isEqualTo("call_001");
            assertThat(tc.function().name()).isEqualTo("run_shell_command");
            assertThat(tc.function().arguments().get("command").asText())
                .isEqualTo("java -version");
        }

        @Test
        @DisplayName("流式 IR → Gemini SSE 含 functionCall")
        void streamingIRToGeminiSSEWithFunctionCall() throws Exception {
            var converter = new OpenAiStreamingResponseConverter("gpt-4");
            var optEmpty = java.util.Optional.<ChatCompletionChunk.Choice.FinishReason>empty();

            converter.convertChunk(choiceChunk(0L, optEmpty,
                deltaWithToolCall(0L, "call_abc", "run_shell_command",
                    "{\"command\":\"java -version\",\"dir_path\":\"D:\\\\AI\\\\gemini-work\"}")));
            UnifiedChatResponse ir = converter.convertChunk(choiceChunk(0L,
                Optional.of(ChatCompletionChunk.Choice.FinishReason.TOOL_CALLS), emptyDelta()));

            String sse = geminiAdapter.fromUnifiedStreamChunk(ir);
            JsonNode sseJson = mapper.readTree(sse);

            JsonNode fc = sseJson.get("candidates").get(0)
                .get("content").get("parts").get(0).get("functionCall");
            assertThat(fc.get("name").asText()).isEqualTo("run_shell_command");
            assertThat(fc.has("id")).isTrue();
            assertThat(fc.get("args").get("command").asText()).isEqualTo("java -version");
            assertThat(fc.get("args").get("dir_path").asText()).isEqualTo("D:\\AI\\gemini-work");
        }
    }

    // ================================================================
    // 辅助方法
    // ================================================================

    private static byte[] readFixture(String path) throws Exception {
        return new ClassPathResource(path).getInputStream().readAllBytes();
    }

    private static ChatCompletionChunk choiceChunk(long index,
            Optional<ChatCompletionChunk.Choice.FinishReason> fr,
            ChatCompletionChunk.Choice.Delta delta) {
        var cb = ChatCompletionChunk.Choice.builder()
            .index(index).delta(delta);
        fr.ifPresentOrElse(cb::finishReason, () -> cb.finishReason(Optional.empty()));
        return ChatCompletionChunk.builder()
            .id("1").model("x").created(1L)
            .choices(List.of(cb.build()))
            .build();
    }

    private static ChatCompletionChunk.Choice.Delta emptyDelta() {
        return ChatCompletionChunk.Choice.Delta.builder().build();
    }

    private static ChatCompletionChunk.Choice.Delta deltaWithToolCall(
            long index, String id, String name, String arguments) {
        var fnBuilder = ChatCompletionChunk.Choice.Delta.ToolCall.Function.builder();
        if (name != null) fnBuilder.name(name);
        if (arguments != null) fnBuilder.arguments(arguments);

        var tcBuilder = ChatCompletionChunk.Choice.Delta.ToolCall.builder()
            .index(index).function(fnBuilder.build());
        if (id != null) tcBuilder.id(id);

        return ChatCompletionChunk.Choice.Delta.builder()
            .toolCalls(List.of(tcBuilder.build()))
            .build();
    }
}
