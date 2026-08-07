package com.ai8493.llmproxy.adapter.gemini;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Gemini 出站转换的 per-request 上下文。
 * 持有 reasoningStore 用的 sessionKey 和流式 toolCall 累积器(按 index 区分,支持多 toolCall)。
 * 由 ProxyController 创建,传给 GeminiProtocolAdapter 的重载方法。
 */
public class GeminiRequestContext {

    private final String sessionKey;
    private final Map<Integer, ToolCallAcc> toolCallAccs = new LinkedHashMap<>();

    public GeminiRequestContext(String sessionKey) {
        this.sessionKey = sessionKey;
    }

    public String sessionKey() {
        return sessionKey;
    }

    public Map<Integer, ToolCallAcc> toolCallAccs() {
        return toolCallAccs;
    }

    /** 单个 toolCall 的累积器(id/name 信号 + arguments 增量) */
    public static class ToolCallAcc {
        String id, fnName;
        final StringBuilder argsBuilder = new StringBuilder();

        void reset() {
            id = null;
            fnName = null;
            argsBuilder.setLength(0);
        }
    }
}
