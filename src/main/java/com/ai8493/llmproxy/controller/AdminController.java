package com.ai8493.llmproxy.controller;

import com.ai8493.llmproxy.config.ConfigService;
import com.ai8493.llmproxy.config.entity.BackendConfigEntity;
import com.ai8493.llmproxy.config.entity.ModelMappingEntity;
import com.ai8493.llmproxy.config.entity.ProtocolMappingEntity;
import org.springframework.data.domain.Page;
import org.springframework.security.web.server.csrf.CsrfToken;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Controller
@RequestMapping("/admin")
public class AdminController {

    private final ConfigService configService;

    public AdminController(ConfigService configService) {
        this.configService = configService;
    }

    // WebFlux + Thymeleaf 下 _csrf 不会自动注入 model，需显式从 request attribute 取出 Mono<CsrfToken> 并返回，
    // Spring 会自动 subscribe 并把 CsrfToken 暴露给所有 admin 模板
    @ModelAttribute("_csrf")
    public Mono<CsrfToken> csrfToken(
        @RequestAttribute(name = "org.springframework.security.web.server.csrf.CsrfToken", required = false)
        Mono<CsrfToken> csrfTokenMono) {
        return csrfTokenMono;
    }

    // ===== 页面 =====

    @GetMapping("/login")
    public Mono<String> loginPage(
        ServerWebExchange exchange,
        @RequestAttribute(name = "org.springframework.security.web.server.csrf.CsrfToken", required = false)
        Mono<CsrfToken> csrfTokenMono, Model model) {
        // WebFlux 下 Thymeleaf 不暴露 ${param}，需显式把登录状态写入 model
        // 用 containsKey 判断，避免 ?error（无值）时 @RequestParam 返回 null 漏判
        var queryParams = exchange.getRequest().getQueryParams();
        if (queryParams.containsKey("error")) model.addAttribute("loginError", true);
        if (queryParams.containsKey("logout")) model.addAttribute("logoutSuccess", true);
        // 显式订阅 CSRF token Mono：
        // 1. 触发 CookieServerCsrfTokenRepository 写入 XSRF-TOKEN cookie（明文 token）
        // 2. 将 XOR 编码后的 token 暴露到 model，供模板渲染 meta 标签
        if (csrfTokenMono == null) {
            return Mono.just("admin/login");
        }
        return csrfTokenMono.doOnNext(token -> model.addAttribute("_csrf", token))
            .then(Mono.just("admin/login"));
    }

    @GetMapping({"", "/"})
    public String index() {
        return "redirect:/admin/backends";
    }

    @GetMapping("/protocols")
    public String protocolsPage() {
        return "admin/protocols";
    }

    @GetMapping("/backends")
    public String backendsPage() {
        return "admin/backends";
    }

    // ===== REST API: Backends =====

    // 支持按后端名模糊匹配 + 分页：name 不传时查全部，page/size 默认 0/10
    @GetMapping("/api/backends")
    @ResponseBody
    public Page<BackendConfigEntity> listBackends(
        @RequestParam(required = false) String name,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "10") int size) {
        return configService.searchBackends(name, page, size);
    }

    @PostMapping("/api/backends")
    @ResponseBody
    public void saveBackend(@RequestBody BackendConfigEntity input) {
        configService.saveBackend(input);
    }

    @DeleteMapping("/api/backends/{name}")
    @ResponseBody
    public void deleteBackend(@PathVariable String name) {
        configService.deleteBackend(name);
    }

    // ===== REST API: ProtocolMappings =====

    // 支持按客户端协议过滤 + 分页：clientProtocol 不传时查全部，page/size 默认 0/10
    @GetMapping("/api/protocols")
    @ResponseBody
    public Page<ProtocolMappingEntity> listProtocols(
        @RequestParam(required = false) String clientProtocol,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "10") int size) {
        return configService.searchProtocolMappings(clientProtocol, page, size);
    }

    @GetMapping("/api/protocols/{clientProtocol}/{backendCfgName}")
    @ResponseBody
    public ProtocolMappingEntity getProtocol(@PathVariable String clientProtocol,
                                               @PathVariable String backendCfgName) {
        var pm = configService.getProtocolMapping(clientProtocol, backendCfgName);
        if (pm == null) {
            throw new IllegalArgumentException("协议映射不存在: " + clientProtocol + "/" + backendCfgName);
        }
        return pm;
    }

    @PostMapping("/api/protocols")
    @ResponseBody
    public void saveProtocol(@RequestBody ProtocolMappingEntity input) {
        configService.saveProtocolMapping(input);
    }

    @DeleteMapping("/api/protocols/{clientProtocol}/{backendCfgName}")
    @ResponseBody
    public void deleteProtocol(@PathVariable String clientProtocol,
                                @PathVariable String backendCfgName) {
        configService.deleteProtocolMapping(clientProtocol, backendCfgName);
    }

    // 编辑模式下"添加"按钮即时落库：单条模型映射 upsert
    @PostMapping("/api/protocols/{clientProtocol}/{backendCfgName}/model-mappings")
    @ResponseBody
    public void addModelMapping(@PathVariable String clientProtocol,
                                @PathVariable String backendCfgName,
                                @RequestBody ModelMappingEntity input) {
        configService.addModelMapping(clientProtocol, backendCfgName, input.requestModel(), input.actualModel());
    }

    // 编辑模式下"删除"按钮即时落库：按主键删单条模型映射
    @DeleteMapping("/api/protocols/{clientProtocol}/{backendCfgName}/model-mappings/{requestModel}")
    @ResponseBody
    public void deleteModelMapping(@PathVariable String clientProtocol,
                                    @PathVariable String backendCfgName,
                                    @PathVariable String requestModel) {
        configService.deleteModelMapping(clientProtocol, backendCfgName, requestModel);
    }
}
