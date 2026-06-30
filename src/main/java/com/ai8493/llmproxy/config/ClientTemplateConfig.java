package com.ai8493.llmproxy.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

// 专门用于渲染客户端配置文件（.toml / .json）的 Thymeleaf 引擎。
// 与 Spring Boot 自动配置的 TemplateEngine（渲染 .html 页面）隔离，
// 避免互相干扰后缀映射和模板模式。
@Configuration
public class ClientTemplateConfig {

    @Bean(name = "clientTemplateEngine")
    public SpringTemplateEngine clientTemplateEngine() {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        // 模板前缀：resources/templates/ 下的文件按 templatePath 解析
        // 但 ClientFile.templatePath 已含 "clients/codex/config" 完整路径，
        // 所以前缀设为 "templates/"（classpath 根），由 templatePath 提供子路径
        resolver.setPrefix("templates/");
        // templatePath 不含文件后缀（如 "clients/codex/config"），
        // ClientConfigService 会拼上后缀（如 ".toml"）传入 templateEngine.process()，
        // 因此 resolver 的 suffix 必须为空，不强制追加任何后缀。
        resolver.setSuffix("");
        resolver.setTemplateMode(TemplateMode.TEXT);
        // 强制 TEXT 模式:否则 .json 扩展名会被 Thymeleaf 自动识别为 JAVASCRIPT 模式,
        // JAVASCRIPT 模式下 [[${var}]] 的 String 值会被 Jackson 序列化加引号,
        // 与模板外层引号叠加导致 ""value"" 双引号
        resolver.setForceTemplateMode(true);
        resolver.setCharacterEncoding("UTF-8");
        resolver.setCacheable(false);
        resolver.setOrder(0);

        SpringTemplateEngine engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);
        return engine;
    }
}
