package com.ai8493.llmproxy.controller;

import com.ai8493.llmproxy.clients.ClientConfigService;
import com.ai8493.llmproxy.clients.ClientInfo;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.server.csrf.CsrfToken;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import reactor.core.publisher.Mono;

import java.util.List;

@Controller
@RequestMapping("/admin/clients")
public class ClientConfigController {

    private final ClientConfigService clientConfigService;

    public ClientConfigController(ClientConfigService clientConfigService) {
        this.clientConfigService = clientConfigService;
    }

    // WebFlux + Thymeleaf 下 _csrf 不会自动注入 model，需显式从 request attribute 取出 Mono<CsrfToken> 并返回，
    // Spring 会自动 subscribe 并把 CsrfToken 暴露给所有 admin 模板
    @ModelAttribute("_csrf")
    public Mono<CsrfToken> csrfToken(
        @RequestAttribute(name = "org.springframework.security.web.server.csrf.CsrfToken", required = false)
        Mono<CsrfToken> csrfTokenMono) {
        return csrfTokenMono;
    }

    @GetMapping({"", "/"})
    public String page() {
        return "admin/clients";
    }

    @GetMapping("/api/clients")
    @ResponseBody
    public List<ClientInfo> listClients() {
        return clientConfigService.listClients();
    }

    @GetMapping("/api/clients/{id}/files/{filename}")
    public ResponseEntity<String> readFile(@PathVariable String id, @PathVariable String filename) {
        var result = clientConfigService.readFile(id, filename);
        return ResponseEntity.ok()
            .header("X-File-Exists", String.valueOf(result.exists()))
            .header("X-Filled", String.valueOf(result.filled()))
            .body(result.content());
    }

    @PutMapping("/api/clients/{id}/files/{filename}")
    @ResponseBody
    public String writeFile(@PathVariable String id,
                          @PathVariable String filename,
                          @RequestBody String content) {
        return clientConfigService.writeFile(id, filename, content).content();
    }

    // 应用代理默认值：返回更新后的文件内容，通过 X-Updated 响应头标识是否真正写入
    @PostMapping("/api/clients/{id}/files/{filename}/apply-defaults")
    @ResponseBody
    public ResponseEntity<String> applyDefaults(@PathVariable String id, @PathVariable String filename) {
        var result = clientConfigService.applyProxyDefaults(id, filename);
        return ResponseEntity.ok()
            .header("X-Updated", String.valueOf(result.updated()))
            .body(result.content());
    }
}
