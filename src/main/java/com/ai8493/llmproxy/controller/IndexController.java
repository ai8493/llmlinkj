package com.ai8493.llmproxy.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

// 根路径无 Controller 映射，WebFlux 会尝试找静态资源导致 NoResourceFoundException。
// 这里映射 GET / 重定向到 /admin，未登录用户由 SecurityConfig 跳到 /admin/login。
@Controller
public class IndexController {

    @GetMapping("/")
    public String index() {
        return "redirect:/admin";
    }
}
