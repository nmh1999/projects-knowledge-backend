package com.projectsknowledge.general.cancellation;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.projectsknowledge.general.exception.ApiExceptionHandler;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@Timeout(10)
class RequestCancellationTest {

    @Test
    void registryIsolatesIdsRetainsActiveRequestsAndExpiresPrearrivalTombstones() {
        Clock clock = mock(Clock.class);
        Instant start = Instant.parse("2026-08-28T10:00:00Z");
        when(clock.instant()).thenReturn(start);
        var registry = new RequestCancellationRegistry(clock);
        UUID first = UUID.randomUUID(),
            second = UUID.randomUUID(),
            early = UUID.randomUUID();
        var a = registry.register(first);
        var b = registry.register(second);
        registry.cancel(first);
        registry.cancel(first); // Idempotent.
        assertThat(a.isCancelled()).isTrue();
        assertThat(b.isCancelled()).isFalse();
        registry.cancel(early);
        assertThatThrownBy(() -> registry.register(early)).isInstanceOf(RequestCancelledException.class);
        when(clock.instant()).thenReturn(start.plusSeconds(60));
        assertThat(registry.register(early).isCancelled()).isFalse();
        registry.cancel(second); // Active entries never expire at the tombstone deadline.
        assertThat(b.isCancelled()).isTrue();
        registry.finish(second);
        when(clock.instant()).thenReturn(start.plusSeconds(120));
        assertThat(registry.register(second).isCancelled()).isFalse();
    }

    @Test
    void filterRejectsCancelledAndMalformedIdsAndLeavesLegacyRequestsWorking() throws Exception {
        var registry = new RequestCancellationRegistry(Clock.systemUTC());
        var probe = new Probe();
        var mvc = MockMvcBuilders.standaloneSetup(probe, new RequestCancellationController(registry))
            .setControllerAdvice(new ApiExceptionHandler())
            .addFilters(new RequestCancellationFilter(registry, new ObjectMapper()))
            .build();
        UUID id = UUID.randomUUID();
        mvc.perform(post("/api/requests/" + id + "/cancel")).andExpect(status().isNoContent());
        mvc.perform(get("/api/probe").header("X-Request-ID", id)).andExpect(status().isConflict());
        assertThat(probe.calls).hasValue(0);
        mvc.perform(get("/api/probe").header("X-Request-ID", "1-1-1-1-1")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/requests/invalid/cancel")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/probe").header("X-Request-ID", UUID.randomUUID())).andExpect(status().isOk());
        mvc.perform(get("/api/probe")).andExpect(status().isOk());
        assertThat(probe.calls).hasValue(2);
        assertThat(RequestCancellation.current()).isNull();
    }

    @Test
    void cancellingOneOfTwoCallersDoesNotCancelSharedAnalysis() throws Exception {
        var shared = new SharedAnalysis<String, String>();
        var source = new CompletableFuture<String>();
        var started = new CountDownLatch(1);
        var calls = new AtomicInteger();
        var token = new RequestCancellation();
        var first = CompletableFuture.supplyAsync(() ->
            RequestCancellation.with(token, () ->
                shared.run("key", () -> {
                    calls.incrementAndGet();
                    started.countDown();
                    return RequestCancellation.await(source);
                })
            )
        );
        assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
        var second = new CompletableFuture<String>();
        Thread waiting = Thread.ofPlatform().start(() -> {
            try {
                second.complete(
                    shared.run("key", () -> {
                        calls.incrementAndGet();
                        return "unexpected";
                    })
                );
            } catch (Throwable error) {
                second.completeExceptionally(error);
            }
        });
        org.awaitility.Awaitility.await()
            .atMost(java.time.Duration.ofSeconds(2))
            .until(() -> waiting.getState() == Thread.State.TIMED_WAITING);
        token.cancel();
        assertThatThrownBy(() -> first.get(2, TimeUnit.SECONDS)).hasCauseInstanceOf(RequestCancelledException.class);
        source.complete("retained");
        assertThat(second.get(2, TimeUnit.SECONDS)).isEqualTo("retained");
        assertThat(calls).hasValue(1);
    }

    @Test
    void cancellationReachesSharedWorkBeforeTheCallerWaitLoopWakes() throws Exception {
        var shared = new SharedAnalysis<String, String>();
        var started = new CompletableFuture<RequestCancellation>();
        var release = new CompletableFuture<String>();
        var token = new RequestCancellation();
        var pending = CompletableFuture.supplyAsync(() ->
            RequestCancellation.with(token, () ->
                shared.run("key", () -> {
                    started.complete(RequestCancellation.current());
                    return release.join();
                })
            )
        );
        var worker = started.get(2, TimeUnit.SECONDS);
        token.cancel();
        assertThat(worker.isCancelled()).isTrue();
        release.complete("too late");
        assertThatThrownBy(() -> pending.get(2, TimeUnit.SECONDS)).hasCauseInstanceOf(RequestCancelledException.class);
        assertThat(shared.run("key", () -> "retry")).isEqualTo("retry");
    }

    @Test
    void cancelledContextCannotPublishAndIsRemovedAfterFailure() {
        var token = new RequestCancellation();
        var published = new AtomicInteger();
        assertThatThrownBy(() ->
            RequestCancellation.with(token, () -> {
                token.cancel();
                RequestCancellation.publish(published::incrementAndGet);
                return null;
            })
        ).isInstanceOf(RequestCancelledException.class);
        assertThat(published).hasValue(0);
        assertThat(RequestCancellation.current()).isNull();
    }

    @RestController
    static class Probe {

        final AtomicInteger calls = new AtomicInteger();

        @GetMapping("/api/probe")
        String call() {
            RequestCancellation.check();
            calls.incrementAndGet();
            return "ok";
        }
    }
}
