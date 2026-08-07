package com.ai8493.llmproxy.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.reactive.resource.NoResourceFoundException;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BackendApiException.class)
    public ResponseEntity<Map<String, Object>> handleBackendError(BackendApiException e) {
        log.error("后端 API 异常: backend={}, status={}, message={}, rawBody={}",
            e.getBackend(), e.getStatusCode(), e.getMessage(), e.getRawBody());
        int httpStatus = e.getStatusCode();

        // 有 rawBody 时归一化上游错误体(MiniMax base_resp / 纯文本 / 标准格式等),否则用异常 message
        Map<String, Object> errorFields;
        if (e.getRawBody() != null && !e.getRawBody().isBlank()) {
            errorFields = ErrorNormalizer.normalize(e.getRawBody());
        } else {
            errorFields = new java.util.LinkedHashMap<>();
            errorFields.put("message", e.getMessage());
            errorFields.put("type", mapErrorType(httpStatus));
            errorFields.put("code", httpStatus);
            errorFields.put("param", null);
        }

        return ResponseEntity.status(mapHttpStatus(httpStatus)).body(Map.of("error", errorFields));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleBadRequest(IllegalArgumentException e) {
        log.warn("请求参数异常: {}", e.getMessage(), e);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
            "error", Map.of(
                "message", e.getMessage(),
                "type", "invalid_request_error",
                "code", 400
            )
        ));
    }

    // 协议转换失败:请求格式正确但内容无法转换(未知字段/不支持的消息类型/工具映射失败等)
    // 返回 422 Unprocessable Entity,与 400(请求格式错误)区分
    @ExceptionHandler(TransformException.class)
    public ResponseEntity<Map<String, Object>> handleTransformError(TransformException e) {
        log.warn("协议转换异常: {}", e.getMessage(), e);
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of(
            "error", Map.of(
                "message", e.getMessage(),
                "type", "transform_error",
                "code", 422
            )
        ));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGeneral(Exception e) {
        log.error("未捕获异常: {}", e.getMessage(), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
            "error", Map.of(
                "message", e.getMessage(),
                "type", "server_error",
                "code", 500
            )
        ));
    }

    // 静态资源不存在（如 /favicon.ico）：浏览器自动请求，属正常情况，不记 ERROR 避免污染日志。
    // 必须在 handleGeneral 之前声明——Spring 按异常类型具体度匹配，NoResourceFoundException 优先于 Exception。
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(NoResourceFoundException e) {
        log.debug("静态资源未找到: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
            "error", Map.of(
                "message", e.getMessage(),
                "type", "not_found",
                "code", 404
            )
        ));
    }

    private String mapErrorType(int status) {
        return switch (status) {
            case 400 -> "invalid_request_error";
            case 401, 403 -> "authentication_error";
            case 429 -> "rate_limit_error";
            case 500 -> "server_error";
            case 503 -> "server_error";
            default -> "api_error";
        };
    }

    private HttpStatus mapHttpStatus(int status) {
        return switch (status) {
            case 400 -> HttpStatus.BAD_REQUEST;
            case 401, 403 -> HttpStatus.UNAUTHORIZED;
            case 429 -> HttpStatus.TOO_MANY_REQUESTS;
            case 500 -> HttpStatus.BAD_GATEWAY;
            case 503 -> HttpStatus.SERVICE_UNAVAILABLE;
            default -> HttpStatus.BAD_GATEWAY;
        };
    }
}
