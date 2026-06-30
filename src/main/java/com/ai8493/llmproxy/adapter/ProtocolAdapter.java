package com.ai8493.llmproxy.adapter;

import com.ai8493.llmproxy.model.UnifiedChatRequest;
import com.ai8493.llmproxy.model.UnifiedChatResponse;
import java.util.Map;

public interface ProtocolAdapter {
    /** 协议名称，如 "openai-chat"、"gemini" */
    String protocolName();

    /** 将原始字节请求解析为统一模型（多协议接入时使用） */
    UnifiedChatRequest toUnifiedRequest(byte[] rawRequest, Map<String, String> headers);

    /** 将统一响应序列化为该协议的响应字节 */
    byte[] fromUnifiedResponse(UnifiedChatResponse unifiedResponse);

    /** 将统一流式块序列化为该协议的 SSE 字符串 */
    String fromUnifiedStreamChunk(UnifiedChatResponse chunk);
}
