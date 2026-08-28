package com.projectsknowledge.general.cancellation;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/** Cooperative cancellation: never interrupt a servlet thread or terminate a shared Codex connection. */
public final class RequestCancellation {

    private static final ThreadLocal<RequestCancellation> CURRENT = new ThreadLocal<>();
    private volatile boolean cancelled;
    private final List<Runnable> listeners = new ArrayList<>();

    public void cancel() {
        List<Runnable> callbacks;
        synchronized (this) {
            if (cancelled) return;
            cancelled = true;
            callbacks = List.copyOf(listeners);
            listeners.clear();
        }
        callbacks.forEach(Runnable::run);
    }

    /** Propagate cancellation before the endpoint acknowledges it, without holding the token lock. */
    public Runnable onCancel(Runnable callback) {
        synchronized (this) {
            if (!cancelled) {
                listeners.add(callback);
                return () -> {
                    synchronized (this) {
                        listeners.remove(callback);
                    }
                };
            }
        }
        callback.run();
        return () -> {};
    }

    public boolean isCancelled() {
        return cancelled;
    }

    public static RequestCancellation current() {
        return CURRENT.get();
    }

    public static void check() {
        var token = current();
        if (token != null && token.cancelled) throw new RequestCancelledException();
    }

    public static <T> T with(RequestCancellation token, Supplier<T> work) {
        var previous = CURRENT.get();
        CURRENT.set(token);
        try {
            check();
            return work.get();
        } finally {
            if (previous == null) CURRENT.remove();
            else CURRENT.set(previous);
        }
    }

    /** The cancellation/cache-write race has one winner; no cancelled result can be saved later. */
    public static void publish(Runnable action) {
        var token = current();
        if (token == null) {
            action.run();
            return;
        }
        synchronized (token) {
            check();
            action.run();
        }
    }

    public static <T> T await(CompletableFuture<T> result) {
        while (true) {
            check();
            try {
                T value = result.get(100, TimeUnit.MILLISECONDS);
                check();
                return value;
            } catch (TimeoutException ignored) {
                /* Recheck only this caller's cancellation. */
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new RequestCancelledException();
            } catch (ExecutionException failure) {
                if (failure.getCause() instanceof RuntimeException cause) throw cause;
                if (failure.getCause() instanceof Error cause) throw cause;
                throw new IllegalStateException("The analysis failed.", failure.getCause());
            }
        }
    }
}
