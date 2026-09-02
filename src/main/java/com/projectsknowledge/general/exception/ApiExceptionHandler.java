package com.projectsknowledge.general.exception;

import java.time.Instant;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(KnowledgeException.class)
    ResponseEntity<Map<String, Object>> handleKnowledge(KnowledgeException exception) {
        return ResponseEntity.status(exception.getStatus()).body(
            error(exception.getCode(), exception.getMessage(), exception.isRetryable())
        );
    }

    @ExceptionHandler({ MethodArgumentNotValidException.class, MethodArgumentTypeMismatchException.class })
    ResponseEntity<Map<String, Object>> handleValidation() {
        return ResponseEntity.badRequest().body(error(ApiErrorCode.INVALID_REQUEST, "The request is invalid.", false));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Map<String, Object>> handleUnexpected() {
        return ResponseEntity.internalServerError().body(
            error(ApiErrorCode.INTERNAL_ERROR, "Unable to analyze the project.", true)
        );
    }

    private Map<String, Object> error(ApiErrorCode code, String message, boolean retryable) {
        return Map.of(
            "code",
            code,
            "message",
            message,
            "retryable",
            retryable,
            "timestamp",
            Instant.now().toString()
        );
    }
}
