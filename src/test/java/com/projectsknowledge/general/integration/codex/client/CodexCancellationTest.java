package com.projectsknowledge.general.integration.codex.client;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.projectsknowledge.general.cancellation.RequestCancellation;
import com.projectsknowledge.general.cancellation.RequestCancelledException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(12)
class CodexCancellationTest {

    @Test
    void interruptsOnlyTheOwnedTurnAndKeepsOtherTurnsAndConnectionAlive() throws Exception {
        var peer = new ScriptedCodexProcess();
        var started = new CountDownLatch(2);
        peer.handler = (process, request) -> {
            if ("turn/start".equals(request.path("method").asText())) {
                process.reply(request, Map.of("turn", Map.of("id", "turn-" + request.path("id").asLong())));
                started.countDown();
                return true;
            }
            return interrupt(process, request);
        };
        try (
            var connection = new CodexAppServerConnection(new ObjectMapper(), peer);
            var workers = Executors.newVirtualThreadPerTaskExecutor()
        ) {
            var token = new RequestCancellation();
            var cancelled = workers.submit(() ->
                RequestCancellation.with(token, () ->
                    connection.runTurn("cancel", Map.of("threadId", "cancel"), Duration.ofSeconds(6))
                )
            );
            var other = workers.submit(() ->
                connection.runTurn("keep", Map.of("threadId", "keep"), Duration.ofSeconds(6))
            );
            assertThat(started.await(3, TimeUnit.SECONDS)).isTrue();
            token.cancel();
            assertThatThrownBy(() -> cancelled.get(3, TimeUnit.SECONDS)).hasCauseInstanceOf(
                RequestCancelledException.class
            );
            JsonNode stopped = peer
                .requests("turn/start")
                .stream()
                .filter(r -> r.at("/params/threadId").asText().equals("cancel"))
                .findFirst()
                .orElseThrow();
            assertThat(peer.requests("turn/interrupt"))
                .singleElement()
                .satisfies(request -> {
                    assertThat(request.at("/params/threadId").asText()).isEqualTo("cancel");
                    assertThat(request.at("/params/turnId").asText()).isEqualTo("turn-" + stopped.path("id").asLong());
                });
            JsonNode retained = peer
                .requests("turn/start")
                .stream()
                .filter(r -> r.at("/params/threadId").asText().equals("keep"))
                .findFirst()
                .orElseThrow();
            peer.complete(retained, "Kept answer", false);
            assertThat(other.get(3, TimeUnit.SECONDS)).contains("Kept answer");
            assertThat(connection.isHealthy()).isTrue();
            assertThat(connection.request("thread/list", Map.of(), Duration.ofSeconds(1)).path("data")).isEmpty();
        }
    }

    @Test
    void cancellationDuringStartUsesTheAcknowledgedIdAndDoesNotResetConnection() throws Exception {
        var peer = new ScriptedCodexProcess();
        var started = new CountDownLatch(1);
        peer.handler = (process, request) -> {
            if ("turn/start".equals(request.path("method").asText())) {
                started.countDown();
                return true;
            }
            return interrupt(process, request);
        };
        try (
            var connection = new CodexAppServerConnection(new ObjectMapper(), peer);
            var workers = Executors.newVirtualThreadPerTaskExecutor()
        ) {
            var token = new RequestCancellation();
            var pending = workers.submit(() ->
                RequestCancellation.with(token, () ->
                    connection.runTurn("sample", Map.of("threadId", "sample"), Duration.ofSeconds(6))
                )
            );
            assertThat(started.await(3, TimeUnit.SECONDS)).isTrue();
            token.cancel();
            peer.reply(peer.requests("turn/start").getFirst(), Map.of("turn", Map.of("id", "delayed")));
            assertThatThrownBy(() -> pending.get(3, TimeUnit.SECONDS)).hasCauseInstanceOf(
                RequestCancelledException.class
            );
            assertThat(peer.requests("turn/interrupt"))
                .singleElement()
                .satisfies(request -> assertThat(request.at("/params/turnId").asText()).isEqualTo("delayed"));
            assertThat(connection.isHealthy()).isTrue();
        }
    }

    @Test
    void alreadyCancelledRequestNeverStartsAModelTurn() {
        var peer = new ScriptedCodexProcess();
        try (var connection = new CodexAppServerConnection(new ObjectMapper(), peer)) {
            var token = new RequestCancellation();
            token.cancel();
            assertThatThrownBy(() ->
                RequestCancellation.with(token, () -> connection.runTurn("unused", Map.of(), Duration.ofSeconds(1)))
            ).isInstanceOf(RequestCancelledException.class);
            assertThat(peer.requests("turn/start")).isEmpty();
        }
    }

    private boolean interrupt(ScriptedCodexProcess peer, JsonNode request) {
        if (!"turn/interrupt".equals(request.path("method").asText())) return false;
        peer.reply(request, Map.of());
        peer.send(
            Map.of(
                "method",
                "turn/completed",
                "params",
                Map.of(
                    "threadId",
                    request.at("/params/threadId").asText(),
                    "turn",
                    Map.of("id", request.at("/params/turnId").asText(), "status", "interrupted")
                )
            )
        );
        return true;
    }
}
