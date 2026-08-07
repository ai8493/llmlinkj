package com.ai8493.llmproxy.exception;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

// 上游错误体归一化:把各种非标错误体统一为 OpenAI 标准 shape {"message","type","code","param"}。
public final class ErrorNormalizer {

    private static final Logger log = LoggerFactory.getLogger(ErrorNormalizer.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ErrorNormalizer() {}

    // 解析 rawBody,返回 {"message","type","code","param"} 四字段 Map。
    // rawBody 为 null/空时返回全 null 的默认错误。
    public static Map<String, Object> normalize(String rawBody) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("message", null);
        result.put("type", "upstream_error");
        result.put("code", null);
        result.put("param", null);

        if (rawBody == null || rawBody.isBlank()) {
            result.put("message", "Upstream returned an empty error response");
            return result;
        }

        // 纯文本:直接作为 message
        String trimmed = rawBody.trim();
        if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) {
            result.put("message", trimmed);
            return result;
        }

        try {
            JsonNode body = MAPPER.readTree(trimmed);
            // source = body.error 或 body 本身
            JsonNode source = body.has("error") && body.get("error").isObject() ? body.get("error") : body;

            // message: message / detail / status_msg / base_resp.status_msg / source 本身(字符串) / 序列化 source
            String message = pickString(source, "message", "detail", "status_msg");
            if (message == null) {
                message = pickPointer(source, "/base_resp/status_msg");
            }
            if (message == null && source.isTextual()) {
                message = source.asText();
            }
            if (message == null) {
                message = source.toString();
            }
            result.put("message", message);

            // type: source.type 或 "upstream_error"
            String type = pickString(source, "type");
            if (type != null) result.put("type", type);

            // code: source.code 或 base_resp.status_code
            JsonNode code = source.get("code");
            if (code == null || code.isNull()) {
                code = source.at("/base_resp/status_code");
            }
            if (code != null && !code.isNull()) {
                result.put("code", code.isNumber() ? code.asInt() : code.asText());
            }

            // param: source.param
            JsonNode param = source.get("param");
            if (param != null && !param.isNull()) {
                result.put("param", param.isTextual() ? param.asText() : param.toString());
            }
        } catch (Exception e) {
            log.debug("错误体 JSON 解析失败,降级为纯文本: body={}", rawBody, e);
            result.put("message", trimmed);
        }
        return result;
    }

    private static String pickString(JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode v = node.get(field);
            if (v != null && v.isTextual() && !v.asText().isEmpty()) {
                return v.asText();
            }
        }
        return null;
    }

    private static String pickPointer(JsonNode node, String pointer) {
        JsonNode v = node.at(pointer);
        if (v != null && v.isTextual() && !v.asText().isEmpty()) {
            return v.asText();
        }
        return null;
    }
}
