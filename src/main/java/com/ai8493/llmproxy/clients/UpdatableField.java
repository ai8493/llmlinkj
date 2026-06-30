package com.ai8493.llmproxy.clients;

// 单个可更新字段的描述：所属文件 + 字段定位键 + 模板变量名 + 值后缀
// fieldKey 按 language 解释：
//   toml: "model" 或 "model_providers.llm-proxy.base_url"（section.key 格式）
//   json: "/OPENAI_API_KEY"（JSON Pointer 格式）
//   ini:  "GEMINI_API_KEY"
// valueSuffix 用于 codex base_url 需在变量后拼 "/v1" 的场景，其余为 ""
public record UpdatableField(
    String filename,
    String fieldKey,
    String templateVar,
    String valueSuffix
) {}
