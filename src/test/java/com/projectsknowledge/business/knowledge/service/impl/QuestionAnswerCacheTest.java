package com.projectsknowledge.business.knowledge.service.impl;

import static com.projectsknowledge.support.TestFixtures.project;
import static com.projectsknowledge.support.TestFixtures.repository;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.*;

import com.projectsknowledge.business.knowledge.cache.QuestionAnswerCache;
import com.projectsknowledge.business.knowledge.enums.SearchMode;
import com.projectsknowledge.business.knowledge.schema.request.ReqIntegrationDetails;
import com.projectsknowledge.business.knowledge.schema.request.ReqQuestion;
import com.projectsknowledge.business.project.entity.Project;
import com.projectsknowledge.business.project.service.ProjectRetrievalService;
import com.projectsknowledge.general.cancellation.RequestCancellation;
import com.projectsknowledge.general.cancellation.RequestCancelledException;
import com.projectsknowledge.general.cache.PersistentKnowledgeCache;
import com.projectsknowledge.general.config.ProjectsKnowledgeProperties;
import com.projectsknowledge.general.integration.codex.client.CodexAppServerClient;
import com.projectsknowledge.general.integration.codex.schema.response.DtoBasicKnowledgeResult;
import com.projectsknowledge.general.scanner.RepositoryScanner;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class QuestionAnswerCacheTest {

    @TempDir
    Path cacheRoot;

    private final CodexAppServerClient client = mock(CodexAppServerClient.class);
    private final ProjectRetrievalService projects = mock(ProjectRetrievalService.class);
    private final ProjectsKnowledgeProperties properties = new ProjectsKnowledgeProperties();
    private final Clock clock = mock(Clock.class);
    private final Instant start = Instant.parse("2026-08-28T10:00:00Z");
    private final ReqQuestion question = new ReqQuestion("sample", "Explain approvals", "en", SearchMode.BASIC);
    private final Project project = project(
        "sample",
        "Sample",
        repository("repo", "Repository", Path.of("runtime-project"), null)
    );
    private QuestionAskServiceImpl service;

    @BeforeEach
    void setUp() {
        when(projects.requireProject(anyString())).thenReturn(project);
        when(clock.instant()).thenReturn(start);
        when(client.ask(anyList(), anyString(), anyString(), any())).thenReturn(
            new DtoBasicKnowledgeResult("Answer", "high", true).toKnowledgeResult()
        );
        service = service(PersistentKnowledgeCache.disabled());
    }

    @ParameterizedTest
    @EnumSource(SearchMode.class)
    void cachesEveryModeForExactlyTwentyFourHoursFromCompletionWithoutSlidingExpiry(SearchMode mode) {
        assertThat(properties.getCodex().getAnswerCacheSeconds()).isEqualTo(86_400);
        var request = new ReqQuestion("sample", question.question(), "en", mode);
        when(client.ask(anyList(), anyString(), anyString(), any())).thenAnswer(call -> {
            when(clock.instant()).thenReturn(start.plusSeconds(30));
            return new DtoBasicKnowledgeResult("Answer", "high", true).toKnowledgeResult();
        });
        var first = service.ask(request);
        assertThat(first.updatedAt()).isEqualTo(start.plusSeconds(30));
        assertThat(first.expiresAt()).isEqualTo(start.plusSeconds(86_430));
        when(clock.instant()).thenReturn(first.expiresAt().minusNanos(1));
        assertThat(service.ask(request)).isSameAs(first);
        verify(client, times(1)).ask(anyList(), anyString(), anyString(), any());
        when(clock.instant()).thenReturn(first.expiresAt());
        service.ask(request);
        verify(client, times(2)).ask(anyList(), anyString(), anyString(), any());
    }

    @Test
    void cancelledRefreshStopsSharedWorkAndPreservesTheCachedAnswer() throws Exception {
        var old = service.ask(question);
        var started = new CountDownLatch(1);
        var stopped = new CountDownLatch(1);
        when(client.ask(anyList(), anyString(), anyString(), any())).thenAnswer(call -> {
            started.countDown();
            try {
                return RequestCancellation.await(new CompletableFuture<>());
            } finally {
                stopped.countDown();
            }
        });
        var token = new RequestCancellation();
        var pending = CompletableFuture.supplyAsync(() ->
            RequestCancellation.with(token, () -> service.refresh(question))
        );
        assertThat(started.await(3, TimeUnit.SECONDS)).isTrue();
        token.cancel();
        assertThatThrownBy(() -> pending.get(3, TimeUnit.SECONDS)).hasCauseInstanceOf(RequestCancelledException.class);
        assertThat(stopped.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(service.ask(question)).isSameAs(old);
        doReturn(new DtoBasicKnowledgeResult("Retry", "high", true).toKnowledgeResult())
            .when(client)
            .ask(anyList(), anyString(), anyString(), any());
        assertThat(service.refresh(question).summary()).isEqualTo("Retry");
    }

    @Test
    void refreshBypassesCacheAndRenewsDatesOnlyOnSuccess() {
        var first = service.ask(question);
        when(clock.instant()).thenReturn(start.plusSeconds(60));
        var second = service.refresh(question);
        assertThat(second.updatedAt()).isEqualTo(start.plusSeconds(60));
        assertThat(second.expiresAt()).isEqualTo(start.plusSeconds(86_460));
        assertThat(service.ask(question)).isSameAs(second).isNotSameAs(first);
        verify(client, times(2)).ask(anyList(), anyString(), anyString(), any());
        when(client.ask(anyList(), anyString(), anyString(), any())).thenThrow(new IllegalStateException("Offline"));
        assertThatThrownBy(() -> service.refresh(question)).hasMessage("Offline");
        assertThat(service.ask(question)).isSameAs(second);
        doReturn(new DtoBasicKnowledgeResult("Recovered", "high", true).toKnowledgeResult())
            .when(client)
            .ask(anyList(), anyString(), anyString(), any());
        assertThat(service.refresh(question).summary()).isEqualTo("Recovered");
    }

    @Test
    void includesDynamicRepositoryScopeAndProjectIdentityInTheKey() {
        service.ask(question);
        project.getRepositories().getFirst().setPath(Path.of("another-runtime-project"));
        service.ask(question);
        project.setId("other");
        service.ask(new ReqQuestion("other", question.question(), "en", SearchMode.BASIC));
        verify(client, times(3)).ask(anyList(), anyString(), anyString(), any());
    }

    @Test
    void restoresAnAnswerAfterServiceRestartWithoutAnotherCodexTurn() {
        var persistent = new PersistentKnowledgeCache(
            new ObjectMapper().findAndRegisterModules(),
            cacheRoot.resolve("knowledge.db"),
            true
        );
        persistent.initialize();
        var firstService = service(persistent);
        var first = firstService.ask(question);

        var restartedCache = new PersistentKnowledgeCache(
            new ObjectMapper().findAndRegisterModules(),
            cacheRoot.resolve("knowledge.db"),
            true
        );
        restartedCache.initialize();
        var restartedService = service(restartedCache);
        var restored = restartedService.ask(question);

        assertThat(restored).isEqualTo(first).isNotSameAs(first);
        verify(client, times(1)).ask(anyList(), anyString(), anyString(), any());
    }

    @Test
    void restoresIntegrationDetailsAfterServiceRestartWithoutAnotherCodexTurn() {
        Path database = cacheRoot.resolve("integration-knowledge.db");
        var persistent = new PersistentKnowledgeCache(
            new ObjectMapper().findAndRegisterModules(),
            database,
            true
        );
        persistent.initialize();
        var firstService = service(persistent);
        var request = new ReqIntegrationDetails("sample", "Orbit", "en");
        var first = firstService.explainIntegration(request);

        var restartedCache = new PersistentKnowledgeCache(
            new ObjectMapper().findAndRegisterModules(),
            database,
            true
        );
        restartedCache.initialize();
        var restartedService = service(restartedCache);

        assertThat(restartedService.explainIntegration(request)).isEqualTo(first).isNotSameAs(first);
        verify(client, times(1)).ask(anyList(), anyString(), anyString(), any());
    }

    @Test
    void isolatesIntegrationCacheAndAllowsManualRefreshWithTheSameTwentyFourHourTtl() {
        var integration = new ReqIntegrationDetails("sample", "Orbit", "en");
        var first = service.explainIntegration(integration);
        assertThat(first.expiresAt()).isEqualTo(start.plusSeconds(86_400));
        assertThat(service.explainIntegration(integration)).isSameAs(first);
        service.ask(new ReqQuestion("sample", "Orbit", "en", SearchMode.ADVANCED));
        var refreshed = service.refreshIntegration(integration);
        assertThat(service.explainIntegration(integration)).isSameAs(refreshed).isNotSameAs(first);
        verify(client, times(3)).ask(anyList(), anyString(), anyString(), any());
        when(clock.instant()).thenReturn(refreshed.expiresAt());
        service.explainIntegration(integration);
        verify(client, times(4)).ask(anyList(), anyString(), anyString(), any());
    }

    @Test
    void keepsCacheBoundedAndSupportsDisabledCaching() {
        properties.getCodex().setAnswerCacheMaxEntries(1);
        service.ask(question);
        when(clock.instant()).thenReturn(start.plusSeconds(1));
        service.ask(new ReqQuestion("sample", "Another question", "en", SearchMode.BASIC));
        service.ask(question);
        verify(client, times(3)).ask(anyList(), anyString(), anyString(), any());
        properties.getCodex().setAnswerCacheSeconds(0);
        assertThat(service.ask(question).expiresAt()).isNull();
        service.ask(question);
        verify(client, times(5)).ask(anyList(), anyString(), anyString(), any());
    }

    @Test
    void concurrentRefreshesShareOneAnalysisAndNormalReadsKeepThePreviousAnswer() throws Exception {
        var old = service.ask(question);
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(client.ask(anyList(), anyString(), anyString(), any())).thenAnswer(call -> {
            started.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Test timed out");
            return new DtoBasicKnowledgeResult("Refreshed", "high", true).toKnowledgeResult();
        });
        var first = CompletableFuture.supplyAsync(() -> service.refresh(question));
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        var second = new CompletableFuture<
            com.projectsknowledge.business.knowledge.schema.response.DtoKnowledgeAnswer
        >();
        Thread waiter = Thread.ofPlatform().start(() -> {
            try {
                second.complete(service.refresh(question));
            } catch (Throwable failure) {
                second.completeExceptionally(failure);
            }
        });
        try {
            await()
                .atMost(Duration.ofSeconds(3))
                .until(() -> waiter.getState() == Thread.State.TIMED_WAITING);
            assertThat(service.ask(question)).isSameAs(old);
        } finally {
            release.countDown();
        }
        assertThat(second.get(5, TimeUnit.SECONDS)).isSameAs(first.get(5, TimeUnit.SECONDS));
        verify(client, times(2)).ask(anyList(), anyString(), anyString(), any());
    }

    private QuestionAskServiceImpl service(PersistentKnowledgeCache persistentCache) {
        return new QuestionAskServiceImpl(
            projects,
            client,
            new QuestionAnswerCache(properties, clock, persistentCache),
            new RepositoryScanner(properties)
        );
    }
}
