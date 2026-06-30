package com.ai8493.llmproxy.adapter.anthropic;

import com.fasterxml.jackson.annotation.JsonPropertyOrder; /**
 * 定义 Tool.InputSchema 序列化字段顺序。
 */
@JsonPropertyOrder({
        "type",
        "properties",
        "required"
})
public interface InputSchemaOrderMixIn {}
