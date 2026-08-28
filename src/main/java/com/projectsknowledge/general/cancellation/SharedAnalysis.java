package com.projectsknowledge.general.cancellation;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/** Coalesces identical analyses; cancelling one caller stops the work only when no caller still needs it. */
public final class SharedAnalysis<K, V> {

    private final Map<K, Work<V>> running = new HashMap<>();

    public V run(K key, Supplier<V> analyze) {
        RequestCancellation.check();
        Work<V> work;
        boolean start;
        synchronized (running) {
            work = running.get(key);
            start = work == null;
            if (start) {
                work = new Work<>();
                running.put(key, work);
            }
            work.callers++;
        }
        final Work<V> selected = work;
        var released = new AtomicBoolean();
        Runnable release = () -> {
            synchronized (running) {
                if (released.compareAndSet(false, true) && --selected.callers == 0 && !selected.result.isDone()) {
                    selected.cancellation.cancel();
                    running.remove(key, selected); // A retry never joins work that has been cancelled.
                }
            }
        };
        var caller = RequestCancellation.current();
        Runnable unsubscribe = caller == null ? () -> {} : caller.onCancel(release);
        if (start) {
            Thread.ofVirtual()
                .name("shared-repository-analysis")
                .start(() -> {
                    try {
                        RequestCancellation.with(selected.cancellation, () -> {
                            V result = analyze.get();
                            synchronized (running) {
                                RequestCancellation.publish(() -> {
                                    running.remove(key, selected);
                                    selected.result.complete(result);
                                });
                            }
                            return null;
                        });
                    } catch (Throwable failure) {
                        synchronized (running) {
                            running.remove(key, selected);
                            selected.result.completeExceptionally(failure);
                        }
                    } finally {
                        synchronized (running) {
                            running.remove(key, selected);
                        }
                    }
                });
        }
        try {
            return RequestCancellation.await(selected.result);
        } finally {
            unsubscribe.run();
            release.run();
        }
    }

    private static final class Work<T> {

        private final RequestCancellation cancellation = new RequestCancellation();
        private final CompletableFuture<T> result = new CompletableFuture<>();
        private int callers;
    }
}
