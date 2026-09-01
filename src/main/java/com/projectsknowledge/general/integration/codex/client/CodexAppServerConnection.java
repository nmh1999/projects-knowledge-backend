package com.projectsknowledge.general.integration.codex.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.projectsknowledge.general.cancellation.RequestCancellation;
import com.projectsknowledge.general.cancellation.RequestCancelledException;
import com.projectsknowledge.general.exception.KnowledgeException;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;

/** Single reader routes RPC replies by request ID and final answers by both thread and turn ID. */
@Slf4j
final class CodexAppServerConnection implements AutoCloseable {

    private final ObjectMapper mapper;
    private final Process process;
    private final BufferedWriter input;
    private final BlockingQueue<JsonNode> outgoing = new LinkedBlockingQueue<>(128);
    private final Thread writer;
    private final AtomicLong nextId = new AtomicLong();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final CompletableFuture<Void> terminated = new CompletableFuture<>();
    private final Map<Long, CompletableFuture<JsonNode>> requests = new ConcurrentHashMap<>();
    private final Map<String, TurnResult> turns = new ConcurrentHashMap<>();

    CodexAppServerConnection(ObjectMapper mapper, Process process) {
        this.mapper = mapper;
        this.process = process;
        this.input = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        this.writer = Thread.ofVirtual().name("codex-rpc-writer").start(this::writeMessages);
        Thread.ofVirtual().name("codex-rpc-reader").start(this::readMessages);
        Thread.ofVirtual()
            .name("codex-stderr-drain")
            .start(() -> {
                // Drain without retaining or exposing logs that may contain repository data or credentials.
                try (var errors = process.getErrorStream()) {
                    byte[] buffer = new byte[4096];
                    while (errors.read(buffer) != -1) {}
                } catch (IOException ignored) {}
            });
    }

    void initialize(Duration timeout) {
        request(
            "initialize",
            Map.of(
                "clientInfo",
                Map.of("name", "projects_knowledge", "title", "Projects Knowledge", "version", "0.1.0"),
                "capabilities",
                Map.of("experimentalApi", true)
            ),
            timeout
        );
        ObjectNode initialized = mapper.createObjectNode().put("method", "initialized");
        initialized.set("params", mapper.createObjectNode());
        write(initialized);
    }

    boolean isHealthy() {
        return !closed.get() && process.isAlive();
    }

    int activeTurns() {
        return turns.size();
    }

    JsonNode request(String method, Object params, Duration timeout) {
        long id = nextId.incrementAndGet();
        var response = new CompletableFuture<JsonNode>();
        requests.put(id, response);
        try {
            ObjectNode message = mapper.createObjectNode().put("id", id).put("method", method);
            message.set("params", mapper.valueToTree(params));
            write(message);
            return await(response, timeout);
        } finally {
            requests.remove(id);
        }
    }

    String runTurn(String threadId, Map<String, Object> params, Duration timeout) {
        TurnResult turn = new TurnResult();
        if (turns.putIfAbsent(threadId, turn) != null) throw failure("A turn is already active for this conversation.");
        long deadline = System.nanoTime() + timeout.toNanos();
        try {
            RequestCancellation.check();
            JsonNode accepted = request("turn/start", params, remaining(deadline));
            String turnId = accepted.path("turn").path("id").asText();
            if (turnId.isBlank()) {
                fail("Codex returned a missing turn identifier.");
                throw failure("Codex returned a missing turn identifier.");
            }
            turn.bind(turnId);
            try {
                return awaitTurn(turn.answer, deadline);
            } catch (RequestCancelledException cancelled) {
                interruptTurn(threadId, turnId, turn);
                throw cancelled;
            }
        } finally {
            turns.remove(threadId, turn);
        }
    }

    /** Interrupt only this turn; other requests keep using the shared connection. */
    private void interruptTurn(String threadId, String turnId, TurnResult turn) {
        if (turn.finished.isDone()) return;
        try {
            request("turn/interrupt", Map.of("threadId", threadId, "turnId", turnId), Duration.ofSeconds(2));
            await(turn.finished, Duration.ofSeconds(2));
        } catch (RuntimeException failure) {
            // If interruption cannot be confirmed, do not leave an owned model process running indefinitely.
            if (!turn.finished.isDone()) fail("Codex connection was reset after cancellation failed.");
        }
    }

    private String awaitTurn(CompletableFuture<String> answer, long deadline) {
        while (true) {
            RequestCancellation.check();
            if (answer.isDone() || System.nanoTime() >= deadline) return await(answer, remaining(deadline));
            try {
                String result = answer.get(
                    Math.min(TimeUnit.MILLISECONDS.toNanos(100), remaining(deadline).toNanos()),
                    TimeUnit.NANOSECONDS
                );
                RequestCancellation.check();
                return result;
            } catch (TimeoutException ignored) {
                // Poll the request token without interrupting the shared RPC reader or writer.
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new RequestCancelledException();
            } catch (ExecutionException exception) {
                RequestCancellation.check();
                if (exception.getCause() instanceof KnowledgeException known) throw known;
                throw failure("Codex request failed.");
            }
        }
    }

    void unsubscribe(String threadId) {
        if (!isHealthy()) return;
        try {
            request("thread/unsubscribe", Map.of("threadId", threadId), Duration.ofSeconds(2));
        } catch (RuntimeException exception) {
            // Cleanup must not replace an already completed answer or leave a leaking transport reusable.
            log.warn("Codex conversation cleanup failed; resetting the connection.");
            fail("Codex connection was reset after cleanup failed.");
        }
    }

    private Duration remaining(long deadline) {
        return Duration.ofNanos(Math.max(1, deadline - System.nanoTime()));
    }

    private <T> T await(CompletableFuture<T> result, Duration timeout) {
        try {
            return result.get(Math.max(1, timeout.toNanos()), TimeUnit.NANOSECONDS);
        } catch (TimeoutException exception) {
            // Kill this private process so an ambiguous timed-out turn cannot continue consuming tokens.
            fail("Timed out waiting for Codex. Retry the request when ready.");
            throw failure("Timed out waiting for Codex. Retry the request when ready.");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            fail("Codex request was interrupted.");
            throw failure("Codex request was interrupted.");
        } catch (ExecutionException exception) {
            if (exception.getCause() instanceof KnowledgeException known) throw known;
            throw failure("Codex request failed.");
        }
    }

    private void readMessages() {
        try (var output = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = output.readLine()) != null) {
                if (line.isBlank()) continue;
                JsonNode message = mapper.readTree(line);
                if (message == null || !message.isObject()) throw new IOException("Invalid protocol message");
                dispatch(message);
            }
            fail("Codex connection closed. Retry the request when ready.");
        } catch (IOException | RuntimeException exception) {
            fail("Codex connection failed. Retry the request when ready.");
        }
    }

    private void dispatch(JsonNode message) {
        if (message.has("id")) {
            if (message.has("method")) {
                // This read-only client never approves tools or answers authentication/user-input callbacks.
                ObjectNode rejected = mapper.createObjectNode();
                rejected.set("id", message.get("id"));
                rejected.set(
                    "error",
                    mapper.valueToTree(Map.of("code", -32601, "message", "Client callback not supported."))
                );
                write(rejected);
                return;
            }
            CompletableFuture<JsonNode> pending = requests.get(message.path("id").asLong(-1));
            if (pending == null) return;
            if (message.has("error")) pending.completeExceptionally(
                failure("Codex rejected the request. Check its availability and sign-in.")
            );
            else pending.complete(message.path("result"));
            return;
        }
        String method = message.path("method").asText();
        if (!"item/completed".equals(method) && !"turn/completed".equals(method)) return;
        JsonNode params = message.path("params");
        TurnResult turn = turns.get(params.path("threadId").asText());
        if (turn != null) turn.receive(method, params);
    }

    private void write(JsonNode message) {
        if (!isHealthy()) {
            fail("Codex connection is unavailable. Retry the request when ready.");
            throw failure("Codex connection is unavailable. Retry the request when ready.");
        }
        // Queueing keeps both RPC deadlines and the reader responsive if the process stops reading stdin.
        if (!outgoing.offer(message)) {
            fail("Codex connection is overloaded. Retry when ready.");
            throw failure("Codex connection is overloaded. Retry when ready.");
        }
    }

    private void writeMessages() {
        try (input) {
            while (!closed.get()) {
                JsonNode message = outgoing.take();
                if (closed.get()) return;
                input.write(mapper.writeValueAsString(message));
                input.newLine();
                input.flush();
            }
        } catch (IOException exception) {
            fail("Could not send the Codex request. It has not been retried automatically.");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            fail("Codex writer was interrupted.");
        }
    }

    private void fail(String message) {
        if (!closed.compareAndSet(false, true)) return;
        try {
            // Only descendants of this private process are owned here, never desktop/IDE Codex processes.
            List<ProcessHandle> children = process.descendants().toList();
            children.reversed().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            process.waitFor(2, TimeUnit.SECONDS);
            for (ProcessHandle child : children) {
                child.onExit().get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException ignored) {
            // Termination was already requested; do not make a failed request wait indefinitely.
        } finally {
            outgoing.clear();
            writer.interrupt();
            KnowledgeException error = failure(message);
            requests.values().forEach(request -> request.completeExceptionally(error));
            turns
                .values()
                .forEach(turn -> {
                    turn.answer.completeExceptionally(error);
                    turn.finished.completeExceptionally(error);
                });
            requests.clear();
            turns.clear();
            terminated.complete(null);
        }
    }

    @Override
    public void close() {
        fail("Codex connection was closed.");
        try {
            // Another reader may already be closing the process; do not return before its bounded cleanup.
            terminated.get(2, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException ignored) {}
    }

    private static KnowledgeException failure(String message) {
        return new KnowledgeException(HttpStatus.SERVICE_UNAVAILABLE, message);
    }

    private static final class TurnResult {

        private final CompletableFuture<String> answer = new CompletableFuture<>();
        private final CompletableFuture<Void> finished = new CompletableFuture<>();
        private final List<Event> earlyEvents = new ArrayList<>();
        private String turnId;
        private String finalText = "";

        synchronized void bind(String id) {
            turnId = id;
            earlyEvents.forEach(event -> accept(event.method(), event.params()));
            earlyEvents.clear();
        }

        synchronized void receive(String method, JsonNode params) {
            if (answer.isDone()) return;
            if ("item/completed".equals(method) && !isFinalMessage(params.path("item"))) return;
            if (turnId == null) {
                // Notifications can arrive before the turn/start response; retain only relevant events.
                if (earlyEvents.size() >= 32) {
                    throw new IllegalStateException("Unexpected Codex event sequence.");
                } else earlyEvents.add(new Event(method, params));
                return;
            }
            accept(method, params);
        }

        private void accept(String method, JsonNode params) {
            if (answer.isDone()) return;
            String eventTurn = "turn/completed".equals(method)
                ? params.path("turn").path("id").asText()
                : params.path("turnId").asText();
            if (!turnId.equals(eventTurn)) return;
            if ("turn/completed".equals(method)) finished.complete(null);
            if ("item/completed".equals(method)) {
                finalText = params.path("item").path("text").asText("");
            } else if (!"completed".equals(params.path("turn").path("status").asText())) {
                answer.completeExceptionally(failure("Codex could not complete the answer."));
            } else if (finalText.isBlank()) {
                answer.completeExceptionally(failure("Codex completed without an answer."));
            } else answer.complete(finalText);
        }

        private boolean isFinalMessage(JsonNode item) {
            String phase = item.path("phase").asText("");
            return (
                "agentMessage".equals(item.path("type").asText()) && (phase.isEmpty() || "final_answer".equals(phase))
            );
        }

        private record Event(String method, JsonNode params) {}
    }
}
