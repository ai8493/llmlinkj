package com.ai8493.llmproxy.clients;

import java.util.List;

// codex 客户端定义。proxyBaseUrl 和 apiKey 由 ClientConfigService 构造时注入，
// 作为 Thymeleaf 模板上下文变量。模板路径 "clients/codex/config" 对应
// resources/templates/clients/codex/config.toml（Thymeleaf TEXT 模式渲染）
public record CodexClient(String proxyBaseUrl, String apiKey) implements ClientDefinition {

    @Override
    public String id() { return "codex"; }

    @Override
    public String displayName() { return "Codex"; }

    @Override
    public String protocol() { return "responses"; }

    @Override
    public String proxyEndpointPath() { return "/v1/responses"; }

    @Override
    public String configSubdir() { return ".codex"; }

    @Override
    public List<ClientFile> files() {
        return List.of(
            new ClientFile("config.toml", "toml", "clients/codex/config"),
            new ClientFile("auth.json", "json", "clients/codex/auth")
        );
    }

    @Override
    public List<UpdatableField> updatableFields() {
        return List.of(
            new UpdatableField("config.toml", "model", "defaultModel", ""),
            new UpdatableField("config.toml", "model_providers.llm-proxy.base_url", "proxyBaseUrl", "/v1"),
            new UpdatableField("auth.json", "/OPENAI_API_KEY", "apiKey", "")
        );
    }
}
