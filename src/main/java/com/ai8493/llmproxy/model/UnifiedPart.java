package com.ai8493.llmproxy.model;

import com.fasterxml.jackson.databind.JsonNode;

// 隐式 permits:所有 subtypes 嵌套在 sealed interface 内部,JDK 25 无法在 permits 子句中
// 用简单名解析尚未声明的嵌套类型,故省略 permits 子句,由编译器自动推断。
public sealed interface UnifiedPart {

    record TextPart(String text) implements UnifiedPart {}

    record ImagePart(JsonNode imageData) implements UnifiedPart {}

    record ToolUsePart(UnifiedToolCall functionCall) implements UnifiedPart {}

    record ToolResultPart(JsonNode functionResponse, String toolCallId) implements UnifiedPart {}

    record ThinkingPart(String thinking, String signature) implements UnifiedPart {}

    record RedactedThinkingPart(JsonNode data) implements UnifiedPart {}

    record DocumentPart(JsonNode documentData) implements UnifiedPart {}

    record FileDataPart(String fileUri, String mimeType) implements UnifiedPart {}

    record ExecutableCodePart(String language, String code) implements UnifiedPart {}

    record CodeExecutionResultPart(String outcome, String output) implements UnifiedPart {}
}
