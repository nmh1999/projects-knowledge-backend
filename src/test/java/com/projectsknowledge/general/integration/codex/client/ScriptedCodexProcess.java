package com.projectsknowledge.general.integration.codex.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiPredicate;
import java.util.stream.Stream;

/** In-memory JSONL peer: tests exercise real transport IO without a Codex account or model turns. */
final class ScriptedCodexProcess extends Process {

    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicBoolean alive = new AtomicBoolean(true);
    private final CountDownLatch stopped = new CountDownLatch(1);
    private final CountDownLatch writesReleased = new CountDownLatch(1);
    volatile boolean stallWrites;
    private final BlockingQueue<JsonNode> incoming = new LinkedBlockingQueue<>();
    private final QueueInputStream stdout = new QueueInputStream();
    private final QueueInputStream stderr = new QueueInputStream();
    private final AtomicInteger threadSequence = new AtomicInteger();
    private final Thread server;
    final List<JsonNode> messages = new CopyOnWriteArrayList<>();
    volatile BiPredicate<ScriptedCodexProcess, JsonNode> handler = (peer, request) -> false;

    private final OutputStream stdin = new ByteArrayOutputStream() {
        @Override
        public synchronized void flush() throws IOException {
            if (stallWrites) {
                try {
                    writesReleased.await();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IOException(exception);
                }
            }
            if (!alive.get()) throw new IOException("Peer closed");
            String text = toString(StandardCharsets.UTF_8);
            reset();
            for (String line : text.split("\n")) {
                if (!line.isBlank()) incoming.add(mapper.readTree(line));
            }
        }
    };

    ScriptedCodexProcess() {
        server = Thread.ofVirtual().start(() -> {
            try {
                while (alive.get()) {
                    JsonNode message = incoming.take();
                    messages.add(message);
                    if (handler.test(this, message)) continue;
                    switch (message.path("method").asText()) {
                        case "initialize" -> reply(message, Map.of("userAgent", "test"));
                        case "thread/list" -> reply(message, Map.of("data", List.of()));
                        case "thread/start" -> reply(
                            message,
                            Map.of("thread", Map.of("id", "thread-" + threadSequence.incrementAndGet()))
                        );
                        case "thread/unsubscribe" -> reply(message, Map.of("status", "unsubscribed"));
                        default -> {}
                    }
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        });
    }

    void reply(JsonNode request, Object result) {
        send(Map.of("id", request.path("id").asLong(), "result", result));
    }

    void reject(JsonNode request) {
        send(
            Map.of(
                "id",
                request.path("id").asLong(),
                "error",
                Map.of("code", -32000, "message", "SECRET-DO-NOT-EXPOSE")
            )
        );
    }

    void complete(JsonNode request, String summary, boolean beforeAck) {
        String threadId = request.path("params").path("threadId").asText();
        String turnId = "turn-" + request.path("id").asLong();
        if (!beforeAck) reply(request, Map.of("turn", Map.of("id", turnId)));
        finalMessage(threadId, turnId, summary);
        send(
            Map.of(
                "method",
                "turn/completed",
                "params",
                Map.of("threadId", threadId, "turn", Map.of("id", turnId, "status", "completed"))
            )
        );
        if (beforeAck) reply(request, Map.of("turn", Map.of("id", turnId)));
    }

    void finalMessage(String threadId, String turnId, String summary) {
        try {
            String answer = mapper.writeValueAsString(Map.of("inScope", true, "answer", summary, "confidence", "high"));
            send(
                Map.of(
                    "method",
                    "item/completed",
                    "params",
                    Map.of(
                        "threadId",
                        threadId,
                        "turnId",
                        turnId,
                        "item",
                        Map.of("type", "agentMessage", "phase", "final_answer", "text", answer)
                    )
                )
            );
        } catch (IOException exception) {
            throw new AssertionError(exception);
        }
    }

    void send(Object message) {
        try {
            raw(mapper.writeValueAsString(message) + "\n");
        } catch (IOException exception) {
            throw new AssertionError(exception);
        }
    }

    void raw(String text) {
        stdout.add(text.getBytes(StandardCharsets.UTF_8));
    }

    List<JsonNode> requests(String method) {
        return messages
            .stream()
            .filter(message -> method.equals(message.path("method").asText()))
            .toList();
    }

    @Override
    public OutputStream getOutputStream() {
        return stdin;
    }

    @Override
    public InputStream getInputStream() {
        return stdout;
    }

    @Override
    public InputStream getErrorStream() {
        return stderr;
    }

    @Override
    public boolean isAlive() {
        return alive.get();
    }

    @Override
    public Stream<ProcessHandle> descendants() {
        return Stream.empty();
    }

    @Override
    public int waitFor() throws InterruptedException {
        stopped.await();
        return 0;
    }

    @Override
    public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
        return stopped.await(timeout, unit);
    }

    @Override
    public int exitValue() {
        if (alive.get()) throw new IllegalThreadStateException();
        return 0;
    }

    @Override
    public void destroy() {
        destroyForcibly();
    }

    @Override
    public Process destroyForcibly() {
        if (alive.compareAndSet(true, false)) {
            writesReleased.countDown();
            stdout.close();
            stderr.close();
            server.interrupt();
            stopped.countDown();
        }
        return this;
    }

    private static final class QueueInputStream extends InputStream {

        private final BlockingQueue<byte[]> chunks = new LinkedBlockingQueue<>();
        private byte[] chunk = new byte[0];
        private int offset;
        private boolean ended;

        void add(byte[] bytes) {
            chunks.add(Arrays.copyOf(bytes, bytes.length));
        }

        @Override
        public int read(byte[] bytes, int start, int length) throws IOException {
            if (length == 0) return 0;
            if (ended) return -1;
            if (offset == chunk.length) {
                try {
                    chunk = chunks.take();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IOException(exception);
                }
                offset = 0;
                if (chunk.length == 0) {
                    ended = true;
                    return -1;
                }
            }
            int size = Math.min(length, chunk.length - offset);
            System.arraycopy(chunk, offset, bytes, start, size);
            offset += size;
            return size;
        }

        @Override
        public int read() throws IOException {
            byte[] single = new byte[1];
            return read(single, 0, 1) == -1 ? -1 : Byte.toUnsignedInt(single[0]);
        }

        @Override
        public void close() {
            chunks.add(new byte[0]);
        }
    }
}
