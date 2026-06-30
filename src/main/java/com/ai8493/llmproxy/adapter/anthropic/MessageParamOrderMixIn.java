package com.ai8493.llmproxy.adapter.anthropic;

import com.fasterxml.jackson.annotation.JsonPropertyOrder; /**
 * 定义 MessageParam 序列化字段顺序。
 */
@JsonPropertyOrder({
        "role",
        "content",
        "additionalProperties"
})
public interface MessageParamOrderMixIn {}
