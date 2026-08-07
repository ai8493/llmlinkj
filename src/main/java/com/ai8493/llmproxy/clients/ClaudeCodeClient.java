package com.ai8493.llmproxy.clients;

import java.util.List;

// claude code 客户端定义。走 anthropic 协议,配置文件 ~/.claude/settings.json,
// 通过 env 块设置环境变量切代理。proxyBaseUrl 不加 /v1(Claude Code 内部自拼 /v1/messages)
public record ClaudeCodeClient(String proxyBaseUrl, String apiKey) implements ClientDefinition {

    @Override
    public String id() { return "claude-code"; }

    @Override
    public String displayName() { return "Claude Code"; }

    @Override
    public String protocol() { return "anthropic"; }

    @Override
    public String proxyEndpointPath() { return "/v1/messages"; }

    @Override
    public String configSubdir() { return ".claude"; }

    @Override
    public List<ClientFile> files() {
        return List.of(
            new ClientFile("settings.json", "json", "clients/claude-code/settings")
        );
    }

    @Override
    public List<UpdatableField> updatableFields() {
        return List.of(
            new UpdatableField("settings.json", "/env/ANTHROPIC_BASE_URL",             "proxyBaseUrl", ""),
            new UpdatableField("settings.json", "/env/ANTHROPIC_AUTH_TOKEN",           "apiKey",       ""),
            new UpdatableField("settings.json", "/env/ANTHROPIC_MODEL",                "defaultModel", ""),
            new UpdatableField("settings.json", "/env/ANTHROPIC_DEFAULT_SONNET_MODEL", "defaultModel", ""),
            new UpdatableField("settings.json", "/env/ANTHROPIC_DEFAULT_OPUS_MODEL",   "defaultModel", ""),
            new UpdatableField("settings.json", "/env/ANTHROPIC_DEFAULT_HAIKU_MODEL",  "defaultModel", ""),
            new UpdatableField("settings.json", "/env/CLAUDE_CODE_SUBAGENT_MODEL",     "defaultModel", "")
        );
    }
}
