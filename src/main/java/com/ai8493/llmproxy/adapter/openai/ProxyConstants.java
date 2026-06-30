package com.ai8493.llmproxy.adapter.openai;

public final class ProxyConstants {
    private ProxyConstants() {}

    /** 工具调用参数名：携带 MCP server namespace 用于路由还原 */
    public static final String MCP_SERVER_ROUTER_PARAM = "target_server_id";
}
