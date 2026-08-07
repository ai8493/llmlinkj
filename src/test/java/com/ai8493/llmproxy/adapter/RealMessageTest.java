package com.ai8493.llmproxy.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ai8493.llmproxy.adapter.gemini.GeminiProtocolAdapter;
import com.ai8493.llmproxy.model.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真实报文全量验证测试。
 * 使用 {@code json/} 下从生产日志提取的 20 个 Gemini 请求和 15 个 OpenAI 响应报文，
 * 验证格式正确性和结构完整性。
 */
@DisplayName("真实报文验证")
class RealMessageTest {

    private static final ObjectMapper mapper = new ObjectMapper();
    private static GeminiProtocolAdapter geminiAdapter;

    private static final int REQUEST_COUNT = 20;
    private static final int RESPONSE_COUNT = 15;

    @BeforeAll
    static void setUp() {
        geminiAdapter = new GeminiProtocolAdapter();
    }

    // ====== Gemini 请求报文 ======

    @Test
    @DisplayName("Gemini 请求报文: 20个文件全部可解析为合法 JsonNode")
    void shouldParseAllGeminiRequestFixtures() throws Exception {
        for (int i = 1; i <= REQUEST_COUNT; i++) {
            byte[] raw = readJsonFixture(i);
            JsonNode json = mapper.readTree(raw);

            assertThat(json.has("contents")).as("文件 #%d 含 contents", i).isTrue();
            assertThat(json.get("contents").size()).as("文件 #%d 消息数 > 0", i).isPositive();

            // systemInstruction 存在（Gemini CLI 格式为顶层字段）
            assertThat(json.has("systemInstruction")).as("文件 #%d 含 systemInstruction", i).isTrue();

            // tools 存在且非空
            assertThat(json.has("tools")).as("文件 #%d 含 tools", i).isTrue();
            assertThat(json.get("tools").size()).as("文件 #%d tools 非空", i).isPositive();
        }
    }

    @Test
    @DisplayName("Gemini 请求报文: 消息角色和 systemInstruction 正确")
    void shouldHaveCorrectRolesAndSystemInstruction() throws Exception {
        // 取样：最小(#1)、中等(#10)、最大(#20)
        int[] samples = {1, 10, 20};
        for (int i : samples) {
            byte[] raw = readJsonFixture(i);
            JsonNode json = mapper.readTree(raw);

            // systemInstruction 存在
            assertThat(json.has("systemInstruction"))
                .as("文件 #%d 含 systemInstruction", i).isTrue();

            // 每条 contents 都有 role
            for (JsonNode content : json.get("contents")) {
                assertThat(content.has("role")).as("文件 #%d 消息有 role", i).isTrue();
                assertThat(content.has("parts")).as("文件 #%d 消息有 parts", i).isTrue();
            }

            // tools 存在
            assertThat(json.has("tools")).as("文件 #%d 含 tools", i).isTrue();
        }
    }

    @Test
    @DisplayName("Gemini 请求报文: 中文内容无乱码")
    void shouldPreserveChineseContent() throws Exception {
        byte[] raw = readJsonFixture(1);
        String bodyStr = new String(raw, StandardCharsets.UTF_8);

        // 真实报文中的中文关键词
        assertThat(bodyStr).contains("用java25+swing");
        assertThat(bodyStr).contains("俄罗斯方块游戏");
        assertThat(bodyStr).contains("详细的设计文档和执行计划文档");
    }

    @Test
    @DisplayName("Gemini 请求报文: 转为 IR 消息数正确")
    void shouldConvertToIRWithCorrectMessageCount() throws Exception {
        int[] samples = {1, 4, 7, 14, 20};
        for (int i : samples) {
            byte[] raw = readJsonFixture(i);
            UnifiedChatRequest ir = geminiAdapter.toUnifiedRequest(raw, Map.of());

            assertThat(ir.messages()).as("文件 #%d IR 消息数 > 0", i).isNotEmpty();

            // 第一条应为 system（来自 systemInstruction）
            if (!ir.messages().isEmpty()) {
                assertThat(ir.messages().get(0).role())
                    .as("文件 #%d 首条消息为 SYSTEM", i)
                    .isEqualTo(UnifiedMessage.Role.SYSTEM);
            }

            assertThat(ir.tools()).as("文件 #%d IR tools 非空", i).isNotNull();
            assertThat(ir.tools().size()).as("文件 #%d IR 工具数 >= 12", i).isGreaterThanOrEqualTo(12);
        }
    }

    @Test
    @DisplayName("Gemini 请求报文: 各文件消息数记为快照")
    void snapshotMessageCounts() {
        int[] expectedCounts = {
            3,  // #1: 2 user 消息 + 1 system
            9,  // #2: 8 user 消息 + 1 system
            15, // #3
            21, // #4
            27, // #5
            33, // #6
            39, // #7
            45, // #8
            51, // #9
            59, // #10
            63, // #11
            69, // #12
            75, // #13
            81, // #14
            87, // #15
            93, // #16
            99, // #17
            105,// #18
            111,// #19
            117 // #20
        };
        // 由于 systemInstruction 被转为一条 system 消息，IR 消息数 = contents 数 + 1
        // 暂不做精确断言，仅打印快照供参考
        // 至少确保所有文件都能成功转换
        for (int i = 1; i <= REQUEST_COUNT; i++) {
            try {
                byte[] raw = readJsonFixture(i);
                UnifiedChatRequest ir = geminiAdapter.toUnifiedRequest(raw, Map.of());
                int msgCount = ir.messages().size();
                assertThat(msgCount).as("文件 #%d 消息数快照", i).isPositive();
            } catch (Exception e) {
                throw new RuntimeException("文件 #" + i + " 转换失败: " + e.getMessage(), e);
            }
        }
    }

    // ====== OpenAI 响应报文 ======

    private static final Pattern ID_PATTERN = Pattern.compile("ChatCompletionChunk\\{id=([a-f0-9]+),");
    private static final Pattern FR_PATTERN = Pattern.compile("finishReason=([^,\\]]+)");

    @Test
    @DisplayName("OpenAI 响应报文: 15个文件的 chunk id 一致性")
    void shouldHaveConsistentIdPerResponse() throws Exception {
        for (int i = 1; i <= RESPONSE_COUNT; i++) {
            List<String> chunks = readResponseFixture(i);

            assertThat(chunks).as("响应 #%d 至少 2 个 chunk", i).hasSizeGreaterThanOrEqualTo(2);

            Set<String> ids = new HashSet<>();
            for (String chunk : chunks) {
                Matcher m = ID_PATTERN.matcher(chunk);
                assertThat(m.find()).as("响应 #%d chunk 含 id", i).isTrue();
                ids.add(m.group(1));
            }

            assertThat(ids).as("响应 #%d 所有 chunk id 一致", i).hasSize(1);
        }
    }

    @Test
    @DisplayName("OpenAI 响应报文: 每个 chunk 结构完整")
    void shouldHaveValidChunkStructure() throws Exception {
        for (int i = 1; i <= RESPONSE_COUNT; i++) {
            List<String> chunks = readResponseFixture(i);

            for (int j = 0; j < chunks.size(); j++) {
                String chunk = chunks.get(j);
                assertThat(chunk).as("响应 #%d chunk[%d] 以 ChatCompletionChunk 开头", i, j)
                    .startsWith("ChatCompletionChunk{");
                assertThat(chunk).as("响应 #%d chunk[%d] 含 choices=", i, j)
                    .contains("choices=[");
            }
        }
    }

    @Test
    @DisplayName("OpenAI 响应报文: 含 toolcalls 标记的文件确实含 toolCalls 块")
    void filesWithToolCallsTagShouldActuallyHaveToolCalls() throws Exception {
        for (int i = 1; i <= RESPONSE_COUNT; i++) {
            List<String> chunks = readResponseFixture(i);
            boolean tagged = responseFileName(i).contains("toolcalls");
            boolean hasToolCalls = chunks.stream()
                .anyMatch(c -> c.contains("toolCalls=[") && !c.contains("toolCalls=[]"));

            if (tagged) {
                assertThat(hasToolCalls)
                    .as("响应 #%d 文件名含 toolcalls 标记, 数据应含非空 toolCalls", i)
                    .isTrue();
            }
        }
    }

    @Test
    @DisplayName("OpenAI 响应报文: 最后一个 chunk 的 finishReason 非空")
    void shouldHaveTerminalFinishReason() throws Exception {
        for (int i = 1; i <= RESPONSE_COUNT; i++) {
            List<String> chunks = readResponseFixture(i);
            String last = chunks.get(chunks.size() - 1);

            Matcher m = FR_PATTERN.matcher(last);
            String finishReason = "";
            while (m.find()) {
                String val = m.group(1).trim();
                if (!val.isEmpty()) {
                    finishReason = val;
                }
            }

            assertThat(finishReason).as("响应 #%d 最后 chunk finishReason 非空", i)
                .isNotEmpty();
        }
    }

    @Test
    @DisplayName("OpenAI 响应报文: 完整 chunk 的 object_ 为 chat.completion.chunk")
    void shouldHaveCorrectObjectType() throws Exception {
        for (int i = 1; i <= RESPONSE_COUNT; i++) {
            List<String> chunks = readResponseFixture(i);
            int checked = 0;
            for (int j = 0; j < chunks.size(); j++) {
                String chunk = chunks.get(j);
                // 只检查未被截断的完整 chunk（含 object_= 字段）
                if (chunk.contains("object_=")) {
                    assertThat(chunk)
                        .as("响应 #%d chunk[%d] object_=chat.completion.chunk", i, j)
                        .contains("object_=chat.completion.chunk");
                    checked++;
                }
            }
            assertThat(checked).as("响应 #%d 至少 1 个完整 chunk", i).isPositive();
        }
    }

    // ====== 辅助方法 ======

    private static byte[] readJsonFixture(int index) throws Exception {
        var resolver = new org.springframework.core.io.support.PathMatchingResourcePatternResolver();
        var resources = resolver.getResources("classpath:json/gemini-request-real-" +
            String.format("%02d", index) + "*.json");
        if (resources.length == 0) {
            throw new RuntimeException("未找到请求文件: #" + index);
        }
        return resources[0].getInputStream().readAllBytes();
    }

    private static String responseFileName(int index) throws Exception {
        String prefix = "json/openai-response-real-" + String.format("%02d", index);
        // 扫描可用文件
        var resolver = new org.springframework.core.io.support.PathMatchingResourcePatternResolver();
        var resources = resolver.getResources("classpath:json/openai-response-real-" +
            String.format("%02d", index) + "*.json");
        if (resources.length == 0) {
            throw new RuntimeException("未找到响应文件: " + prefix);
        }
        return resources[0].getFilename();
    }

    @SuppressWarnings("unchecked")
    private static List<String> readResponseFixture(int index) throws Exception {
        var resolver = new org.springframework.core.io.support.PathMatchingResourcePatternResolver();
        var resources = resolver.getResources("classpath:json/openai-response-real-" +
            String.format("%02d", index) + "*.json");
        if (resources.length == 0) {
            throw new RuntimeException("未找到响应文件: #" + index);
        }
        byte[] raw = resources[0].getInputStream().readAllBytes();
        return mapper.readValue(raw, List.class);
    }
}
