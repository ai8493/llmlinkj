package com.ai8493.llmproxy.adapter.anthropic;

import com.anthropic.backends.Backend;
import com.anthropic.core.http.Headers;
import com.anthropic.core.http.HttpRequest;
import com.anthropic.core.http.HttpResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * {@link Backend} 装饰器：
 * - prepareRequest 将请求体包上日志记录
 * - prepareResponse 对非流式响应打印原始 body,便于字段级审计(区分后端真实返回 vs SDK 序列化行为)
 */
public class LoggingBackend implements Backend {

    private static final Logger log = LoggerFactory.getLogger("anthropic");

    private final Backend delegate;

    public LoggingBackend(Backend delegate) {
        this.delegate = delegate;
    }

    @Override
    public HttpRequest prepareRequest(HttpRequest request) {
        HttpRequest prepared = delegate.prepareRequest(request);
        if (prepared.body() != null) {
            return prepared.toBuilder()
                           .body(new LoggingHttpRequestBody(prepared.body()))
                           .build();
        }
        return prepared;
    }

    @Override
    public HttpResponse prepareResponse(HttpResponse response) {
        HttpResponse prepared = delegate.prepareResponse(response);
        // 流式响应跳过 body 打印(已有事件级日志,且整体读取会破坏流式特性)
        String contentType = prepared.headers().values("content-type").stream()
            .findFirst().orElse("");
        if (contentType.toLowerCase().contains("text/event-stream")) {
            return prepared;
        }
        return new RawBodyLoggingHttpResponse(prepared);
    }

    @Override
    public HttpRequest authorizeRequest(HttpRequest request) {
        return delegate.authorizeRequest(request);
    }

    @Override
    public String baseUrl() {
        return delegate.baseUrl();
    }

    @Override
    public void close() {
        delegate.close();
    }

    /**
     * 包装 {@link HttpResponse},读取 body 到字节数组并打印日志,再用 {@link ByteArrayInputStream}
     * 重新返回给 SDK 解析。仅用于非流式响应。
     */
    private static final class RawBodyLoggingHttpResponse implements HttpResponse {

        private final HttpResponse delegate;
        private byte[] bodyBytes;
        private boolean bodyRead;

        RawBodyLoggingHttpResponse(HttpResponse delegate) {
            this.delegate = delegate;
        }

        @Override
        public int statusCode() {
            return delegate.statusCode();
        }

        @Override
        public Headers headers() {
            return delegate.headers();
        }

        @Override
        public InputStream body() {
            if (!bodyRead) {
                try {
                    bodyBytes = delegate.body().readAllBytes();
                } catch (IOException e) {
                    log.warn("读取后端响应 body 失败: {}", e.getMessage());
                    bodyBytes = new byte[0];
                }
                bodyRead = true;
                String bodyStr = new String(bodyBytes, StandardCharsets.UTF_8);
                log.info("RESPONSE-->:status={} body={}", statusCode(), bodyStr);
            }
            return new ByteArrayInputStream(bodyBytes);
        }

        @Override
        public void close() {
            delegate.close();
        }
    }
}
