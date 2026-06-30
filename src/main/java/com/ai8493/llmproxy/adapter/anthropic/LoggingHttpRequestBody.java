package com.ai8493.llmproxy.adapter.anthropic;

import com.anthropic.core.http.HttpRequestBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

class LoggingHttpRequestBody implements HttpRequestBody {
    private static final Logger logger = LoggerFactory.getLogger("anthropic");

    private final HttpRequestBody delegate;

    LoggingHttpRequestBody(HttpRequestBody delegate) {
        this.delegate = delegate;
    }

    @Override
    public void writeTo(OutputStream outputStream) {
        ByteArrayOutputStream capture = new ByteArrayOutputStream();
        delegate.writeTo(new TeeOutputStream(outputStream, capture));
        logger.info("REQUEST-->:{}", capture.toString(StandardCharsets.UTF_8));
    }

    @Override
    public String contentType() {
        return delegate.contentType();
    }

    @Override
    public long contentLength() {
        return delegate.contentLength();
    }

    @Override
    public boolean repeatable() {
        return delegate.repeatable();
    }

    @Override
    public void close() {
        delegate.close();
    }

    private static class TeeOutputStream extends OutputStream {
        private final OutputStream a;
        private final OutputStream b;

        TeeOutputStream(OutputStream a, OutputStream b) {
            this.a = a;
            this.b = b;
        }

        @Override
        public void write(int i) throws IOException {
            a.write(i);
            b.write(i);
        }

        @Override
        public void write(byte[] bytes, int off, int len) throws IOException {
            a.write(bytes, off, len);
            b.write(bytes, off, len);
        }

        @Override
        public void flush() throws IOException {
            a.flush();
            b.flush();
        }

        @Override
        public void close() throws IOException {
            a.close();
            b.close();
        }
    }
}
