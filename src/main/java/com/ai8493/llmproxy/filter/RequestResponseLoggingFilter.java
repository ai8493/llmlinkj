package com.ai8493.llmproxy.filter;

import com.ai8493.llmproxy.util.SensitiveDataMasker;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.stereotype.Component;
import org.springframework.util.MultiValueMap;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class RequestResponseLoggingFilter implements WebFilter {

    private static final Logger log = LoggerFactory.getLogger(RequestResponseLoggingFilter.class);
    private static final Set<String> SENSITIVE_HEADERS = Set.of("authorization", "x-goog-api-key");

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (!log.isDebugEnabled()) {
            return chain.filter(exchange);
        }

        // 先记录请求头+path，不依赖 body 读取，确保 404 等场景也能看到
        logRequestLine(exchange.getRequest());

        MediaType contentType = exchange.getRequest().getHeaders().getContentType();
        if (MediaType.APPLICATION_FORM_URLENCODED.isCompatibleWith(contentType)) {
            // form data 请求：必须先触发 getFormData() 订阅 originalRequest 的 body，
            // 否则 DataBufferUtils.join 会先消费 body，导致后续 CSRF filter 的 form data 解析拿到 empty。
            // formDataMono 内部已 .cache()，第二次订阅（CSRF filter）从缓存读取，不重复消费 body。
            BodyCaptureResponse decoratedResponse = new BodyCaptureResponse(exchange);
            ServerWebExchange mutatedExchange = exchange.mutate().response(decoratedResponse).build();
            return mutatedExchange.getFormData()
                .doOnNext(this::logRequestBody)
                .then(chain.filter(mutatedExchange))
                .doFinally(s -> {
                    if (!decoratedResponse.logged) {
                        logResponseStatus(decoratedResponse);
                    }
                });
        }

        return DataBufferUtils.join(exchange.getRequest().getBody())
            .defaultIfEmpty(exchange.getResponse().bufferFactory().wrap(new byte[0]))
            .flatMap(dataBuffer -> {
                byte[] reqBody = new byte[dataBuffer.readableByteCount()];
                dataBuffer.read(reqBody);
                DataBufferUtils.release(dataBuffer);

                logRequestBody(reqBody);

                ServerHttpRequest decoratedRequest = new ServerHttpRequestDecorator(exchange.getRequest()) {
                    @Override
                    public Flux<DataBuffer> getBody() {
                        return Flux.just(exchange.getResponse().bufferFactory().wrap(reqBody));
                    }
                };

                BodyCaptureResponse decoratedResponse = new BodyCaptureResponse(exchange);

                return chain.filter(exchange.mutate()
                        .request(decoratedRequest)
                        .response(decoratedResponse)
                        .build())
                    .doFinally(s -> {
                        // 兜底：404 等异常时 writeWith 被绕过，在此记录状态码
                        if (!decoratedResponse.logged) {
                            logResponseStatus(decoratedResponse);
                        }
                    });
            });
    }

    private void logRequestLine(ServerHttpRequest request) {
        Map<String, String> headers = collectHeaders(request);
        log.debug("请求: {} {} | headers: {}", request.getMethod(), request.getURI().getPath(), headers);
    }

    private void logRequestBody(byte[] body) {
        String bodyStr = new String(body, StandardCharsets.UTF_8);
        if (!bodyStr.isEmpty()) {
            log.debug("请求体: {}", bodyStr);
        }
    }

    private void logRequestBody(MultiValueMap<String, String> formData) {
        if (!formData.isEmpty()) {
            log.debug("请求体: {}", formData);
        }
    }

    private void logResponseStatus(BodyCaptureResponse response) {
        log.debug("响应: {} | headers: {}",
            response.getStatusCode() != null ? response.getStatusCode().value() : "?",
            collectResponseHeaders(response));
    }

    private Map<String, String> collectHeaders(ServerHttpRequest request) {
        Map<String, String> headers = new LinkedHashMap<>();
        request.getHeaders().forEach((k, v) -> {
            if (SENSITIVE_HEADERS.contains(k.toLowerCase())) {
                headers.put(k, SensitiveDataMasker.maskApiKey(v.isEmpty() ? "" : v.get(0)));
            } else {
                headers.put(k, String.join(",", v));
            }
        });
        return headers;
    }

    private Map<String, String> collectResponseHeaders(BodyCaptureResponse response) {
        Map<String, String> headers = new LinkedHashMap<>();
        response.getHeaders().forEach((k, v) -> headers.put(k, String.join(",", v)));
        return headers;
    }

    private static class BodyCaptureResponse extends ServerHttpResponseDecorator {
        private final ServerWebExchange exchange;
        boolean logged;

        BodyCaptureResponse(ServerWebExchange exchange) {
            super(exchange.getResponse());
            this.exchange = exchange;
        }

        @Override
        public Mono<Void> writeWith(Publisher<? extends DataBuffer> body) {
            boolean isSse = getHeaders().getContentType() != null &&
                getHeaders().getContentType().includes(MediaType.TEXT_EVENT_STREAM);

            if (isSse) {
                logged = true;
                log.debug("响应: {} | headers: {} | body: <SSE流>",
                    getStatusCode() != null ? getStatusCode().value() : "?",
                    collectResponseHeadersStatic(this));
                return super.writeWith(body);
            }

            return DataBufferUtils.join(body).flatMap(db -> {
                byte[] bytes = new byte[db.readableByteCount()];
                db.read(bytes);
                DataBufferUtils.release(db);

                logged = true;
                log.debug("响应: {} | headers: {} | body: {}",
                    getStatusCode() != null ? getStatusCode().value() : "?",
                    collectResponseHeadersStatic(this),
                    new String(bytes, StandardCharsets.UTF_8));

                return super.writeWith(Mono.just(
                    exchange.getResponse().bufferFactory().wrap(bytes)));
            });
        }

        private static Map<String, String> collectResponseHeadersStatic(BodyCaptureResponse response) {
            Map<String, String> headers = new LinkedHashMap<>();
            response.getHeaders().forEach((k, v) -> headers.put(k, String.join(",", v)));
            return headers;
        }
    }
}
