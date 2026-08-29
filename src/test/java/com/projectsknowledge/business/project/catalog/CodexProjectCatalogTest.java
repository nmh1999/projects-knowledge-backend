package com.projectsknowledge.business.project.catalog;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.projectsknowledge.general.config.ProjectsKnowledgeProperties;
import com.projectsknowledge.general.exception.KnowledgeException;
import com.projectsknowledge.general.integration.codex.client.CodexAppServerClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;

class CodexProjectCatalogTest {

    @TempDir
    Path root;

    @Test
    void discoversRenamesAndRemovesArbitraryWorkspacesFromCodexOnly() throws Exception {
        Path first = project("alpha-suite", "pyproject.toml");
        Path second = project("beta-tool", "go.mod");
        var client = mock(CodexAppServerClient.class);
        var properties = new ProjectsKnowledgeProperties();
        properties.getCodex().setProjectCacheSeconds(0);
        when(client.listThreads()).thenReturn(
            List.of(thread(first, 1), thread(second, 5), thread(first, 9), thread(root.resolve("missing"), 12))
        );
        var catalog = new CodexProjectCatalog(client, properties, Clock.systemUTC());
        var projects = catalog.projects();
        assertThat(projects)
            .extracting(project -> project.getName())
            .containsExactly("alpha-suite", "beta-tool");
        assertThat(projects.getFirst().getRepositories())
            .extracting(repository -> repository.getPath())
            .containsExactly(first);
        assertThat(catalog.projects().getFirst().getId()).isEqualTo(projects.getFirst().getId());
        Path renamed = Files.move(second, root.resolve("renamed-tool"));
        when(client.listThreads()).thenReturn(List.of(thread(renamed, 15)));
        assertThat(catalog.projects())
            .extracting(project -> project.getName())
            .containsExactly("renamed-tool");
        when(client.listThreads()).thenReturn(List.of());
        assertThat(catalog.projects()).isEmpty();
    }

    @Test
    void groupsApplicationsAndNeverIncludesExcludedOrVendorFolders() throws Exception {
        Path suite = root.resolve("suite");
        Path api = project("suite/api", "pom.xml");
        Path web = project("suite/web", "package.json");
        Path excluded = project("suite/private-app", "package.json");
        project("suite/private-app/nested", "go.mod");
        project("suite/node_modules/dependency", "package.json");
        Files.createDirectory(suite.resolve(".git"));
        var client = mock(CodexAppServerClient.class);
        when(client.listThreads()).thenReturn(List.of(thread(suite, 1), thread(excluded, 2)));
        var properties = new ProjectsKnowledgeProperties();
        properties.getCodex().setExcludedPaths(List.of(excluded));
        var catalog = new CodexProjectCatalog(client, properties, Clock.systemUTC());
        assertThat(catalog.projects()).hasSize(1);
        assertThat(catalog.projects().getFirst().getRepositories())
            .extracting(repository -> repository.getPath())
            .containsExactly(api, web);
    }

    @Test
    void unavailableOrDisabledCodexNeverUsesAFixedFallback() {
        var client = mock(CodexAppServerClient.class);
        when(client.listThreads()).thenThrow(new KnowledgeException(HttpStatus.SERVICE_UNAVAILABLE, "Unavailable"));
        var properties = new ProjectsKnowledgeProperties();
        assertThatThrownBy(() -> new CodexProjectCatalog(client, properties, Clock.systemUTC()).projects()).isInstanceOf(
            KnowledgeException.class
        );
        properties.getCodex().setEnabled(false);
        clearInvocations(client);
        assertThatThrownBy(() -> new CodexProjectCatalog(client, properties, Clock.systemUTC()).projects()).isInstanceOf(
            KnowledgeException.class
        );
        verifyNoInteractions(client);
    }

    @Test
    void cachesForTwentyFourHoursAndReloadsAtExpiry() throws Exception {
        var client = mock(CodexAppServerClient.class);
        var clock = mock(Clock.class);
        var now = Instant.parse("2026-01-01T00:00:00Z");
        when(clock.instant()).thenReturn(now);
        when(client.listThreads()).thenReturn(List.of(thread(project("sample", "pom.xml"), 1)));
        var properties = new ProjectsKnowledgeProperties();
        assertThat(properties.getCodex().getProjectCacheSeconds()).isEqualTo(86_400);
        var catalog = new CodexProjectCatalog(client, properties, clock);
        var snapshot = catalog.projects();
        when(clock.instant()).thenReturn(now.plusSeconds(86_399));
        assertThat(catalog.projects()).isSameAs(snapshot);
        verify(client, times(1)).listThreads();
        when(clock.instant()).thenReturn(now.plusSeconds(86_400));
        when(client.listThreads()).thenReturn(List.of());
        assertThat(catalog.projects()).isEmpty();
        verify(client, times(2)).listThreads();
        assertThat(catalog.projects()).isEmpty();
        verifyNoMoreInteractions(client);
    }

    @Test
    void manualRefreshReplacesTheSnapshotAndRestartsItsLifetime() throws Exception {
        var client = mock(CodexAppServerClient.class);
        var clock = mock(Clock.class);
        var now = Instant.parse("2026-01-01T00:00:00Z");
        when(clock.instant()).thenReturn(now);
        when(client.listThreads()).thenReturn(List.of(thread(project("first", "pom.xml"), 1)));
        var catalog = new CodexProjectCatalog(client, new ProjectsKnowledgeProperties(), clock);
        catalog.projects();
        when(clock.instant()).thenReturn(now.plusSeconds(60));
        when(client.listThreads()).thenReturn(List.of(thread(project("second", "go.mod"), 2)));
        assertThat(catalog.refresh()).extracting(value -> value.getName()).containsExactly("second");
        when(clock.instant()).thenReturn(now.plusSeconds(86_400));
        assertThat(catalog.projects()).extracting(value -> value.getName()).containsExactly("second");
        verify(client, times(2)).listThreads();
        when(clock.instant()).thenReturn(now.plusSeconds(86_460));
        catalog.projects();
        verify(client, times(3)).listThreads();
    }

    @Test
    void failedRefreshKeepsThePreviousUnexpiredSnapshotAndCanBeRetried() throws Exception {
        var client = mock(CodexAppServerClient.class);
        when(client.listThreads()).thenReturn(List.of(thread(project("sample", "pom.xml"), 1)));
        var catalog = new CodexProjectCatalog(client, new ProjectsKnowledgeProperties(), Clock.systemUTC());
        var snapshot = catalog.projects();
        when(client.listThreads()).thenThrow(new KnowledgeException(HttpStatus.SERVICE_UNAVAILABLE, "Unavailable"));
        assertThatThrownBy(catalog::refresh).isInstanceOf(KnowledgeException.class);
        assertThat(catalog.projects()).isSameAs(snapshot);
        doReturn(List.of()).when(client).listThreads();
        assertThat(catalog.refresh()).isEmpty();
        assertThat(catalog.projects()).isEmpty();
        verify(client, times(3)).listThreads();
    }

    private Path project(String name, String manifest) throws Exception {
        Path path = Files.createDirectories(root.resolve(name));
        Files.writeString(path.resolve(manifest), "{}");
        return path;
    }

    private CodexAppServerClient.CodexThread thread(Path path, long updatedAt) {
        return new CodexAppServerClient.CodexThread(path, updatedAt);
    }
}
