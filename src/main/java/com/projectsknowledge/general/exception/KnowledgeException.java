package com.projectsknowledge.general.exception;

import org.springframework.http.HttpStatus;

public class KnowledgeException extends RuntimeException {

    private final HttpStatus status;
    private final ApiErrorCode code;
    private final boolean retryable;

    public KnowledgeException(HttpStatus status, String message) {
        this(status, ApiErrorCode.REQUEST_FAILED, message, status.is5xxServerError());
    }

    public KnowledgeException(HttpStatus status, ApiErrorCode code, String message, boolean retryable) {
        super(message);
        this.status = status;
        this.code = code;
        this.retryable = retryable;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public ApiErrorCode getCode() {
        return code;
    }

    public boolean isRetryable() {
        return retryable;
    }
}
