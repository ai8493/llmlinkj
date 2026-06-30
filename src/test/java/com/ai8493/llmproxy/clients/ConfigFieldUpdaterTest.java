package com.ai8493.llmproxy.clients;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ConfigFieldUpdaterTest {

    private final ConfigFieldUpdater updater = new ConfigFieldUpdater();

    @Test
    void updateToml_替换顶层model和section下base_url_其他行保留() {
        String content = """
            model = "old-model"
            model_reasoning_effort = "xhigh"

            [model_providers.llm-proxy]
            name = "LLM Proxy"
            base_url = "https://old.example.com/v1"
            wire_api = "responses"
            """;
        List<UpdatableField> fields = List.of(
            new UpdatableField("config.toml", "model", "defaultModel", ""),
            new UpdatableField("config.toml", "model_providers.llm-proxy.base_url", "proxyBaseUrl", "/v1"));
        Map<String, String> vars = Map.of(
            "defaultModel", "deepseek-v3",
            "proxyBaseUrl", "http://localhost:8493");

        String result = updater.update(content, "toml", fields, vars);

        assertThat(result)
            .contains("model = \"deepseek-v3\"")
            .contains("base_url = \"http://localhost:8493/v1\"")
            .contains("model_reasoning_effort = \"xhigh\"")
            .contains("wire_api = \"responses\"")
            .doesNotContain("old-model")
            .doesNotContain("old.example.com");
    }

    @Test
    void updateToml_字段被注释或不存在时不替换不新增() {
        String content = """
            # model = "commented"
            [model_providers.llm-proxy]
            name = "LLM Proxy"
            """;
        List<UpdatableField> fields = List.of(
            new UpdatableField("config.toml", "model", "defaultModel", ""),
            new UpdatableField("config.toml", "model_providers.llm-proxy.base_url", "proxyBaseUrl", "/v1"));
        Map<String, String> vars = Map.of("defaultModel", "m", "proxyBaseUrl", "http://x");

        String result = updater.update(content, "toml", fields, vars);

        // model 行被注释，base_url 行不存在 → 原样返回，不新增
        assertThat(result).isEqualTo(content);
    }

    @Test
    void updateJson_替换OPENAI_API_KEY值() {
        String content = "{\n  \"OPENAI_API_KEY\": \"old-key\"\n}";
        List<UpdatableField> fields = List.of(
            new UpdatableField("auth.json", "/OPENAI_API_KEY", "apiKey", ""));
        Map<String, String> vars = Map.of("apiKey", "new-key");

        String result = updater.update(content, "json", fields, vars);

        assertThat(result).contains("\"OPENAI_API_KEY\" : \"new-key\"");
        assertThat(result).doesNotContain("old-key");
    }

    @Test
    void updateJson_字段不存在时不新增() {
        String content = "{}";
        List<UpdatableField> fields = List.of(
            new UpdatableField("auth.json", "/OPENAI_API_KEY", "apiKey", ""));
        Map<String, String> vars = Map.of("apiKey", "new-key");

        String result = updater.update(content, "json", fields, vars);

        // missing node → 不新增，返回规整化后的内容（Jackson 重写空对象为 "{}"）
        assertThat(result).doesNotContain("OPENAI_API_KEY");
    }

    @Test
    void updateIni_替换三个KEY_注释行保留() {
        String content = """
            GEMINI_API_KEY=old-key
            GEMINI_MODEL=old-model
            # 注释行保留
            GOOGLE_GEMINI_BASE_URL=https://old.example.com
            """;
        List<UpdatableField> fields = List.of(
            new UpdatableField(".env", "GEMINI_API_KEY", "apiKey", ""),
            new UpdatableField(".env", "GEMINI_MODEL", "defaultModel", ""),
            new UpdatableField(".env", "GOOGLE_GEMINI_BASE_URL", "proxyBaseUrl", ""));
        Map<String, String> vars = Map.of(
            "apiKey", "new-key",
            "defaultModel", "gemini-2.5-pro",
            "proxyBaseUrl", "http://localhost:8493");

        String result = updater.update(content, "ini", fields, vars);

        assertThat(result)
            .contains("GEMINI_API_KEY=new-key")
            .contains("GEMINI_MODEL=gemini-2.5-pro")
            .contains("GOOGLE_GEMINI_BASE_URL=http://localhost:8493")
            .contains("# 注释行保留");
    }

    @Test
    void update_不支持的language抛IllegalStateException() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                updater.update("x", "yaml", List.of(), Map.of()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("不支持的文件类型");
    }
}
