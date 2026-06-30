package com.ai8493.llmproxy.clients;

// 单个配置文件描述：文件名（Tab 标签）+ 语言（CodeMirror mode）+ 模板逻辑视图名
public record ClientFile(
    String filename,
    String language,
    String templatePath
) {}
