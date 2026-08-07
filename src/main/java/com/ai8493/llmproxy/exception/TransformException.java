package com.ai8493.llmproxy.exception;

// 协议转换失败时抛出,GlobalExceptionHandler 映射为 422 Unprocessable Entity。
// 与 IllegalArgumentException(400 客户端请求格式错误)区分:TransformException 表示请求格式正确,
// 但内容无法转换(如未知字段、不支持的消息类型、工具映射失败等)。
public class TransformException extends RuntimeException {

    public TransformException(String message) {
        super(message);
    }

    public TransformException(String message, Throwable cause) {
        super(message, cause);
    }
}
