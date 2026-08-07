package com.ai8493.llmproxy.exception;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ErrorNormalizerTest {

    @Test
    void shouldNormalizeStandardOpenAIError() {
        // 标准 OpenAI 格式 {"error":{"message","type","code","param"}}
        String body = "{\"error\":{\"message\":\"Invalid model\",\"type\":\"invalid_request_error\",\"code\":\"model_not_found\",\"param\":\"model\"}}";

        Map<String, Object> result = ErrorNormalizer.normalize(body);

        assertThat(result.get("message")).isEqualTo("Invalid model");
        assertThat(result.get("type")).isEqualTo("invalid_request_error");
        assertThat(result.get("code")).isEqualTo("model_not_found");
        assertThat(result.get("param")).isEqualTo("model");
    }

    @Test
    void shouldNormalizeMiniMaxBaseResp() {
        // MiniMax 格式 {"base_resp":{"status_code":2013,"status_msg":"upstream failed"}}
        String body = "{\"base_resp\":{\"status_code\":2013,\"status_msg\":\"upstream gateway failed\"}}";

        Map<String, Object> result = ErrorNormalizer.normalize(body);

        assertThat(result.get("message")).isEqualTo("upstream gateway failed");
        assertThat(result.get("type")).isEqualTo("upstream_error");
        assertThat(result.get("code")).isEqualTo(2013);
        assertThat(result.get("param")).isNull();
    }

    @Test
    void shouldNormalizePlainText() {
        // 纯文本错误体
        String body = "Internal Server Error";

        Map<String, Object> result = ErrorNormalizer.normalize(body);

        assertThat(result.get("message")).isEqualTo("Internal Server Error");
        assertThat(result.get("type")).isEqualTo("upstream_error");
        assertThat(result.get("code")).isNull();
    }

    @Test
    void shouldNormalizeDetailField() {
        // {"detail":"..."} 格式
        String body = "{\"detail\":\"Not Found\"}";

        Map<String, Object> result = ErrorNormalizer.normalize(body);

        assertThat(result.get("message")).isEqualTo("Not Found");
        assertThat(result.get("type")).isEqualTo("upstream_error");
    }

    @Test
    void shouldNormalizeEmptyBody() {
        Map<String, Object> result = ErrorNormalizer.normalize(null);

        assertThat(result.get("message")).isEqualTo("Upstream returned an empty error response");
        assertThat(result.get("type")).isEqualTo("upstream_error");
    }

    @Test
    void shouldNormalizeBlankBody() {
        Map<String, Object> result = ErrorNormalizer.normalize("   ");

        assertThat(result.get("message")).isEqualTo("Upstream returned an empty error response");
    }

    @Test
    void shouldNormalizeErrorWithMessageAndDetail() {
        // {"error":{"message":"...","detail":"..."}}
        String body = "{\"error\":{\"message\":\"Validation failed\",\"detail\":\"field 'model' is required\"}}";

        Map<String, Object> result = ErrorNormalizer.normalize(body);

        // message 优先,detail 作为 message 的 fallback(此处 message 存在,用 message)
        assertThat(result.get("message")).isEqualTo("Validation failed");
    }

    @Test
    void shouldNormalizeNestedCodeFromBaseResp() {
        // base_resp 嵌套在 error 内
        String body = "{\"error\":{\"base_resp\":{\"status_code\":4001,\"status_msg\":\"rate limited\"}}}";

        Map<String, Object> result = ErrorNormalizer.normalize(body);

        assertThat(result.get("message")).isEqualTo("rate limited");
        assertThat(result.get("code")).isEqualTo(4001);
    }

    @Test
    void shouldFallbackToSerializedSourceWhenNoMessageField() {
        // 无 message/detail/status_msg 字段,降级为序列化 source
        String body = "{\"error\":{\"unknown_field\":\"some_value\"}}";

        Map<String, Object> result = ErrorNormalizer.normalize(body);

        assertThat((String) result.get("message")).contains("unknown_field");
        assertThat((String) result.get("message")).contains("some_value");
    }

    @Test
    void shouldHandleNumericCode() {
        // code 是数字
        String body = "{\"error\":{\"message\":\"bad\",\"code\":429}}";

        Map<String, Object> result = ErrorNormalizer.normalize(body);

        assertThat(result.get("code")).isEqualTo(429);
    }

    @Test
    void shouldHandleStringCode() {
        // code 是字符串
        String body = "{\"error\":{\"message\":\"bad\",\"code\":\"rate_limit_exceeded\"}}";

        Map<String, Object> result = ErrorNormalizer.normalize(body);

        assertThat(result.get("code")).isEqualTo("rate_limit_exceeded");
    }
}
