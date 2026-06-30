package com.ai8493.llmproxy.clients;

import java.util.List;

// 客户端定义契约。sealed permits 在 Task 3 加 GeminiCliClient 时一并补全
public sealed interface ClientDefinition
    permits CodexClient, GeminiCliClient {

    String id();          // URL 路径段 + 文件目录名，如 "codex"
    String displayName(); // 左列表展示，如 "Codex"
    String protocol();    // 关联代理 protocol 概念，如 "responses"
    String proxyEndpointPath(); // 模板里写 baseUrl 时用，如 "/v1/responses"
    // 配置文件在用户 HOME 下的子目录名，如 ".codex" / ".gemini"
    String configSubdir();

    List<ClientFile> files();

    // 该客户端声明的可更新字段列表，默认空（如 settings.json 无代理字段不重写）
    default List<UpdatableField> updatableFields() {
        return List.of();
    }

    // 模板上下文变量：代理服务地址
    String proxyBaseUrl();

    // 模板上下文变量：API Key（admin 密码明文，占位 token 用）
    String apiKey();
}
