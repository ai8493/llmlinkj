package com.ai8493.llmproxy.clients;

import java.util.List;

// gemini cli 客户端定义。模板路径 "clients/gemini-cli/env" 对应
// resources/templates/clients/gemini-cli/env（Thymeleaf TEXT 模式渲染）
// gemini cli 通过 .env 文件配置（GEMINI_API_KEY / GEMINI_MODEL / GOOGLE_GEMINI_BASE_URL 环境变量）
public record GeminiCliClient(String proxyBaseUrl, String apiKey) implements ClientDefinition {

    @Override
    public String id() { return "gemini-cli"; }

    @Override
    public String displayName() { return "Gemini CLI"; }

    @Override
    public String protocol() { return "gemini"; }

    @Override
    public String proxyEndpointPath() { return "/v1beta/models"; }

    @Override
    public String configSubdir() { return ".gemini"; }

    @Override
    public List<ClientFile> files() {
        return List.of(
            new ClientFile(".env", "ini", "clients/gemini-cli/env"),
            new ClientFile("settings.json", "json", "clients/gemini-cli/settings")
        );
    }

    @Override
    public List<UpdatableField> updatableFields() {
        return List.of(
            new UpdatableField(".env", "GEMINI_API_KEY", "apiKey", ""),
            new UpdatableField(".env", "GEMINI_MODEL", "defaultModel", ""),
            new UpdatableField(".env", "GOOGLE_GEMINI_BASE_URL", "proxyBaseUrl", "")
        );
    }
}
