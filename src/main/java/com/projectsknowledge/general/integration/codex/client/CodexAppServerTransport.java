package com.projectsknowledge.general.integration.codex.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.projectsknowledge.general.config.ProjectsKnowledgeProperties;
import com.projectsknowledge.general.exception.ApiErrorCode;
import com.projectsknowledge.general.exception.KnowledgeException;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** Owns one lazily initialized connection, shared by independent read-only conversations. */
@Service
@RequiredArgsConstructor
@Slf4j
public class CodexAppServerTransport implements AutoCloseable {

    private final ObjectMapper mapper;
    private final ProjectsKnowledgeProperties properties;
    private final CodexProcessFactory processes;
    private CodexAppServerConnection connection;
    private boolean closed;

    synchronized CodexAppServerConnection connection() {
        if (closed || !properties.getCodex().isEnabled()) {
            throw new KnowledgeException(
                HttpStatus.SERVICE_UNAVAILABLE,
                ApiErrorCode.CODEX_UNAVAILABLE,
                "Codex integration is unavailable.",
                true
            );
        }
        if (connection != null && connection.isHealthy()) return connection;
        if (connection != null) connection.close();
        long started = System.nanoTime();
        CodexAppServerConnection candidate = null;
        try {
            candidate = new CodexAppServerConnection(mapper, processes.start());
            candidate.initialize(
                Duration.ofSeconds(Math.max(1, Math.min(30, properties.getCodex().getTimeoutSeconds())))
            );
            connection = candidate;
            log.info(
                "Codex connection initialized: setupMs={}",
                Duration.ofNanos(System.nanoTime() - started).toMillis()
            );
            return connection;
        } catch (IOException exception) {
            throw new KnowledgeException(
                HttpStatus.SERVICE_UNAVAILABLE,
                ApiErrorCode.CODEX_UNAVAILABLE,
                "Could not start Codex. Check its installation and sign-in.",
                true
            );
        } catch (RuntimeException exception) {
            if (candidate != null) candidate.close();
            throw exception;
        }
    }

    @PreDestroy
    @Override
    public synchronized void close() {
        closed = true;
        if (connection != null) connection.close();
        connection = null;
    }
}
