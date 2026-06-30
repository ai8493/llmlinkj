package com.ai8493.llmproxy.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.userdetails.MapReactiveUserDetailsService;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.authentication.RedirectServerAuthenticationSuccessHandler;
import org.springframework.security.web.server.authentication.logout.RedirectServerLogoutSuccessHandler;
import org.springframework.security.web.server.csrf.CookieServerCsrfTokenRepository;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatcher.MatchResult;

import java.net.URI;

@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    @Bean
    SecurityWebFilterChain adminChain(ServerHttpSecurity http) throws Exception {
        // 默认 logoutSuccessHandler 会重定向到 /login?logout，但项目无 /login 映射，
        // 会触发 NoResourceFoundException 被 GlobalExceptionHandler 包装成 500 JSON。
        // 显式指向 /admin/login?logout，与 login.html 的 ${param.logout} 提示对应。
        RedirectServerLogoutSuccessHandler logoutSuccessHandler = new RedirectServerLogoutSuccessHandler();
        logoutSuccessHandler.setLogoutSuccessUrl(URI.create("/admin/login?logout"));

        http.authorizeExchange(a -> a
                .pathMatchers("/admin/login", "/admin/api/login", "/admin/app.js", "/admin/app.css", "/static/**", "/webjars/**").permitAll()
                .pathMatchers("/admin/**").authenticated()
                .anyExchange().permitAll())
            .formLogin(f -> f.loginPage("/admin/login")
                             .authenticationSuccessHandler(
                                 new RedirectServerAuthenticationSuccessHandler("/admin")))
            .logout(l -> l.logoutUrl("/admin/logout").logoutSuccessHandler(logoutSuccessHandler))
            // CSRF 仅对 /admin/** 的非安全方法校验；代理 API（/v1/**、/v1beta/**）不走 CSRF
            .csrf(c -> c
                .csrfTokenRepository(CookieServerCsrfTokenRepository.withHttpOnlyFalse())
                .requireCsrfProtectionMatcher(exchange -> {
                    var method = exchange.getRequest().getMethod();
                    boolean isSafe = method == HttpMethod.GET || method == HttpMethod.HEAD
                        || method == HttpMethod.TRACE || method == HttpMethod.OPTIONS;
                    String path = exchange.getRequest().getPath().pathWithinApplication().value();
                    return !isSafe && path.startsWith("/admin/")
                        ? MatchResult.match()
                        : MatchResult.notMatch();
                }));
        return http.build();
    }

    @Bean
    MapReactiveUserDetailsService adminUsers(
        @Value("${admin.username:admin}") String username,
        @Value("${admin.password:change-me}") String password) {
        UserDetails user = User.withUsername(username)
                                .password(passwordEncoder().encode(password))
                                .roles("ADMIN").build();
        return new MapReactiveUserDetailsService(user);
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
