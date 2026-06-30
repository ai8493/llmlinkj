package com.ai8493.llmproxy.adapter.openai;

import com.ai8493.llmproxy.model.UnifiedChatRequest;

public record ParseResult(UnifiedChatRequest request, ToolRemapContext toolRemapContext) {}
