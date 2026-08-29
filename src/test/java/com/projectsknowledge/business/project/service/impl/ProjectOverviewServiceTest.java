package com.projectsknowledge.business.project.service.impl;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

import com.projectsknowledge.business.project.entity.Project;
import com.projectsknowledge.business.project.schema.response.DtoProject;
import com.projectsknowledge.business.project.service.ProjectOverviewService;
import com.projectsknowledge.general.cancellation.RequestCancellation;
import com.projectsknowledge.general.cancellation.RequestCancelledException;
import com.projectsknowledge.general.cache.PersistentKnowledgeCache;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.projectsknowledge.general.config.ProjectsKnowledgeProperties;
import com.projectsknowledge.general.exception.KnowledgeException;
import com.projectsknowledge.general.integration.codex.client.CodexAppServerClient;
import com.projectsknowledge.general.integration.codex.schema.response.DtoCodexProjectOverview;
import com.projectsknowledge.general.integration.codex.schema.response.DtoCodexProjectOverview.IntegrationEvidence;
import com.projectsknowledge.general.scanner.RepositoryScanner;
import com.projectsknowledge.general.scanner.RepositoryScannerTest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;

class ProjectOverviewServiceTest {

    @TempDir
    Path root;

    private final CodexAppServerClient client = mock(CodexAppServerClient.class);
    private final ProjectsKnowledgeProperties properties = new ProjectsKnowledgeProperties();
    private final Clock clock = mock(Clock.class);
    private final Instant start = Instant.parse("2026-01-01T00:00:00Z");
    private ProjectOverviewService service;
    private Project project;

    @BeforeEach
    void setUp() throws Exception {
        when(clock.instant()).thenReturn(start);
        var repository = RepositoryScannerTest.repository("repo", root);
        project = new Project();
        project.setId("project");
        project.setName("Runtime project");
        project.setRepositories(List.of(repository));
        Files.createDirectories(root.resolve("arbitrary/place"));
        Files.writeString(root.resolve("arbitrary/place/Remote.java"), "class Remote {}\n");
        service = new ProjectOverviewServiceImpl(client, new RepositoryScanner(properties), properties, clock);
        when(client.overview(anyList())).thenReturn(
            result(List.of(new IntegrationEvidence("Orbit", repository.getName(), "arbitrary/place/Remote.java")))
        );
    }

    private DtoCodexProjectOverview result(List<IntegrationEvidence> integrations) {
        return new DtoCodexProjectOverview(
            List.of(),
            List.of(" Spring Boot "),
            List.of("PostgreSQL"),
            List.of("Orders"),
            integrations,
            List.of(),
            List.of()
        );
    }

    @Test
    void cancelledRefreshKeepsThePreviousOverviewAndDate() throws Exception {
        var old = service.get(project);
        var started = new CountDownLatch(1);
        var stopped = new CountDownLatch(1);
        when(client.overview(anyList())).thenAnswer(call -> {
            started.countDown();
            try {
                return RequestCancellation.await(new CompletableFuture<>());
            } finally {
                stopped.countDown();
            }
        });
        var token = new RequestCancellation();
        var pending = CompletableFuture.supplyAsync(() ->
            RequestCancellation.with(token, () -> service.refresh(project))
        );
        assertThat(started.await(3, TimeUnit.SECONDS)).isTrue();
        token.cancel();
        assertThatThrownBy(() -> pending.get(3, TimeUnit.SECONDS)).hasCauseInstanceOf(RequestCancelledException.class);
        assertThat(stopped.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(service.get(project)).isSameAs(old);
    }

    @Test
    void cachesWholePageForExactlyTwentyFourHoursFromCompletion() {
        assertThat(properties.getCodex().getOverviewCacheSeconds()).isEqualTo(86_400);
        var first = service.get(project);
        assertThat(first.overviewUpdatedAt()).isEqualTo(start);
        assertThat(first.overview().integrations()).containsExactly("Orbit");
        assertThat(first.overview().backend()).containsExactly("Spring Boot");
        assertThat(first.repositories().getFirst().languages()).contains("Java");
        when(clock.instant()).thenReturn(start.plusSeconds(86_399));
        assertThat(service.get(project)).isSameAs(first);
        verify(client, times(1)).overview(anyList());
        when(clock.instant()).thenReturn(start.plusSeconds(86_400));
        var expired = service.get(project);
        assertThat(expired).isNotSameAs(first);
        assertThat(expired.overviewUpdatedAt()).isEqualTo(start.plusSeconds(86_400));
        verify(client, times(2)).overview(anyList());
    }

    @Test
    void restoresOverviewAfterServiceRestartWithoutAnotherCodexTurn() {
        Path database = root.resolve("local-cache/knowledge.db");
        var persistent = new PersistentKnowledgeCache(new ObjectMapper().findAndRegisterModules(), database, true);
        persistent.initialize();
        var firstService = new ProjectOverviewServiceImpl(
            client,
            new RepositoryScanner(properties),
            properties,
            clock,
            persistent
        );
        var first = firstService.get(project);

        var restartedCache = new PersistentKnowledgeCache(
            new ObjectMapper().findAndRegisterModules(),
            database,
            true
        );
        restartedCache.initialize();
        var restartedService = new ProjectOverviewServiceImpl(
            client,
            new RepositoryScanner(properties),
            properties,
            clock,
            restartedCache
        );
        var restored = restartedService.get(project);

        assertThat(restored).isEqualTo(first).isNotSameAs(first);
        verify(client, times(1)).overview(anyList());
    }

    @Test
    void doesNotCacheFailedAnalysisAndRetriesNormally() {
        when(client.overview(anyList()))
            .thenThrow(new KnowledgeException(HttpStatus.SERVICE_UNAVAILABLE, "offline"))
            .thenReturn(result(List.of()));
        assertThatThrownBy(() -> service.get(project)).hasMessage("offline");
        assertThat(service.get(project).overview().integrations()).isEmpty();
        service.get(project);
        verify(client, times(2)).overview(anyList());
    }

    @Test
    void manualRefreshBypassesValidCacheAndRenewsTimestampAndLifetime() throws Exception {
        var first = service.get(project);
        when(clock.instant()).thenReturn(start.plusSeconds(60));
        Files.writeString(root.resolve("new-module.py"), "# newly added source");
        var refreshed = service.refresh(project);
        assertThat(refreshed).isNotSameAs(first);
        assertThat(refreshed.overviewUpdatedAt()).isEqualTo(start.plusSeconds(60));
        assertThat(refreshed.repositories().getFirst().languages()).contains("Python");
        when(clock.instant()).thenReturn(start.plusSeconds(86_400));
        assertThat(service.get(project)).isSameAs(refreshed);
        verify(client, times(2)).overview(anyList());
        when(clock.instant()).thenReturn(start.plusSeconds(86_460));
        assertThat(service.get(project).overviewUpdatedAt()).isEqualTo(start.plusSeconds(86_460));
        verify(client, times(3)).overview(anyList());
    }

    @Test
    void failedManualRefreshPreservesCachedSnapshotAndTimestamp() {
        var first = service.get(project);
        when(clock.instant()).thenReturn(start.plusSeconds(90));
        when(client.overview(anyList())).thenThrow(new KnowledgeException(HttpStatus.SERVICE_UNAVAILABLE, "offline"));
        assertThatThrownBy(() -> service.refresh(project)).hasMessage("offline");
        assertThat(service.get(project)).isSameAs(first);
        assertThat(service.get(project).overviewUpdatedAt()).isEqualTo(start);
        verify(client, times(2)).overview(anyList());
    }

    @Test
    void timestampReflectsCompletionNotRequestStart() {
        when(client.overview(anyList())).thenAnswer(invocation -> {
            when(clock.instant()).thenReturn(start.plusSeconds(120));
            return result(List.of());
        });
        assertThat(service.refresh(project).overviewUpdatedAt()).isEqualTo(start.plusSeconds(120));
    }

    @Test
    void ignoresMissingForeignAndEscapingIntegrationEvidenceAndDeduplicates() {
        String repo = project.getRepositories().getFirst().getName();
        when(client.overview(anyList())).thenReturn(
            result(
                List.of(
                    new IntegrationEvidence(" Orbit ", repo, "arbitrary/place/Remote.java"),
                    new IntegrationEvidence("orbit", repo, "arbitrary/place/Remote.java"),
                    new IntegrationEvidence("Missing", repo, "missing.java"),
                    new IntegrationEvidence("Foreign", "another-repo", "arbitrary/place/Remote.java"),
                    new IntegrationEvidence("Escape", repo, "../elsewhere.java"),
                    new IntegrationEvidence("Absolute", repo, root.resolve("arbitrary/place/Remote.java").toString())
                )
            )
        );
        assertThat(service.get(project).overview().integrations()).containsExactly("Orbit");
    }

    @Test
    void invalidatesOnRepositoryChangeAndSeparatesProjectEntries() throws Exception {
        var first = service.get(project);
        Path secondRoot = Files.createDirectory(root.resolve("another"));
        project.setRepositories(List.of(RepositoryScannerTest.repository("repo", secondRoot)));
        assertThat(service.get(project)).isNotSameAs(first);
        project.setId("other-project");
        service.get(project);
        verify(client, times(3)).overview(anyList());
    }

    @Test
    void boundedCacheEvictsLeastRecentlyUsedAndCanBeDisabled() {
        properties.getCodex().setOverviewCacheMaxEntries(1);
        service.get(project);
        project.setId("another");
        service.get(project);
        project.setId("project");
        service.get(project);
        verify(client, times(3)).overview(anyList());
        properties.getCodex().setOverviewCacheSeconds(0);
        service.get(project);
        service.get(project);
        verify(client, times(5)).overview(anyList());
    }

    @Test
    void simultaneousPageOpensShareOneAnalysis() throws Exception {
        CountDownLatch started = new CountDownLatch(1),
            release = new CountDownLatch(1);
        when(client.overview(anyList())).thenAnswer(invocation -> {
            started.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Test timed out");
            return result(List.of());
        });
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> service.get(project));
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            var second = executor.submit(() -> service.get(project));
            release.countDown();
            assertThat(first.get(5, TimeUnit.SECONDS)).isSameAs(second.get(5, TimeUnit.SECONDS));
        } finally {
            release.countDown();
        }
        verify(client, times(1)).overview(anyList());
    }

    @Test
    void emptyProjectNeverStartsAnalysis() {
        project.setRepositories(List.of());
        assertThat(service.get(project).overview().integrations()).isEmpty();
        verifyNoInteractions(client);
    }

    @Test
    void concurrentManualRefreshesShareOneNewAnalysisWithoutDiscardingCurrentCache() throws Exception {
        var cached = service.get(project);
        when(clock.instant()).thenReturn(start.plusSeconds(120));
        CountDownLatch started = new CountDownLatch(1),
            release = new CountDownLatch(1);
        when(client.overview(anyList())).thenAnswer(invocation -> {
            started.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Test timed out");
            return result(List.of());
        });
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            try {
                var first = executor.submit(() -> service.refresh(project));
                assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(service.get(project)).isSameAs(cached);
                var secondResult = new CompletableFuture<DtoProject>();
                var secondThread = Thread.ofVirtual().start(() -> {
                    try {
                        secondResult.complete(service.refresh(project));
                    } catch (Throwable error) {
                        secondResult.completeExceptionally(error);
                    }
                });
                // Wait until the second caller is actually joining the in-flight future, not merely scheduled.
                await()
                    .atMost(Duration.ofSeconds(3))
                    .until(() -> secondThread.getState() == Thread.State.TIMED_WAITING);
                release.countDown();
                var refreshed = first.get(5, TimeUnit.SECONDS);
                assertThat(secondResult.get(5, TimeUnit.SECONDS)).isSameAs(refreshed);
                assertThat(refreshed.overviewUpdatedAt()).isEqualTo(start.plusSeconds(120));
                assertThat(service.get(project)).isSameAs(refreshed);
            } finally {
                release.countDown();
            }
        }
        verify(client, times(2)).overview(anyList());
    }
}
