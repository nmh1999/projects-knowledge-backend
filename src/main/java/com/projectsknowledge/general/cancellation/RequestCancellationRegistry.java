package com.projectsknowledge.general.cancellation;

import com.projectsknowledge.general.exception.KnowledgeException;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** Unpredictable per-request IDs isolate tabs; short tombstones handle cancellation arriving before the request. */
@Service
@RequiredArgsConstructor
public class RequestCancellationRegistry {

    private static final int MAX_ENTRIES = 1024;
    private final Clock clock;
    private final Map<UUID, Entry> entries = new HashMap<>();

    public synchronized RequestCancellation register(UUID id) {
        prune();
        var entry = entries.get(id);
        if (entry != null) {
            if (entry.token.isCancelled()) throw new RequestCancelledException();
            throw new KnowledgeException(HttpStatus.CONFLICT, "The request ID has already been used.");
        }
        ensureCapacity();
        var token = new RequestCancellation();
        entries.put(id, new Entry(token, null));
        return token;
    }

    public synchronized void cancel(UUID id) {
        prune();
        var entry = entries.get(id);
        if (entry == null) {
            ensureCapacity();
            entry = new Entry(new RequestCancellation(), clock.instant().plusSeconds(60));
            entries.put(id, entry);
        }
        entry.token.cancel();
    }

    public synchronized void finish(UUID id) {
        var entry = entries.get(id);
        if (entry != null) entries.put(id, new Entry(entry.token, clock.instant().plusSeconds(60)));
    }

    private void prune() {
        Instant now = clock.instant();
        entries.values().removeIf(entry -> entry.expiresAt != null && !now.isBefore(entry.expiresAt));
    }

    private void ensureCapacity() {
        if (entries.size() >= MAX_ENTRIES) throw new KnowledgeException(
            HttpStatus.TOO_MANY_REQUESTS,
            "Too many active requests. Please retry shortly."
        );
    }

    private record Entry(RequestCancellation token, Instant expiresAt) {}
}
