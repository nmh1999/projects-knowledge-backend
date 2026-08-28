package com.projectsknowledge.general.exception;

import java.time.Instant;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(KnowledgeException.class)
    ResponseEntity<Map<String, Object>> handleKnowledge(KnowledgeException exception) {
        return ResponseEntity.status(exception.getStatus()).body(error(exception.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Map<String, Object>> handleValidation() {
        return ResponseEntity.badRequest().body(error("The request is invalid."));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Map<String, Object>> handleUnexpected() {
        return ResponseEntity.internalServerError().body(error("Unable to analyze the project."));
    }

    private Map<String, Object> error(String message) {
        return Map.of("message", message, "timestamp", Instant.now().toString());
    }
}
