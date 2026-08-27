package com.ai8493.llmproxy.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;

import com.ai8493.llmproxy.clients.ClientConfigService;

import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ClientConfigControllerTest {

    @Autowired
    private WebTestClient client;
    @Autowired
    private ClientConfigService clientConfigService;

    // 把 homeDir 指向 tempDir，避免写穿开发机真实的 ~/.codex / ~/.gemini
    // 用实例字段（非 static）：每个测试方法获得独立目录，避免写文件用例污染读空目录的断言
    @TempDir
    Path tempDir;

    @BeforeEach
    void overrideHomeDir() {
        clientConfigService.setHomeDirForTest(tempDir.toString());
    }

    private static final Pattern CSRF_META_PATTERN =
        Pattern.compile("<meta name=\"_csrf\" content=\"([^\"]+)\"");

    @Test
    void shouldRedirectUnauthenticatedToLogin() {
        client.get().uri("/admin/clients").exchange()
            .expectStatus().is3xxRedirection()
            .expectHeader().valueMatches("Location", ".*/admin/login");
    }

    @Test
    void shouldListClientsWhenAuthenticated() {
        String session = loginAndGetSession();

        // listClients 返回 HashMap.values()，迭代顺序不保证，用 containsExactlyInAnyOrder 避免顺序耦合
        client.get().uri("/admin/clients/api/clients")
            .cookie("SESSION", session)
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.length()").isEqualTo(3)
            .jsonPath("$[*].id").value(ids -> {
                @SuppressWarnings("unchecked")
                java.util.List<Object> list = (java.util.List<Object>) ids;
                org.assertj.core.api.Assertions.assertThat(list)
                    .containsExactlyInAnyOrder("codex", "gemini-cli", "claude-code");
            });
    }

    @Test
    void shouldReadFileWhenAuthenticated() {
        String session = loginAndGetSession();

        client.get().uri("/admin/clients/api/clients/codex/files/config.toml")
            .cookie("SESSION", session)
            .exchange()
            .expectStatus().isOk()
            .expectHeader().valueEquals("X-File-Exists", "false")
            .expectBody(String.class).value(s -> {
                org.assertj.core.api.Assertions.assertThat(s)
                    .contains("http://localhost:8493")
                    .doesNotContain("[[${");
            });
    }

    @Test
    void shouldWriteFileWhenAuthenticated() {
        String session = loginAndGetSession();
        // 写操作需要 CSRF token：cookie 中放明文 token，X-XSRF-TOKEN header 中放 XOR 编码 token
        // 二者来自同一次 GET /admin/login 响应（Spring Security 6.x 默认对 meta 中的 token 做 XOR 编码）
        String[] tokens = fetchCsrfTokens();

        client.put().uri("/admin/clients/api/clients/codex/files/config.toml")
            .cookie("SESSION", session)
            .cookie("XSRF-TOKEN", tokens[0])
            .header("X-XSRF-TOKEN", tokens[1])
            .contentType(MediaType.TEXT_PLAIN)
            .bodyValue("model = \"integration-test\"")
            .exchange()
            .expectStatus().isOk()
            // PUT 返回补缺后内容
            .expectBody(String.class).value(body -> {
                org.assertj.core.api.Assertions.assertThat(body)
                    .contains("model = \"integration-test\"")
                    .contains("model_reasoning_effort = \"xhigh\"");
            });
        // codex 的 config.toml 需 auth.json 同时存在才视为存在，补写 auth.json 让读回走磁盘内容
        client.put().uri("/admin/clients/api/clients/codex/files/auth.json")
            .cookie("SESSION", session)
            .cookie("XSRF-TOKEN", tokens[0])
            .header("X-XSRF-TOKEN", tokens[1])
            .contentType(MediaType.TEXT_PLAIN)
            .bodyValue("{\"OPENAI_API_KEY\": \"k\"}")
            .exchange()
            .expectStatus().isOk();

        // 读回验证:磁盘内容含补齐字段
        client.get().uri("/admin/clients/api/clients/codex/files/config.toml")
            .cookie("SESSION", session)
            .exchange()
            .expectStatus().isOk()
            .expectBody(String.class).value(body -> {
                org.assertj.core.api.Assertions.assertThat(body)
                    .contains("model = \"integration-test\"")
                    .contains("model_reasoning_effort = \"xhigh\"");
            });
    }

    @Test
    void shouldRejectWriteWithoutCsrfToken() {
        String session = loginAndGetSession();

        client.put().uri("/admin/clients/api/clients/codex/files/config.toml")
            .cookie("SESSION", session)
            .contentType(MediaType.TEXT_PLAIN)
            .bodyValue("test")
            .exchange()
            .expectStatus().isForbidden();
    }

    // 同一次 GET /admin/login 同时拿 cookie 中的明文 token 和 body 中的 XOR 编码 token，
    // 确保二者对应同一个 CSRF token（每次请求生成的 token 不同，不能用两次请求）
    private String[] fetchCsrfTokens() {
        EntityExchangeResult<String> result = client.get().uri("/admin/login").exchange()
            .expectStatus().isOk()
            .expectBody(String.class).returnResult();
        var cookie = result.getResponseCookies().getFirst("XSRF-TOKEN");
        if (cookie == null) {
            throw new IllegalStateException("未获取到 XSRF-TOKEN cookie");
        }
        String body = result.getResponseBody();
        Matcher matcher = CSRF_META_PATTERN.matcher(body);
        if (!matcher.find()) {
            throw new IllegalStateException("未找到 CSRF token meta 标签");
        }
        return new String[]{ cookie.getValue(), matcher.group(1) };
    }

    // 登录并返回 SESSION cookie 值，供后续请求带登录态
    private String loginAndGetSession() {
        String[] tokens = fetchCsrfTokens();
        EntityExchangeResult<String> result = client.post().uri("/admin/login")
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .cookie("XSRF-TOKEN", tokens[0])
            .bodyValue("_csrf=" + tokens[1] + "&username=admin&password=123456")
            .exchange()
            .expectStatus().is3xxRedirection()
            .expectBody(String.class).returnResult();
        var session = result.getResponseCookies().getFirst("SESSION");
        if (session == null) {
            throw new IllegalStateException("登录后未获取到 SESSION cookie");
        }
        return session.getValue();
    }

    @Test
    void shouldApplyDefaultsWhenAuthenticated() {
        String session = loginAndGetSession();
        String[] tokens = fetchCsrfTokens();

        client.post().uri("/admin/clients/api/clients/codex/files/config.toml/apply-defaults")
            .cookie("SESSION", session)
            .cookie("XSRF-TOKEN", tokens[0])
            .header("X-XSRF-TOKEN", tokens[1])
            .exchange()
            .expectStatus().isOk()
            .expectHeader().valueEquals("X-Updated", "true")
            .expectBody(String.class).value(body -> {
                org.assertj.core.api.Assertions.assertThat(body)
                    .contains("http://localhost:8493");
            });
    }

    @Test
    void shouldRejectApplyDefaultsWithoutCsrf() {
        String session = loginAndGetSession();

        // 不带 X-XSRF-TOKEN 头 → 403
        client.post().uri("/admin/clients/api/clients/codex/files/config.toml/apply-defaults")
            .cookie("SESSION", session)
            .exchange()
            .expectStatus().isForbidden();
    }

    @Test
    void shouldReturnFileExistsTrueWhenFileOnDisk() {
        String session = loginAndGetSession();
        String[] tokens = fetchCsrfTokens();

        // PUT 写入 config.toml 与 auth.json，让 codex 的 config.toml 视为存在
        client.put().uri("/admin/clients/api/clients/codex/files/config.toml")
            .cookie("SESSION", session)
            .cookie("XSRF-TOKEN", tokens[0])
            .header("X-XSRF-TOKEN", tokens[1])
            .contentType(MediaType.TEXT_PLAIN)
            .bodyValue("model = \"on-disk\"")
            .exchange()
            .expectStatus().isOk();
        client.put().uri("/admin/clients/api/clients/codex/files/auth.json")
            .cookie("SESSION", session)
            .cookie("XSRF-TOKEN", tokens[0])
            .header("X-XSRF-TOKEN", tokens[1])
            .contentType(MediaType.TEXT_PLAIN)
            .bodyValue("{\"OPENAI_API_KEY\": \"k\"}")
            .exchange()
            .expectStatus().isOk();

        // 再 GET config.toml，断言 X-File-Exists 为 true，内容含磁盘内容 + 补齐字段
        client.get().uri("/admin/clients/api/clients/codex/files/config.toml")
            .cookie("SESSION", session)
            .exchange()
            .expectStatus().isOk()
            .expectHeader().valueEquals("X-File-Exists", "true")
            .expectBody(String.class).value(body -> {
                org.assertj.core.api.Assertions.assertThat(body)
                    .contains("model = \"on-disk\"")
                    .contains("model_reasoning_effort = \"xhigh\"");
            });
    }

    @Test
    void shouldReturnFilledTrueWhenCodexConfigTomlMissingFields() throws Exception {
        String session = loginAndGetSession();

        // 用 Files.writeString 直接写磁盘,绕过 PUT 补缺,让磁盘内容字段不全
        // (PUT writeFile 会触发 fillMissingCodexConfigToml 补缺,无法测出 X-Filled: true 场景)
        java.nio.file.Path cfgDir = tempDir.resolve("missing-fields-cfg/.codex");
        java.nio.file.Files.createDirectories(cfgDir);
        java.nio.file.Files.writeString(cfgDir.resolve("config.toml"), "model = \"on-disk\"");
        java.nio.file.Files.writeString(cfgDir.resolve("auth.json"), "{\"OPENAI_API_KEY\": \"k\"}");
        clientConfigService.setHomeDirForTest(tempDir.resolve("missing-fields-cfg").toString());

        // GET config.toml:磁盘内容字段不全 → 补缺 → X-Filled: true
        client.get().uri("/admin/clients/api/clients/codex/files/config.toml")
            .cookie("SESSION", session)
            .exchange()
            .expectStatus().isOk()
            .expectHeader().valueEquals("X-File-Exists", "true")
            .expectHeader().valueEquals("X-Filled", "true")
            .expectBody(String.class).value(body -> {
                org.assertj.core.api.Assertions.assertThat(body)
                    .contains("model = \"on-disk\"")
                    .contains("model_reasoning_effort = \"xhigh\"");
            });
    }

    @Test
    void shouldReturnFilledFalseWhenCodexConfigTomlComplete() throws Exception {
        String session = loginAndGetSession();
        String[] tokens = fetchCsrfTokens();

        // 预置完整 config.toml + auth.json(通过 Files 写入避免 PUT 触发补缺)
        java.nio.file.Path cfgDir = tempDir.resolve("complete-cfg/.codex");
        java.nio.file.Files.createDirectories(cfgDir);
        java.nio.file.Files.writeString(cfgDir.resolve("config.toml"),
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
        java.nio.file.Files.writeString(cfgDir.resolve("auth.json"), "{\"OPENAI_API_KEY\": \"k\"}");
        clientConfigService.setHomeDirForTest(tempDir.resolve("complete-cfg").toString());

        // GET config.toml:字段齐全 → X-Filled: false
        client.get().uri("/admin/clients/api/clients/codex/files/config.toml")
            .cookie("SESSION", session)
            .exchange()
            .expectStatus().isOk()
            .expectHeader().valueEquals("X-File-Exists", "true")
            .expectHeader().valueEquals("X-Filled", "false");
    }
}
