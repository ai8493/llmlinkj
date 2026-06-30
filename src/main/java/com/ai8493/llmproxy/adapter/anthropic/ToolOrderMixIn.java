package com.ai8493.llmproxy.adapter.anthropic;

import com.fasterxml.jackson.annotation.JsonPropertyOrder; /**
 * 定义 Tool 序列化字段顺序，确保 tools 数组中每个工具字段顺序一致。
 */
@JsonPropertyOrder({
        "type",
        "name",
        "description",
        "input_schema",
        "cache_control",
        "defer_loading",
        "strict",
        "allowed_callers",
        "eager_input_streaming",
        "input_examples"
})
public interface ToolOrderMixIn {}
