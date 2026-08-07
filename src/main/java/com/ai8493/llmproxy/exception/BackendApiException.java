package com.ai8493.llmproxy.exception;

public class BackendApiException extends RuntimeException {
    private final String backend;
    private final int statusCode;
    private final String rawBody;

    public BackendApiException(String backend, int statusCode, String message) {
        this(backend, statusCode, message, null);
    }

    public BackendApiException(String backend, int statusCode, String message, String rawBody) {
        super(message);
        this.backend = backend;
        this.statusCode = statusCode;
        this.rawBody = rawBody;
    }

    public String getBackend() { return backend; }
    public int getStatusCode() { return statusCode; }
    public String getRawBody() { return rawBody; }
}
