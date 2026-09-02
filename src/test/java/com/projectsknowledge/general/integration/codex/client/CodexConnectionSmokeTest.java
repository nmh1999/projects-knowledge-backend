package com.projectsknowledge.general.integration.codex.client;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.projectsknowledge.general.config.CodexRuntimeSettings;
import com.projectsknowledge.general.config.ProjectsKnowledgeProperties;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

/** Opt-in local protocol check. It never sends turn/start or prints the user's project catalog. */
@EnabledIfEnvironmentVariable(named = "CODEX_TRANSPORT_SMOKE_TEST", matches = "true")
class CodexConnectionSmokeTest {

    @TempDir
    Path workspace;

    @Test
    @Timeout(60)
    void checksWarmReuseAndEphemeralThreadCleanupWithoutModelCalls() throws Exception {
        var mapper = new ObjectMapper();
        var properties = new ProjectsKnowledgeProperties();
        var factory = spy(new CodexProcessFactory(properties));
        try (var transport = new CodexAppServerTransport(mapper, properties, factory)) {
            var settings = new CodexRuntimeSettings(
                mapper,
                workspace.resolve("codex-settings.json"),
                properties.getCodex().getModel(),
                properties.getCodex().getReasoningEffort()
            );
            var client = new CodexAppServerClient(
                mapper,
                properties,
                settings,
                transport,
                java.time.Clock.systemUTC(),
                com.projectsknowledge.general.cache.PersistentKnowledgeCache.disabled()
            );
            long started = System.nanoTime();
            client.listThreads();
            long coldFinished = System.nanoTime();
            client.listThreads();
            long warmFinished = System.nanoTime();
            var connection = transport.connection();
            var params = Map.of(
                "cwd",
                workspace.toAbsolutePath().toString(),
                "sandbox",
                "read-only",
                "approvalPolicy",
                "never",
                "ephemeral",
                true
            );
            String first = connection.request("thread/start", params, Duration.ofSeconds(15)).at("/thread/id").asText();
            String second = connection
                .request("thread/start", params, Duration.ofSeconds(15))
                .at("/thread/id")
                .asText();
            assertThat(first).isNotBlank().isNotEqualTo(second);
            assertThat(second).isNotBlank();
            connection.unsubscribe(first);
            connection.unsubscribe(second);
            client.listThreads();
            verify(factory, times(1)).start();
            System.out.printf(
                "Codex protocol smoke: coldListMs=%d, warmListMs=%d, processes=1, modelTurns=0%n",
                Duration.ofNanos(coldFinished - started).toMillis(),
                Duration.ofNanos(warmFinished - coldFinished).toMillis()
            );
        }
    }
}
