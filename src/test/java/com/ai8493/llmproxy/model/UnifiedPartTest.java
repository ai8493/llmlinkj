package com.ai8493.llmproxy.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class UnifiedPartTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void shouldCreateTextPart() {
        UnifiedPart.TextPart part = new UnifiedPart.TextPart("hello");
        assertThat(part.text()).isEqualTo("hello");
        assertThat(part).isInstanceOf(UnifiedPart.class);
    }

    @Test
    void shouldCreateImagePart() throws Exception {
        JsonNode imageData = mapper.readTree("{\"url\":\"data:image/png;base64,...\"}");
        UnifiedPart.ImagePart part = new UnifiedPart.ImagePart(imageData);
        assertThat(part.imageData()).isEqualTo(imageData);
    }

    @Test
    void shouldCreateToolUsePart() {
        UnifiedToolCall call = UnifiedToolCall.builder()
            .id("call_1").type("function")
            .function(UnifiedFunctionCall.builder().name("get_weather").build())
            .build();
        UnifiedPart.ToolUsePart part = new UnifiedPart.ToolUsePart(call);
        assertThat(part.functionCall()).isEqualTo(call);
    }

    @Test
    void shouldCreateToolResultPart() throws Exception {
        JsonNode result = mapper.readTree("{\"temp\":72}");
        UnifiedPart.ToolResultPart part = new UnifiedPart.ToolResultPart(result, "call_1");
        assertThat(part.functionResponse()).isEqualTo(result);
        assertThat(part.toolCallId()).isEqualTo("call_1");
    }

    @Test
    void shouldCreateThinkingPart() {
        UnifiedPart.ThinkingPart part = new UnifiedPart.ThinkingPart("thinking content", "sig_123");
        assertThat(part.thinking()).isEqualTo("thinking content");
        assertThat(part.signature()).isEqualTo("sig_123");
    }

    @Test
    void shouldCreateRedactedThinkingPart() throws Exception {
        JsonNode data = mapper.readTree("{\"type\":\"redacted_thinking\",\"data\":\"...\"}");
        UnifiedPart.RedactedThinkingPart part = new UnifiedPart.RedactedThinkingPart(data);
        assertThat(part.data()).isEqualTo(data);
    }

    @Test
    void shouldCreateDocumentPart() throws Exception {
        JsonNode docData = mapper.readTree("{\"source\":{\"type\":\"base64\",\"media_type\":\"application/pdf\"}}");
        UnifiedPart.DocumentPart part = new UnifiedPart.DocumentPart(docData);
        assertThat(part.documentData()).isEqualTo(docData);
    }

    @Test
    void shouldBeExhaustiveInSwitch() {
        UnifiedPart part = new UnifiedPart.TextPart("hello");
        String type = switch (part) {
            case UnifiedPart.TextPart t -> "text";
            case UnifiedPart.ImagePart i -> "image";
            case UnifiedPart.ToolUsePart tu -> "tool_use";
            case UnifiedPart.ToolResultPart tr -> "tool_result";
            case UnifiedPart.ThinkingPart th -> "thinking";
            case UnifiedPart.RedactedThinkingPart rt -> "redacted_thinking";
            case UnifiedPart.DocumentPart d -> "document";
            case UnifiedPart.FileDataPart fd -> "file_data";
            case UnifiedPart.ExecutableCodePart ec -> "executable_code";
            case UnifiedPart.CodeExecutionResultPart cer -> "code_execution_result";
        };
        assertThat(type).isEqualTo("text");
    }

    @Test
    void shouldBuildFileDataPart() {
        UnifiedPart.FileDataPart part = new UnifiedPart.FileDataPart("gs://bucket/file.pdf", "application/pdf");
        assertThat(part.fileUri()).isEqualTo("gs://bucket/file.pdf");
        assertThat(part.mimeType()).isEqualTo("application/pdf");
    }

    @Test
    void shouldBuildExecutableCodePart() {
        UnifiedPart.ExecutableCodePart part = new UnifiedPart.ExecutableCodePart("PYTHON", "print('hello')");
        assertThat(part.language()).isEqualTo("PYTHON");
        assertThat(part.code()).isEqualTo("print('hello')");
    }

    @Test
    void shouldBuildCodeExecutionResultPart() {
        UnifiedPart.CodeExecutionResultPart part = new UnifiedPart.CodeExecutionResultPart("OUTCOME_OK", "hello\n");
        assertThat(part.outcome()).isEqualTo("OUTCOME_OK");
        assertThat(part.output()).isEqualTo("hello\n");
    }
}
