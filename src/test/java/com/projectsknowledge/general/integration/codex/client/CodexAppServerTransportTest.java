package com.projectsknowledge.general.integration.codex.client;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.projectsknowledge.business.knowledge.enums.SearchMode;
import com.projectsknowledge.general.config.ProjectsKnowledgeProperties;
import com.projectsknowledge.general.exception.KnowledgeException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(15)
class CodexAppServerTransportTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final ProjectsKnowledgeProperties properties = new ProjectsKnowledgeProperties();
    private final CodexProcessFactory processes = mock(CodexProcessFactory.class);
    private final ScriptedCodexProcess first = new ScriptedCodexProcess();
    private final ScriptedCodexProcess second = new ScriptedCodexProcess();
    private final CodexAppServerTransport transport = new CodexAppServerTransport(mapper, properties, processes);
    private final CodexAppServerClient client = new CodexAppServerClient(mapper, properties, transport);

    @BeforeEach
    void setup() throws Exception {
        when(processes.start()).thenReturn(first, second);
        properties.getCodex().setTimeoutSeconds(3);
    }

    @AfterEach
    void cleanup() {
        transport.close();
        first.destroy();
        second.destroy();
    }

    @Test
    void reusesOneInitializedProcessButStartsIndependentReadOnlyConversations() throws Exception {
        first.handler = (peer, request) -> {
            if (!"turn/start".equals(request.path("method").asText())) return false;
            peer.complete(request, prompt(request), true);
            return true;
        };
        client.listThreads();
        assertThat(ask("first question")).contains("first question");
        assertThat(ask("second question")).contains("second question");
        client.listThreads();
        verify(processes, times(1)).start();
        assertThat(first.requests("initialize")).hasSize(1);
        assertThat(first.requests("initialized")).hasSize(1);
        assertThat(first.requests("thread/start"))
            .hasSize(2)
            .allSatisfy(request -> {
                assertThat(request.at("/params/ephemeral").asBoolean()).isTrue();
                assertThat(request.at("/params/sandbox").asText()).isEqualTo("read-only");
                assertThat(request.at("/params/approvalPolicy").asText()).isEqualTo("never");
            });
        assertThat(first.requests("turn/start")).allSatisfy(request -> {
            assertThat(request.at("/params/effort").asText()).isEqualTo("medium");
            assertThat(request.at("/params/outputSchema/properties").size()).isEqualTo(3);
        });
        assertThat(first.requests("turn/start"))
            .extracting(request -> request.at("/params/threadId").asText())
            .doesNotHaveDuplicates();
        assertThat(first.requests("thread/unsubscribe")).hasSize(2);
        assertThat(
            first.messages
                .stream()
                .filter(request -> request.has("id"))
                .map(request -> request.path("id").asLong())
        ).doesNotHaveDuplicates();
    }

    @Test
    void routesInterleavedAnswersByThreadAndTurnAndDoesNotBlockCatalogRequests() throws Exception {
        var started = new CountDownLatch(2);
        List<JsonNode> pending = new CopyOnWriteArrayList<>();
        first.handler = (peer, request) -> {
            if (!"turn/start".equals(request.path("method").asText())) return false;
            pending.add(request);
            started.countDown();
            return true;
        };
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var alpha = workers.submit(() -> ask("alpha"));
            var beta = workers.submit(() -> ask("beta"));
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(client.listThreads()).isEmpty();
            for (JsonNode request : pending.reversed()) {
                first.finalMessage("unrelated-thread", "unrelated-turn", "WRONG");
                first.finalMessage(request.at("/params/threadId").asText(), "stale-turn", "WRONG");
                first.complete(request, prompt(request), true);
            }
            assertThat(alpha.get(5, TimeUnit.SECONDS)).contains("alpha").doesNotContain("beta", "WRONG");
            assertThat(beta.get(5, TimeUnit.SECONDS)).contains("beta").doesNotContain("alpha", "WRONG");
        }
        verify(processes, times(1)).start();
    }

    @Test
    void replacesAnIdleDeadProcessOnTheNextRequest() throws Exception {
        client.listThreads();
        first.destroy();
        client.listThreads();
        verify(processes, times(2)).start();
        assertThat(second.requests("initialize")).hasSize(1);
    }

    @Test
    void disconnectFailsAllInFlightRequestsWithoutReplayingAnyQuestion() throws Exception {
        var started = new CountDownLatch(2);
        first.handler = (peer, request) -> {
            if (!"turn/start".equals(request.path("method").asText())) return false;
            peer.reply(request, Map.of("turn", Map.of("id", "turn-" + request.path("id").asLong())));
            started.countDown();
            return true;
        };
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var a = workers.submit(() -> ask("alpha"));
            var b = workers.submit(() -> ask("beta"));
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            first.destroy();
            assertThatThrownBy(() -> a.get(5, TimeUnit.SECONDS)).hasCauseInstanceOf(KnowledgeException.class);
            assertThatThrownBy(() -> b.get(5, TimeUnit.SECONDS)).hasCauseInstanceOf(KnowledgeException.class);
        }
        verify(processes, times(1)).start();
        client.listThreads();
        verify(processes, times(2)).start();
        assertThat(second.requests("turn/start")).isEmpty();
    }

    @Test
    void timeoutIsBoundedEvenWhenStdoutContainsAnUnfinishedJsonLine() {
        var connection = transport.connection();
        first.handler = (peer, request) -> {
            if (!"turn/start".equals(request.path("method").asText())) return false;
            peer.raw("{\"id\":");
            return true;
        };
        assertThatThrownBy(() -> connection.runTurn("sample", Map.of("threadId", "sample"), Duration.ofMillis(150)))
            .isInstanceOf(KnowledgeException.class)
            .hasMessageContaining("Timed out");
        assertThat(first.isAlive()).isFalse();
        assertThat(first.requests("turn/start")).hasSize(1);
    }

    @Test
    void initializationFailureIsNeverCachedAndIsCleanedUp() throws Exception {
        first.handler = (peer, request) -> {
            if (!"initialize".equals(request.path("method").asText())) return false;
            peer.reject(request);
            return true;
        };
        assertThatThrownBy(client::listThreads)
            .isInstanceOf(KnowledgeException.class)
            .hasMessageNotContaining("SECRET");
        assertThat(first.isAlive()).isFalse();
        client.listThreads();
        verify(processes, times(2)).start();
    }

    @Test
    void rpcRejectionDoesNotReplayTheQuestionOrPoisonAHealthyConnection() throws Exception {
        first.handler = (peer, request) -> {
            if (!"turn/start".equals(request.path("method").asText())) return false;
            peer.reject(request);
            return true;
        };
        assertThatThrownBy(() -> ask("sample"))
            .isInstanceOf(KnowledgeException.class)
            .hasMessageNotContaining("SECRET");
        client.listThreads();
        verify(processes, times(1)).start();
        assertThat(first.requests("turn/start")).hasSize(1);
        assertThat(first.requests("thread/unsubscribe")).hasSize(1);
    }

    @Test
    void cleanupFailureRetainsTheAnswerButDiscardsTheConnection() throws Exception {
        first.handler = (peer, request) -> {
            if ("turn/start".equals(request.path("method").asText())) {
                peer.complete(request, "verified answer", false);
                return true;
            }
            if ("thread/unsubscribe".equals(request.path("method").asText())) {
                peer.reject(request);
                return true;
            }
            return false;
        };
        assertThat(ask("sample")).isEqualTo("verified answer");
        assertThat(first.isAlive()).isFalse();
        client.listThreads();
        verify(processes, times(2)).start();
    }

    @Test
    void unsupportedServerCallbacksAreRejectedInsteadOfGrantingApproval() throws Exception {
        var rejected = new CountDownLatch(1);
        first.handler = (peer, request) -> {
            if ("turn/start".equals(request.path("method").asText())) {
                peer.send(
                    Map.of("id", "approval", "method", "item/commandExecution/requestApproval", "params", Map.of())
                );
                peer.complete(request, "done", false);
                return true;
            }
            if ("approval".equals(request.path("id").asText())) {
                assertThat(request.at("/error/code").asInt()).isEqualTo(-32601);
                rejected.countDown();
                return true;
            }
            return false;
        };
        assertThat(ask("sample")).isEqualTo("done");
        assertThat(rejected.await(3, TimeUnit.SECONDS)).isTrue();
        verify(processes, times(1)).start();
    }

    @Test
    void shutdownUnblocksAnActiveRequestAndNeverStartsAnotherProcess() throws Exception {
        var started = new CountDownLatch(1);
        first.handler = (peer, request) -> {
            if (!"turn/start".equals(request.path("method").asText())) return false;
            started.countDown();
            return true;
        };
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var answer = workers.submit(() -> ask("sample"));
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            transport.close();
            assertThatThrownBy(() -> answer.get(5, TimeUnit.SECONDS)).hasCauseInstanceOf(KnowledgeException.class);
        }
        assertThat(first.isAlive()).isFalse();
        assertThatThrownBy(client::listThreads).isInstanceOf(KnowledgeException.class);
        verify(processes, times(1)).start();
    }

    @Test
    void disabledIntegrationDoesNotStartAProcess() throws Exception {
        properties.getCodex().setEnabled(false);
        assertThatThrownBy(client::listThreads).isInstanceOf(KnowledgeException.class);
        verifyNoInteractions(processes);
    }

    @Test
    void invalidProtocolFailsPromptlyAndTheNextRequestGetsAFreshConnection() throws Exception {
        first.handler = (peer, request) -> {
            if (!"thread/list".equals(request.path("method").asText())) return false;
            peer.raw("not-json\n");
            return true;
        };
        assertThatThrownBy(client::listThreads).isInstanceOf(KnowledgeException.class);
        assertThat(first.isAlive()).isFalse();
        client.listThreads();
        verify(processes, times(2)).start();
    }

    @Test
    void aFailedTurnCannotReturnAnEarlierAnswerOrACommentaryMessage() throws Exception {
        first.handler = (peer, request) -> {
            if (!"turn/start".equals(request.path("method").asText())) return false;
            if (prompt(request).contains("good")) peer.complete(request, "verified", false);
            else {
                String threadId = request.at("/params/threadId").asText();
                String turnId = "turn-" + request.path("id").asLong();
                peer.reply(request, Map.of("turn", Map.of("id", turnId)));
                peer.send(
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
                            Map.of("type", "agentMessage", "phase", "commentary", "text", "not an answer")
                        )
                    )
                );
                peer.send(
                    Map.of(
                        "method",
                        "turn/completed",
                        "params",
                        Map.of("threadId", threadId, "turn", Map.of("id", turnId, "status", "failed"))
                    )
                );
            }
            return true;
        };
        assertThat(ask("good")).isEqualTo("verified");
        assertThatThrownBy(() -> ask("bad"))
            .isInstanceOf(KnowledgeException.class)
            .hasMessageContaining("could not complete");
        client.listThreads();
        assertThat(first.requests("thread/unsubscribe")).hasSize(2);
        verify(processes, times(1)).start();
    }

    @Test
    void missingThreadIdentifierDiscardsTheProcessWithoutStartingATurn() throws Exception {
        first.handler = (peer, request) -> {
            if (!"thread/start".equals(request.path("method").asText())) return false;
            peer.reply(request, Map.of("thread", Map.of()));
            return true;
        };
        assertThatThrownBy(() -> ask("sample")).isInstanceOf(KnowledgeException.class);
        assertThat(first.isAlive()).isFalse();
        assertThat(first.requests("turn/start")).isEmpty();
        client.listThreads();
        verify(processes, times(2)).start();
    }

    @Test
    void rpcTimeoutAlsoCoversAProcessThatStopsReadingStdin() {
        var connection = transport.connection();
        first.stallWrites = true;
        assertThatThrownBy(() -> connection.request("thread/list", Map.of(), Duration.ofMillis(150)))
            .isInstanceOf(KnowledgeException.class)
            .hasMessageContaining("Timed out");
        assertThat(first.isAlive()).isFalse();
    }

    private String ask(String question) {
        return client.ask(List.of(Path.of(".")), question, "en", SearchMode.BASIC).answer();
    }

    private static String prompt(JsonNode request) {
        return request.at("/params/input/0/text").asText();
    }
}
