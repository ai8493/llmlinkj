package com.ai8493.llmproxy.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class AdminControllerTest {

    @Autowired
    private WebTestClient client;

    private static final Pattern CSRF_META_PATTERN =
        Pattern.compile("<meta name=\"_csrf\" content=\"([^\"]+)\"");

    @Test
    void shouldReturnLoginPage() {
        client.get().uri("/admin/login").exchange()
            .expectStatus().isOk()
            .expectBody(String.class).value(s -> s.contains("登录"));
    }

    // 直接访问 /admin/login（无 query 参数）时，不应渲染任何提示
    @Test
    void shouldNotShowPromptOnDirectVisit() {
        client.get().uri("/admin/login").exchange()
            .expectStatus().isOk()
            .expectBody(String.class).value(s -> {
                org.assertj.core.api.Assertions.assertThat(s)
                    .doesNotContain("用户名或密码错误")
                    .doesNotContain("已登出");
            });
    }

    @Test
    void shouldShowErrorPromptWhenErrorParam() {
        client.get().uri("/admin/login?error").exchange()
            .expectStatus().isOk()
            .expectBody(String.class).value(s -> {
                org.assertj.core.api.Assertions.assertThat(s)
                    .contains("用户名或密码错误")
                    .doesNotContain("已登出");
            });
    }

    @Test
    void shouldShowLogoutPromptWhenLogoutParam() {
        client.get().uri("/admin/login?logout").exchange()
            .expectStatus().isOk()
            .expectBody(String.class).value(s -> {
                org.assertj.core.api.Assertions.assertThat(s)
                    .doesNotContain("用户名或密码错误")
                    .contains("已登出");
            });
    }

    @Test
    void shouldRedirectUnauthenticatedToLogin() {
        client.get().uri("/admin/api/backends").exchange()
            .expectStatus().is3xxRedirection()
            .expectHeader().valueMatches("Location", ".*/admin/login");
    }

    @Test
    void shouldRedirectToAdminAfterLoginSuccess() {
        String[] tokens = fetchCsrfTokens();
        String cookieToken = tokens[0];
        String encodedToken = tokens[1];

        client.post().uri("/admin/login")
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .cookie("XSRF-TOKEN", cookieToken)
            .header("X-XSRF-TOKEN", encodedToken)
            .bodyValue("username=admin&password=123456")
            .exchange()
            .expectStatus().is3xxRedirection()
            .expectHeader().valueMatches("Location", ".*/admin$");
    }

    // 模拟浏览器表单提交：_csrf 字段放在 form body 中（来自 login.html 的 hidden input），
    // 不通过 X-XSRF-TOKEN header 提交。这是真实浏览器的提交路径。
    @Test
    void shouldLoginViaFormEncodedCsrfToken() {
        String[] tokens = fetchCsrfTokens();
        String cookieToken = tokens[0];
        String encodedToken = tokens[1];

        client.post().uri("/admin/login")
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .cookie("XSRF-TOKEN", cookieToken)
            .bodyValue("_csrf=" + encodedToken + "&username=admin&password=123456")
            .exchange()
            .expectStatus().is3xxRedirection()
            .expectHeader().valueMatches("Location", ".*/admin$");
    }

    @Test
    void shouldRejectLoginWithWrongPassword() {
        String[] tokens = fetchCsrfTokens();
        String cookieToken = tokens[0];
        String encodedToken = tokens[1];

        client.post().uri("/admin/login")
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .cookie("XSRF-TOKEN", cookieToken)
            .header("X-XSRF-TOKEN", encodedToken)
            .bodyValue("username=admin&password=wrong")
            .exchange()
            .expectStatus().is3xxRedirection()
            .expectHeader().valueMatches("Location", ".*/admin/login.*error");
    }

    // 修复前：默认 logoutSuccessHandler 重定向到 /login?logout，无 /login 映射
    // → NoResourceFoundException 被 GlobalExceptionHandler 包装成 500
    // 修复后：显式指向 /admin/login?logout
    @Test
    void shouldRedirectToLoginAfterLogout() {
        String[] tokens = fetchCsrfTokens();
        String cookieToken = tokens[0];
        String encodedToken = tokens[1];

        client.post().uri("/admin/logout")
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .cookie("XSRF-TOKEN", cookieToken)
            .bodyValue("_csrf=" + encodedToken)
            .exchange()
            .expectStatus().is3xxRedirection()
            .expectHeader().valueMatches("Location", ".*/admin/login\\?logout");
    }

    // 根路径 / 无 Controller 映射，WebFlux 尝试找静态资源失败 → NoResourceFoundException
    // 修复后：IndexController 映射 GET / 重定向到 /admin（未登录会被 SecurityConfig 跳到 /admin/login）
    @Test
    void shouldRedirectRootToAdmin() {
        client.get().uri("/").exchange()
            .expectStatus().is3xxRedirection()
            .expectHeader().valueMatches("Location", ".*/admin");
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
            throw new IllegalStateException("未从 /admin/login 响应 body 中找到 CSRF token meta 标签");
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
    void shouldReturnPagedBackendsWhenAuthenticated() {
        String session = loginAndGetSession();

        client.get().uri("/admin/api/backends?page=0&size=2")
            .cookie("SESSION", session)
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.content").isArray()
            .jsonPath("$.totalElements").isNumber()
            .jsonPath("$.totalPages").isNumber()
            .jsonPath("$.size").isEqualTo(2)
            .jsonPath("$.number").isEqualTo(0);
    }

    @Test
    void shouldReturnPagedProtocolsWhenAuthenticated() {
        String session = loginAndGetSession();

        client.get().uri("/admin/api/protocols?page=0&size=2")
            .cookie("SESSION", session)
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.content").isArray()
            .jsonPath("$.totalElements").isNumber()
            .jsonPath("$.totalPages").isNumber()
            .jsonPath("$.size").isEqualTo(2)
            .jsonPath("$.number").isEqualTo(0);
    }
}


