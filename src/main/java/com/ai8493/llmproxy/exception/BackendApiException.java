package com.ai8493.llmproxy.exception;

public class BackendApiException extends RuntimeException {
    private final String backend;
    private final int statusCode;

    public BackendApiException(String backend, int statusCode, String message) {
        super(message);
        this.backend = backend;
        this.statusCode = statusCode;
    }

    public String getBackend() { return backend; }
    public int getStatusCode() { return statusCode; }
}
