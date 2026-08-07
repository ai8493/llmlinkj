package com.ai8493.llmproxy.model;

/**
 * 流式 toolCall arguments 增量,带 index 支持多 toolCall 并行按 index 区分。
 * 由 OpenAiStreamingResponseConverter 产出(OpenAI 后端流式),
 * 由 Gemini/Anthropic/OpenAI 出站 adapter 消费。
 */
public record IndexedArgumentDelta(Integer index, String partialJson) {}
