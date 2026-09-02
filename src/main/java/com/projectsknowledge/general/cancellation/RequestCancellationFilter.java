package com.projectsknowledge.general.cancellation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.projectsknowledge.general.exception.ApiErrorCode;
import com.projectsknowledge.general.exception.KnowledgeException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@RequiredArgsConstructor
public class RequestCancellationFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Request-ID";
    private final RequestCancellationRegistry registry;
    private final ObjectMapper mapper;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return !path.startsWith("/api/") || path.startsWith("/api/requests/") || request.getHeader(HEADER) == null;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
        UUID id;
        try {
            String value = request.getHeader(HEADER);
            id = UUID.fromString(value);
            if (!id.toString().equalsIgnoreCase(value)) throw new IllegalArgumentException();
        } catch (IllegalArgumentException invalid) {
            response.setStatus(400);
            response.setContentType("application/json");
            mapper.writeValue(
                response.getOutputStream(),
                Map.of("code", ApiErrorCode.INVALID_REQUEST_ID, "message", "Invalid request ID.", "retryable", false)
            );
            return;
        }
        boolean registered = false;
        try {
            var token = registry.register(id);
            registered = true;
            RequestCancellation.with(token, () -> {
                try {
                    chain.doFilter(request, response);
                } catch (IOException error) {
                    throw new UncheckedIOException(error);
                } catch (ServletException error) {
                    throw new ServletFailure(error);
                }
                return null;
            });
        } catch (KnowledgeException failure) {
            response.setStatus(failure.getStatus().value());
            response.setContentType("application/json");
            mapper.writeValue(
                response.getOutputStream(),
                Map.of(
                    "code",
                    failure.getCode(),
                    "message",
                    failure.getMessage(),
                    "retryable",
                    failure.isRetryable()
                )
            );
        } catch (UncheckedIOException error) {
            throw error.getCause();
        } catch (ServletFailure error) {
            throw (ServletException) error.getCause();
        } finally {
            if (registered) registry.finish(id);
        }
    }

    private static final class ServletFailure extends RuntimeException {

        private ServletFailure(ServletException cause) {
            super(cause);
        }
    }
}
