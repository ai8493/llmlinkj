package com.ai8493.llmproxy.adapter.gemini;

import com.ai8493.llmproxy.converter.ToolMapper;
import com.ai8493.llmproxy.model.*;
import com.ai8493.llmproxy.model.extensions.GeminiExtensions;
import com.ai8493.llmproxy.model.extensions.ThinkingConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.genai.types.GenerateContentParameters;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class GeminiRequestConverterExtensionsTest {

    private final GeminiRequestConverter converter = new GeminiRequestConverter(new ToolMapper());
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void shouldRebuildTopKFromConfig() {
        UnifiedChatRequest req = UnifiedChatRequest.builder()
            .model("gemini-1.5-pro")
            .messages(List.of(UnifiedMessage.builder().role(UnifiedMessage.Role.USER).content("hi").build()))
            .config(UnifiedGenerationConfig.builder()
                .maxOutputTokens(1024)
                .topK(40)
                .build())
            .build();

        GenerateContentParameters params = converter.toGeminiRequest(req);

        assertThat(params.config()).isPresent();
        assertThat(params.config().get().topK()).hasValue(40.0f);
    }

    @Test
    void shouldRebuildResponseMimeTypeFromExtensions() {
        UnifiedChatRequest req = UnifiedChatRequest.builder()
            .model("gemini-1.5-pro")
            .messages(List.of(UnifiedMessage.builder().role(UnifiedMessage.Role.USER).content("hi").build()))
            .config(UnifiedGenerationConfig.builder().maxOutputTokens(1024).build())
            .gemini(GeminiExtensions.builder()
                .responseMimeType("application/json")
                .build())
            .build();

        GenerateContentParameters params = converter.toGeminiRequest(req);

        assertThat(params.config()).isPresent();
        assertThat(params.config().get().responseMimeType()).hasValue("application/json");
    }

    @Test
    void shouldRebuildSafetySettingsFromExtensions() {
        ArrayNode safetyArr = mapper.createArrayNode();
        ObjectNode ss = mapper.createObjectNode();
        ss.put("category", "HARM_CATEGORY_HARASSMENT");
        ss.put("threshold", "BLOCK_NONE");
        safetyArr.add(ss);

        UnifiedChatRequest req = UnifiedChatRequest.builder()
            .model("gemini-1.5-pro")
            .messages(List.of(UnifiedMessage.builder().role(UnifiedMessage.Role.USER).content("hi").build()))
            .config(UnifiedGenerationConfig.builder().maxOutputTokens(1024).build())
            .gemini(GeminiExtensions.builder()
                .safetySettings(safetyArr)
                .build())
            .build();

        GenerateContentParameters params = converter.toGeminiRequest(req);

        assertThat(params.config()).isPresent();
        assertThat(params.config().get().safetySettings()).isPresent();
        assertThat(params.config().get().safetySettings().get()).hasSize(1);
        // 验证 category/threshold 被正确解析
        var safetySetting = params.config().get().safetySettings().get().get(0);
        assertThat(safetySetting.category()).isPresent();
        assertThat(safetySetting.threshold()).isPresent();
        assertThat(safetySetting.category().get().toString()).isEqualTo("HARM_CATEGORY_HARASSMENT");
        assertThat(safetySetting.threshold().get().toString()).isEqualTo("BLOCK_NONE");
    }

    @Test
    void shouldRebuildResponseSchemaFromExtensions() {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        schema.put("description", "test schema");

        UnifiedChatRequest req = UnifiedChatRequest.builder()
            .model("gemini-1.5-pro")
            .messages(List.of(UnifiedMessage.builder().role(UnifiedMessage.Role.USER).content("hi").build()))
            .config(UnifiedGenerationConfig.builder().maxOutputTokens(1024).build())
            .gemini(GeminiExtensions.builder()
                .responseSchema(schema)
                .build())
            .build();

        GenerateContentParameters params = converter.toGeminiRequest(req);

        assertThat(params.config()).isPresent();
        assertThat(params.config().get().responseSchema()).isPresent();
        // 验证 Type wrapper 构造 + description 提取
        assertThat(params.config().get().responseSchema().get().type()).isPresent();
        assertThat(params.config().get().responseSchema().get().type().get().toString()).isEqualTo("object");
        assertThat(params.config().get().responseSchema().get().description()).hasValue("test schema");
    }

    @Test
    void shouldNotSetExtensionsFieldsWhenAbsent() {
        UnifiedChatRequest req = UnifiedChatRequest.builder()
            .model("gemini-1.5-pro")
            .messages(List.of(UnifiedMessage.builder().role(UnifiedMessage.Role.USER).content("hi").build()))
            .config(UnifiedGenerationConfig.builder().maxOutputTokens(1024).build())
            .build();

        GenerateContentParameters params = converter.toGeminiRequest(req);

        assertThat(params.config()).isPresent();
        assertThat(params.config().get().topK()).isEmpty();
        assertThat(params.config().get().responseMimeType()).isEmpty();
        assertThat(params.config().get().safetySettings()).isEmpty();
    }

    @Test
    void shouldMapThinkingConfigAndStopSequences() {
        var config = UnifiedGenerationConfig.builder()
            .stopSequences(List.of("END", "STOP"))
            .thinkingConfig(ThinkingConfig.builder()
                .type("enabled")
                .budgetTokens(2048)
                .build())
            .build();
        var uReq = UnifiedChatRequest.builder()
            .model("gemini-pro")
            .messages(List.of(UnifiedMessage.builder()
                .role(UnifiedMessage.Role.USER)
                .content("hi")
                .build()))
            .config(config)
            .stream(false)
            .build();

        var result = converter.toGeminiRequest(uReq);
        var gc = result.config().get();
        assertThat(gc.stopSequences()).hasValue(List.of("END", "STOP"));
        assertThat(gc.thinkingConfig()).isPresent();
        assertThat(gc.thinkingConfig().get().thinkingBudget()).hasValue(2048);
        assertThat(gc.thinkingConfig().get().includeThoughts()).hasValue(true);
    }

    @Test
    void shouldMapGeminiExtensionsToolsGoogleSearch() throws Exception {
        var toolsNode = mapper.readTree("[{\"googleSearch\":{}}]");
        var geminiExt = GeminiExtensions.builder()
            .tools(toolsNode)
            .build();
        var uReq = UnifiedChatRequest.builder()
            .model("gemini-pro")
            .messages(List.of(UnifiedMessage.builder()
                .role(UnifiedMessage.Role.USER)
                .content("搜索最新新闻")
                .build()))
            .gemini(geminiExt)
            .stream(false)
            .build();

        var result = converter.toGeminiRequest(uReq);
        var gc = result.config().get();
        assertThat(gc.tools()).isPresent();
        var tools = gc.tools().get();
        assertThat(tools).isNotEmpty();
    }

    @Test
    void shouldMapPresencePenaltyAndSeed() {
        var config = UnifiedGenerationConfig.builder()
            .presencePenalty(0.5)
            .frequencyPenalty(0.3)
            .seed(42L)
            .build();
        var uReq = UnifiedChatRequest.builder()
            .model("gemini-pro")
            .messages(List.of(UnifiedMessage.builder()
                .role(UnifiedMessage.Role.USER)
                .content("hi")
                .build()))
            .config(config)
            .stream(false)
            .build();

        var result = converter.toGeminiRequest(uReq);
        var gc = result.config().get();
        assertThat(gc.presencePenalty()).hasValue(0.5f);
        assertThat(gc.frequencyPenalty()).hasValue(0.3f);
        assertThat(gc.seed()).hasValue(42);
    }
}
