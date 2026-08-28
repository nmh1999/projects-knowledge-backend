package com.projectsknowledge.codex;

import com.projectsknowledge.config.ProjectsKnowledgeProperties;
import com.projectsknowledge.exception.KnowledgeException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CodexProjectCatalogTest {
    @TempDir Path root;

    @Test
    void discoversRenamesAndRemovesArbitraryWorkspacesFromCodexOnly() throws Exception {
        Path first = project("alpha-suite", "pyproject.toml");
        Path second = project("beta-tool", "go.mod");
        var client = mock(CodexAppServerClient.class);
        var properties = new ProjectsKnowledgeProperties();
        properties.getCodex().setProjectCacheSeconds(0);
        when(client.listThreads()).thenReturn(List.of(thread(first, 1), thread(second, 5), thread(first, 9), thread(root.resolve("missing"), 12)));
        var catalog = new CodexProjectCatalog(client, properties);
        var projects = catalog.projects();
        assertThat(projects).extracting(project -> project.getName()).containsExactly("alpha-suite", "beta-tool");
        assertThat(projects.getFirst().getRepositories()).extracting(repository -> repository.getPath()).containsExactly(first);
        assertThat(catalog.projects().getFirst().getId()).isEqualTo(projects.getFirst().getId());
        Path renamed = Files.move(second, root.resolve("renamed-tool"));
        when(client.listThreads()).thenReturn(List.of(thread(renamed, 15)));
        assertThat(catalog.projects()).extracting(project -> project.getName()).containsExactly("renamed-tool");
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
        var catalog = new CodexProjectCatalog(client, properties);
        assertThat(catalog.projects()).hasSize(1);
        assertThat(catalog.projects().getFirst().getRepositories()).extracting(repository -> repository.getPath()).containsExactly(api, web);
    }

    @Test
    void unavailableOrDisabledCodexNeverUsesAFixedFallback() {
        var client = mock(CodexAppServerClient.class);
        when(client.listThreads()).thenThrow(new KnowledgeException(HttpStatus.SERVICE_UNAVAILABLE, "Unavailable"));
        var properties = new ProjectsKnowledgeProperties();
        assertThatThrownBy(() -> new CodexProjectCatalog(client, properties).projects()).isInstanceOf(KnowledgeException.class);
        properties.getCodex().setEnabled(false);
        clearInvocations(client);
        assertThatThrownBy(() -> new CodexProjectCatalog(client, properties).projects()).isInstanceOf(KnowledgeException.class);
        verifyNoInteractions(client);
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
