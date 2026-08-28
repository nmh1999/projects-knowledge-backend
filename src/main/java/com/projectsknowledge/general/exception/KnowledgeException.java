package com.projectsknowledge.general.exception;

import org.springframework.http.HttpStatus;

public class KnowledgeException extends RuntimeException {

    private final HttpStatus status;

    public KnowledgeException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
