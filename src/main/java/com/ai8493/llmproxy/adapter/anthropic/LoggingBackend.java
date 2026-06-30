package com.ai8493.llmproxy.adapter.anthropic;

import com.anthropic.backends.Backend;
import com.anthropic.core.http.HttpRequest;
import com.anthropic.core.http.HttpResponse;

/**
 * {@link Backend} 装饰器，在 {@code prepareRequest} 中将请求体包上日志记录。
 */
public class LoggingBackend implements Backend {

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
        return delegate.prepareResponse(response);
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
}
