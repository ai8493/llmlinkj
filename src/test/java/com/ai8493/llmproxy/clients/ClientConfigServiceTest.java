package com.ai8493.llmproxy.clients;

import com.ai8493.llmproxy.clients.ClientConfigService.ApplyResult;
import com.ai8493.llmproxy.config.entity.BackendConfigEntity;
import com.ai8493.llmproxy.config.entity.ProtocolMappingEntity;
import com.ai8493.llmproxy.config.repository.BackendConfigRepository;
import com.ai8493.llmproxy.config.repository.ModelMappingRepository;
import com.ai8493.llmproxy.config.repository.ProtocolMappingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ClientConfigServiceTest {

    @Autowired
    private ClientConfigService service;
    @Autowired private BackendConfigRepository backendRepo;
    @Autowired private ProtocolMappingRepository protocolRepo;
    @Autowired private ModelMappingRepository modelRepo;

    // V6 迁移在 SpringContext 启动时灌入了种子数据（已提交，不在 @Transactional 内，不回滚）。
    // 每个测试方法执行前清空三张表，保证断言基于本方法插入的数据。
    @BeforeEach
    void cleanTables() {
        modelRepo.deleteAll();
        protocolRepo.deleteAll();
        backendRepo.deleteAll();
    }

    @TempDir
    Path tempDir;

    @Test
    void readFile_本地文件存在_返回文件内容与exists为true且filled为false() throws Exception {
        Path cfgDir = tempDir.resolve("logs/cfg/.codex");
        Files.createDirectories(cfgDir);
        // 预置完整模板内容,让补缺 no-op,验证"文件存在且齐全时原样返回"
        Files.writeString(cfgDir.resolve("config.toml"),
            "model_provider = \"llm-proxy\"\n" +
            "model = \"custom\"\n" +
            "model_reasoning_effort = \"xhigh\"\n" +
            "disable_response_storage = true\n" +
            "model_context_window = 1000000\n" +
            "model_auto_compact_token_limit = 900000\n" +
            "\n" +
            "[model_providers.llm-proxy]\n" +
            "name = \"LLM Proxy\"\n" +
            "wire_api = \"responses\"\n" +
            "base_url = \"http://localhost:8493/v1\"\n" +
            "requires_openai_auth = true\n");
        Files.writeString(cfgDir.resolve("auth.json"), "{\"OPENAI_API_KEY\": \"k\"}");

        service.setHomeDirForTest(tempDir.resolve("logs/cfg").toString());

        var result = service.readFile("codex", "config.toml");
        assertThat(result.content()).contains("model = \"custom\"");
        assertThat(result.exists()).isTrue();
        assertThat(result.filled()).isFalse();
    }

    @Test
    void readFile_本地文件不存在_渲染模板并返回exists为false() {
        // 指向空目录，确保 ~/.codex/config.toml 不存在，
        // 触发 readFile 走 renderTemplate 分支（Task 7 的 clientTemplateEngine 渲染）
        service.setHomeDirForTest(tempDir.resolve("empty-cfg").toString());

        var result = service.readFile("codex", "config.toml");
        // 验证：proxyBaseUrl 已替换为实际地址，且 Thymeleaf 表达式 [[${...}]] 已被求值不残留
        assertThat(result.exists()).isFalse();
        assertThat(result.content())
            .contains("http://localhost:8493")
            .doesNotContain("[[${");
    }

    @Test
    void writeFile_目录不存在_自动创建并写入() throws Exception {
        service.setHomeDirForTest(tempDir.resolve("fresh-cfg").toString());

        service.writeFile("codex", "config.toml", "model = \"new\"");

        Path file = tempDir.resolve("fresh-cfg/.codex/config.toml");
        assertThat(Files.exists(file)).isTrue();
        // 补缺后含用户提交的 model 字段 + 模板补齐的其他字段
        assertThat(Files.readString(file)).contains("model = \"new\"");
        assertThat(Files.readString(file)).contains("model_reasoning_effort = \"xhigh\"");
    }

    @Test
    void writeFile_codex的config_toml_用户删了字段_落盘补缺后内容并返回() throws Exception {
        service.setHomeDirForTest(tempDir.resolve("write-fill/.codex").getParent().toString());

        // 用户提交的内容缺 model_reasoning_effort
        String userContent =
            "model_provider = \"llm-proxy\"\n" +
            "model = \"custom\"\n" +
            "disable_response_storage = true\n" +
            "model_context_window = 1000000\n" +
            "model_auto_compact_token_limit = 900000\n" +
            "\n" +
            "[model_providers.llm-proxy]\n" +
            "name = \"LLM Proxy\"\n" +
            "wire_api = \"responses\"\n" +
            "base_url = \"http://localhost:8493/v1\"\n" +
            "requires_openai_auth = true\n";

        var result = service.writeFile("codex", "config.toml", userContent);

        // 返回内容含补齐字段
        assertThat(result.content()).contains("model_reasoning_effort = \"xhigh\"");
        // 落盘内容与返回内容一致
        Path file = tempDir.resolve("write-fill/.codex/config.toml");
        assertThat(Files.readString(file)).isEqualTo(result.content());
    }

    @Test
    void readFile_文件名不在白名单_抛IllegalArgumentException() {
        service.setHomeDirForTest(tempDir.resolve("safe-cfg").toString());

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                service.readFile("codex", "../../../etc/passwd"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("文件不在白名单");
    }

    @Test
    void readFile_客户端Id不存在_抛IllegalArgumentException() {
        service.setHomeDirForTest(tempDir.resolve("safe-cfg").toString());

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                service.readFile("unknown-client", "config.toml"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("客户端不存在");
    }

    // writeFile 与 readFile 共用同一套白名单校验，安全相关路径必须双向覆盖
    @Test
    void writeFile_文件名不在白名单_抛IllegalArgumentException() {
        service.setHomeDirForTest(tempDir.resolve("safe-cfg").toString());

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                service.writeFile("codex", "../../../etc/passwd", "malicious"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("文件不在白名单");
    }

    @Test
    void writeFile_客户端Id不存在_抛IllegalArgumentException() {
        service.setHomeDirForTest(tempDir.resolve("safe-cfg").toString());

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                service.writeFile("unknown-client", "config.toml", "content"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("客户端不存在");
    }

    @Test
    void readFile_codex的config_toml_仅有config无auth_json_exists为false() throws Exception {
        Path cfgDir = tempDir.resolve("cfg-only/.codex");
        Files.createDirectories(cfgDir);
        Files.writeString(cfgDir.resolve("config.toml"), "model = \"custom\"");

        service.setHomeDirForTest(tempDir.resolve("cfg-only").toString());

        var result = service.readFile("codex", "config.toml");
        // auth.json 缺失 → config.toml 视为不存在,走模板渲染
        assertThat(result.exists()).isFalse();
        assertThat(result.content())
            .contains("http://localhost:8493")
            .doesNotContain("model = \"custom\"");
    }

    @Test
    void readFile_codex的config_toml_同时有config和auth_json_exists为true且补缺后filled为true() throws Exception {
        Path cfgDir = tempDir.resolve("both/.codex");
        Files.createDirectories(cfgDir);
        Files.writeString(cfgDir.resolve("config.toml"), "model = \"custom\"");
        Files.writeString(cfgDir.resolve("auth.json"), "{\"OPENAI_API_KEY\": \"k\"}");

        service.setHomeDirForTest(tempDir.resolve("both").toString());

        var result = service.readFile("codex", "config.toml");
        assertThat(result.exists()).isTrue();
        assertThat(result.filled()).isTrue();
        assertThat(result.content()).contains("model = \"custom\"");
    }

    @Test
    void readFile_codex的config_toml_缺顶层字段_filled为true且补到section前() throws Exception {
        Path cfgDir = tempDir.resolve("miss-top/.codex");
        Files.createDirectories(cfgDir);
        // 缺 model_reasoning_effort 和 disable_response_storage
        Files.writeString(cfgDir.resolve("config.toml"),
            "model_provider = \"llm-proxy\"\n" +
            "model = \"custom\"\n" +
            "model_context_window = 1000000\n" +
            "model_auto_compact_token_limit = 900000\n" +
            "\n" +
            "[model_providers.llm-proxy]\n" +
            "name = \"LLM Proxy\"\n" +
            "wire_api = \"responses\"\n" +
            "base_url = \"http://localhost:8493/v1\"\n" +
            "requires_openai_auth = true\n");
        Files.writeString(cfgDir.resolve("auth.json"), "{\"OPENAI_API_KEY\": \"k\"}");

        service.setHomeDirForTest(tempDir.resolve("miss-top").toString());

        var result = service.readFile("codex", "config.toml");
        assertThat(result.exists()).isTrue();
        assertThat(result.filled()).isTrue();
        // 用户原有字段保留
        assertThat(result.content()).contains("model = \"custom\"");
        // 缺失字段补在 [model_providers.llm-proxy] 之前
        int idxMissing = result.content().indexOf("model_reasoning_effort");
        int idxSection = result.content().indexOf("[model_providers.llm-proxy]");
        assertThat(idxMissing).isGreaterThan(0);
        assertThat(idxSection).isGreaterThan(idxMissing);
        assertThat(result.content()).contains("disable_response_storage = true");
    }

    @Test
    void readFile_codex的config_toml_缺section字段_filled为true且补到section末尾() throws Exception {
        Path cfgDir = tempDir.resolve("miss-section-field/.codex");
        Files.createDirectories(cfgDir);
        // section 存在但缺 wire_api 和 requires_openai_auth
        Files.writeString(cfgDir.resolve("config.toml"),
            "model_provider = \"llm-proxy\"\n" +
            "model = \"custom\"\n" +
            "model_reasoning_effort = \"xhigh\"\n" +
            "disable_response_storage = true\n" +
            "model_context_window = 1000000\n" +
            "model_auto_compact_token_limit = 900000\n" +
            "\n" +
            "[model_providers.llm-proxy]\n" +
            "name = \"LLM Proxy\"\n" +
            "base_url = \"http://localhost:8493/v1\"\n");
        Files.writeString(cfgDir.resolve("auth.json"), "{\"OPENAI_API_KEY\": \"k\"}");

        service.setHomeDirForTest(tempDir.resolve("miss-section-field").toString());

        var result = service.readFile("codex", "config.toml");
        assertThat(result.exists()).isTrue();
        assertThat(result.filled()).isTrue();
        // 缺失字段补在 section 末尾
        assertThat(result.content()).contains("wire_api = \"responses\"");
        assertThat(result.content()).contains("requires_openai_auth = true");
        // 补的字段在 base_url 之后( section 末尾)
        int idxBaseUrl = result.content().indexOf("base_url");
        int idxWireApi = result.content().indexOf("wire_api");
        assertThat(idxWireApi).isGreaterThan(idxBaseUrl);
    }

    @Test
    void readFile_codex的config_toml_缺整个section_filled为true且补到末尾() throws Exception {
        Path cfgDir = tempDir.resolve("miss-whole-section/.codex");
        Files.createDirectories(cfgDir);
        // 仅有顶层字段,整个 [model_providers.llm-proxy] section 缺失
        Files.writeString(cfgDir.resolve("config.toml"),
            "model_provider = \"llm-proxy\"\n" +
            "model = \"custom\"\n" +
            "model_reasoning_effort = \"xhigh\"\n" +
            "disable_response_storage = true\n" +
            "model_context_window = 1000000\n" +
            "model_auto_compact_token_limit = 900000\n");
        Files.writeString(cfgDir.resolve("auth.json"), "{\"OPENAI_API_KEY\": \"k\"}");

        service.setHomeDirForTest(tempDir.resolve("miss-whole-section").toString());

        var result = service.readFile("codex", "config.toml");
        assertThat(result.exists()).isTrue();
        assertThat(result.filled()).isTrue();
        // section 头补在文件末尾
        assertThat(result.content()).contains("[model_providers.llm-proxy]");
        assertThat(result.content()).contains("name = \"LLM Proxy\"");
        assertThat(result.content()).contains("wire_api = \"responses\"");
        assertThat(result.content()).contains("base_url = \"http://localhost:8493/v1\"");
        assertThat(result.content()).contains("requires_openai_auth = true");
        // section 头在顶层字段之后
        int idxTopField = result.content().indexOf("model_auto_compact_token_limit");
        int idxSection = result.content().indexOf("[model_providers.llm-proxy]");
        assertThat(idxSection).isGreaterThan(idxTopField);
    }

    @Test
    void readFile_codex的config_toml_字段全齐_filled为false() throws Exception {
        Path cfgDir = tempDir.resolve("all-present/.codex");
        Files.createDirectories(cfgDir);
        Files.writeString(cfgDir.resolve("config.toml"),
            "model_provider = \"llm-proxy\"\n" +
            "model = \"custom\"\n" +
            "model_reasoning_effort = \"xhigh\"\n" +
            "disable_response_storage = true\n" +
            "model_context_window = 1000000\n" +
            "model_auto_compact_token_limit = 900000\n" +
            "\n" +
            "[model_providers.llm-proxy]\n" +
            "name = \"LLM Proxy\"\n" +
            "wire_api = \"responses\"\n" +
            "base_url = \"http://localhost:8493/v1\"\n" +
            "requires_openai_auth = true\n");
        Files.writeString(cfgDir.resolve("auth.json"), "{\"OPENAI_API_KEY\": \"k\"}");

        service.setHomeDirForTest(tempDir.resolve("all-present").toString());

        var result = service.readFile("codex", "config.toml");
        assertThat(result.exists()).isTrue();
        assertThat(result.filled()).isFalse();
        assertThat(result.content()).doesNotContain("model_reasoning_effort = \"xhigh\"\nmodel_reasoning_effort");
    }

    @Test
    void readFile_codex的config_toml_字段被注释_不补活动行_filled为false() throws Exception {
        Path cfgDir = tempDir.resolve("commented/.codex");
        Files.createDirectories(cfgDir);
        // model 被注释,其他字段齐全
        Files.writeString(cfgDir.resolve("config.toml"),
            "model_provider = \"llm-proxy\"\n" +
            "# model = \"custom\"\n" +
            "model_reasoning_effort = \"xhigh\"\n" +
            "disable_response_storage = true\n" +
            "model_context_window = 1000000\n" +
            "model_auto_compact_token_limit = 900000\n" +
            "\n" +
            "[model_providers.llm-proxy]\n" +
            "name = \"LLM Proxy\"\n" +
            "wire_api = \"responses\"\n" +
            "base_url = \"http://localhost:8493/v1\"\n" +
            "requires_openai_auth = true\n");
        Files.writeString(cfgDir.resolve("auth.json"), "{\"OPENAI_API_KEY\": \"k\"}");

        service.setHomeDirForTest(tempDir.resolve("commented").toString());

        var result = service.readFile("codex", "config.toml");
        assertThat(result.filled()).isFalse();
        // 不补活动 model 行
        assertThat(result.content()).doesNotContain("\nmodel = \"");
        assertThat(result.content()).contains("# model = \"custom\"");
    }

    @Test
    void readFile_codex的auth_json_不补缺_filled为false() throws Exception {
        Path cfgDir = tempDir.resolve("auth-no-fill/.codex");
        Files.createDirectories(cfgDir);
        Files.writeString(cfgDir.resolve("auth.json"), "{\"OPENAI_API_KEY\": \"k\"}");

        service.setHomeDirForTest(tempDir.resolve("auth-no-fill").toString());

        var result = service.readFile("codex", "auth.json");
        assertThat(result.exists()).isTrue();
        assertThat(result.filled()).isFalse();
        assertThat(result.content()).isEqualTo("{\"OPENAI_API_KEY\": \"k\"}");
    }

    @Test
    void readFile_gemini的env_不补缺_filled为false() throws Exception {
        Path cfgDir = tempDir.resolve("env-no-fill/.gemini");
        Files.createDirectories(cfgDir);
        Files.writeString(cfgDir.resolve(".env"), "GEMINI_API_KEY=k\n");

        service.setHomeDirForTest(tempDir.resolve("env-no-fill").toString());

        var result = service.readFile("gemini-cli", ".env");
        assertThat(result.exists()).isTrue();
        assertThat(result.filled()).isFalse();
        assertThat(result.content()).isEqualTo("GEMINI_API_KEY=k\n");
    }

    @Test
    void readFile_codex的auth_json_仅自身存在_exists为true() throws Exception {
        // 验证耦合单向:auth.json 自身存在性不受 config.toml 影响
        Path cfgDir = tempDir.resolve("auth-only/.codex");
        Files.createDirectories(cfgDir);
        Files.writeString(cfgDir.resolve("auth.json"), "{\"OPENAI_API_KEY\": \"k\"}");

        service.setHomeDirForTest(tempDir.resolve("auth-only").toString());

        var result = service.readFile("codex", "auth.json");
        assertThat(result.exists()).isTrue();
        assertThat(result.content()).isEqualTo("{\"OPENAI_API_KEY\": \"k\"}");
    }

    @Test
    void readFile_codex的auth_json_渲染含apiKey且不残留模板表达式() {
        service.setHomeDirForTest(tempDir.resolve("auth-cfg").toString());

        var result = service.readFile("codex", "auth.json");
        // 输出必须是合法 JSON,值两侧只能有一对引号
        assertThat(result.content())
            .contains("OPENAI_API_KEY")
            .doesNotContain("[[${");
        assertThat(result.content().trim())
            .isEqualTo("{\n  \"OPENAI_API_KEY\": \"123456\"\n}");
    }

    @Test
    void readFile_codex的config_toml_含新字段且不含env_key() {
        service.setHomeDirForTest(tempDir.resolve("toml-cfg").toString());

        var result = service.readFile("codex", "config.toml");
        assertThat(result.content())
            .contains("model_reasoning_effort = \"xhigh\"")
            .contains("disable_response_storage = true")
            .contains("model_context_window = 1000000")
            .contains("model_auto_compact_token_limit = 900000")
            .contains("requires_openai_auth = true")
            .contains("wire_api = \"responses\"")
            .doesNotContain("env_key");
    }

    @Test
    void readFile_codex的config_toml_渲染含后端默认模型名() {
        // 数据库插入一条 responses 协议映射 + 对应后端
        backendRepo.save(new BackendConfigEntity(
            "deepseek", "responses", "k", "u", "deepseek-v3", null,
            5, 5, 5, 5, 60, "2026-06-01T00:00:00Z"));
        protocolRepo.save(new ProtocolMappingEntity(
            "responses", "deepseek", true, "2026-06-01T00:00:00Z", null));

        service.setHomeDirForTest(tempDir.resolve("model-cfg").toString());

        var result = service.readFile("codex", "config.toml");
        assertThat(result.content()).contains("model = \"deepseek-v3\"");
        assertThat(result.content()).doesNotContain("model = \"\"deepseek-v3\"\"");
    }

    @Test
    void readFile_codex的config_toml_无后端配置时model为空字符串() {
        // 数据库无 responses 协议映射，defaultModel 返回空字符串
        service.setHomeDirForTest(tempDir.resolve("empty-model-cfg").toString());

        var result = service.readFile("codex", "config.toml");
        assertThat(result.content()).contains("model = \"\"");
    }

    @Test
    void readFile_gemini的env_渲染含后端默认模型名() {
        // 数据库插入一条 gemini 协议映射 + 对应后端
        backendRepo.save(new BackendConfigEntity(
            "gemini", "gemini", "k", "u", "gemini-2.0-flash-exp", null,
            5, 5, 5, 5, 60, "2026-06-01T00:00:00Z"));
        protocolRepo.save(new ProtocolMappingEntity(
            "gemini", "gemini", true, "2026-06-01T00:00:00Z", null));

        service.setHomeDirForTest(tempDir.resolve("gemini-env-cfg").toString());

        var result = service.readFile("gemini-cli", ".env");
        assertThat(result.content()).contains("GEMINI_MODEL=gemini-2.0-flash-exp");
    }

    @Test
    void listClients_返回所有已注册客户端() {
        service.setHomeDirForTest(tempDir.resolve("list-cfg").toString());
        var clients = service.listClients();

        assertThat(clients).hasSize(2);
        assertThat(clients).extracting(ClientInfo::id)
            .containsExactlyInAnyOrder("codex", "gemini-cli");
        assertThat(clients).extracting(ClientInfo::displayName)
            .containsExactlyInAnyOrder("Codex", "Gemini CLI");

        ClientInfo codex = clients.stream()
            .filter(c -> c.id().equals("codex")).findFirst().orElseThrow();
        assertThat(codex.files()).hasSize(2);
        assertThat(codex.files()).extracting(ClientInfo.FileMeta::filename)
            .containsExactly("config.toml", "auth.json");
        assertThat(codex.files()).extracting(ClientInfo.FileMeta::language)
            .containsExactly("toml", "json");
        // absolutePath = homeDir/configSubdir/filename 拼成的绝对路径
        assertThat(codex.files().get(0).absolutePath())
            .isEqualTo(tempDir.resolve("list-cfg").resolve(".codex").resolve("config.toml")
                .toAbsolutePath().toString());
        assertThat(codex.files().get(1).absolutePath())
            .isEqualTo(tempDir.resolve("list-cfg").resolve(".codex").resolve("auth.json")
                .toAbsolutePath().toString());
    }

    @Test
    void applyProxyDefaults_文件存在_替换字段并写回() throws Exception {
        // 数据库插入 responses 协议映射 + 后端，提供 defaultModel
        backendRepo.save(new BackendConfigEntity(
            "deepseek", "responses", "k", "u", "deepseek-v3", null,
            5, 5, 5, 5, 60, "2026-06-01T00:00:00Z"));
        protocolRepo.save(new ProtocolMappingEntity(
            "responses", "deepseek", true, "2026-06-01T00:00:00Z", null));

        // 预置 config.toml(含旧 base_url)与 auth.json(让 codex 的 config.toml 视为存在)
        service.setHomeDirForTest(tempDir.resolve("apply-cfg").toString());
        Path cfgDir = tempDir.resolve("apply-cfg/.codex");
        Files.createDirectories(cfgDir);
        Files.writeString(cfgDir.resolve("config.toml"),
            "model = \"old\"\n\n[model_providers.llm-proxy]\nbase_url = \"https://old.example.com/v1\"\n");
        Files.writeString(cfgDir.resolve("auth.json"), "{\"OPENAI_API_KEY\": \"k\"}");

        ApplyResult result = service.applyProxyDefaults("codex", "config.toml");

        assertThat(result.updated()).isTrue();
        assertThat(result.content())
            .contains("model = \"deepseek-v3\"")
            .contains("base_url = \"http://localhost:8493/v1\"")
            .doesNotContain("old.example.com");
        // 已写回磁盘
        assertThat(Files.readString(cfgDir.resolve("config.toml")))
            .contains("deepseek-v3");
    }

    @Test
    void applyProxyDefaults_codex的config_toml_用户删了model行_先补再替换model值() throws Exception {
        // 数据库插入 responses 协议映射 + 后端，提供 defaultModel
        backendRepo.save(new BackendConfigEntity(
            "deepseek", "responses", "k", "u", "deepseek-v3", null,
            5, 5, 5, 5, 60, "2026-06-01T00:00:00Z"));
        protocolRepo.save(new ProtocolMappingEntity(
            "responses", "deepseek", true, "2026-06-01T00:00:00Z", null));

        service.setHomeDirForTest(tempDir.resolve("apply-miss-model").toString());
        Path cfgDir = tempDir.resolve("apply-miss-model/.codex");
        Files.createDirectories(cfgDir);
        // 用户删了 model 行(其余字段齐全)
        Files.writeString(cfgDir.resolve("config.toml"),
            "model_provider = \"llm-proxy\"\n" +
            "model_reasoning_effort = \"xhigh\"\n" +
            "disable_response_storage = true\n" +
            "model_context_window = 1000000\n" +
            "model_auto_compact_token_limit = 900000\n" +
            "\n" +
            "[model_providers.llm-proxy]\n" +
            "name = \"LLM Proxy\"\n" +
            "wire_api = \"responses\"\n" +
            "base_url = \"https://old.example.com/v1\"\n" +
            "requires_openai_auth = true\n");
        Files.writeString(cfgDir.resolve("auth.json"), "{\"OPENAI_API_KEY\": \"k\"}");

        ApplyResult result = service.applyProxyDefaults("codex", "config.toml");

        // readOrRender 补缺 model 行 → applyProxyDefaults 替换 model 值为 defaultModel
        assertThat(result.updated()).isTrue();
        assertThat(result.content()).contains("model = \"deepseek-v3\"");
        // base_url 被替换为代理地址
        assertThat(result.content()).contains("base_url = \"http://localhost:8493/v1\"");
        assertThat(result.content()).doesNotContain("old.example.com");
        // 落盘内容一致
        assertThat(Files.readString(cfgDir.resolve("config.toml")))
            .contains("model = \"deepseek-v3\"");
    }

    @Test
    void applyProxyDefaults_文件不存在_走模板渲染生成() {
        service.setHomeDirForTest(tempDir.resolve("apply-empty-cfg").toString());

        ApplyResult result = service.applyProxyDefaults("codex", "config.toml");

        // 文件不存在 → renderTemplate 生成，含代理字段 → updated=true
        assertThat(result.updated()).isTrue();
        assertThat(result.content()).contains("http://localhost:8493");
    }

    @Test
    void applyProxyDefaults_文件无代理字段_返回原内容且updated为false() throws Exception {
        // settings.json 无可更新字段
        service.setHomeDirForTest(tempDir.resolve("apply-settings-cfg").toString());
        Path cfgDir = tempDir.resolve("apply-settings-cfg/.gemini");
        Files.createDirectories(cfgDir);
        Files.writeString(cfgDir.resolve("settings.json"), "{\"ui\":{\"errorVerbosity\":\"full\"}}");

        ApplyResult result = service.applyProxyDefaults("gemini-cli", "settings.json");

        assertThat(result.updated()).isFalse();
        assertThat(result.content()).contains("errorVerbosity");
    }

    @Test
    void applyProxyDefaults_文件名不在白名单_抛IllegalArgumentException() {
        service.setHomeDirForTest(tempDir.resolve("apply-safe-cfg").toString());

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                service.applyProxyDefaults("codex", "../../../etc/passwd"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("文件不在白名单");
    }
}
