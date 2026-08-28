package com.projectsknowledge.general.cancellation;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** Explicit cancellation is separate from the original connection, which the browser aborts immediately. */
@RestController
@RequestMapping("/api/requests")
@RequiredArgsConstructor
public class RequestCancellationController {

    private final RequestCancellationRegistry registry;

    @PostMapping("/{id}/cancel")
    public ResponseEntity<Void> cancel(@PathVariable UUID id) {
        registry.cancel(id);
        return ResponseEntity.noContent().build();
    }
}
