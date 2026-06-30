package com.ai8493.llmproxy.adapter.anthropic;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * 定义 MessageCreateParams.Body 序列化时的字段顺序，将稳定字段排在前、频繁变化的
 * messages 排在最后。
 */
@JsonPropertyOrder({
        "system",
        "tools",
        "tool_choice",
        "max_tokens",
        "temperature",
        "top_p",
        "top_k",
        "thinking",
        "stop_sequences",
        "cache_control",
        "service_tier",
        "metadata",
        "container",
        "inference_geo",
        "output_config",
        "model",
        "messages"
})
public interface AnthropicBodyOrderMixIn {}

