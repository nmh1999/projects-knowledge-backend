package com.projectsknowledge.business.project.catalog;

import com.projectsknowledge.business.project.entity.Project;
import com.projectsknowledge.business.project.entity.Repository;
import com.projectsknowledge.business.project.enums.RepositoryType;
import com.projectsknowledge.general.cancellation.RequestCancellation;
import com.projectsknowledge.general.config.ProjectsKnowledgeProperties;
import com.projectsknowledge.general.exception.KnowledgeException;
import com.projectsknowledge.general.integration.codex.client.CodexAppServerClient;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** Caches the dynamically discovered project list until expiry or an explicit refresh. */
@Service
@RequiredArgsConstructor
public class CodexProjectCatalog {

    private static final Set<String> IGNORED_DIRECTORIES = Set.of(
        ".git",
        ".codex",
        ".idea",
        ".vscode",
        "node_modules",
        "target",
        "dist",
        "build",
        "vendor",
        ".venv"
    );
    private static final Set<String> PROJECT_MANIFESTS = Set.of(
        "pom.xml",
        "build.gradle",
        "build.gradle.kts",
        "package.json",
        "pyproject.toml",
        "requirements.txt",
        "go.mod",
        "Cargo.toml",
        "composer.json",
        "Gemfile"
    );
    private final CodexAppServerClient client;
    private final ProjectsKnowledgeProperties properties;
    private final Clock clock;
    private volatile Cache cache = new Cache(Instant.EPOCH, List.of());

    public List<Project> projects() {
        return projects(false);
    }

    public List<Project> refresh() {
        return projects(true);
    }

    private List<Project> projects(boolean refresh) {
        RequestCancellation.check();
        if (!properties.getCodex().isEnabled()) throw new KnowledgeException(
            HttpStatus.SERVICE_UNAVAILABLE,
            "Codex integration is disabled."
        );
        Cache observed = cache;
        if (!refresh && isFresh(observed)) return observed.projects();
        synchronized (this) {
            RequestCancellation.check();
            // Concurrent refreshes share a successful reload; failures never discard the last snapshot.
            if ((!refresh || cache != observed) && isFresh(cache)) return cache.projects();
            List<Project> projects = loadProjects();
            RequestCancellation.publish(() -> cache = new Cache(clock.instant(), projects));
            return projects;
        }
    }

    private boolean isFresh(Cache snapshot) {
        return snapshot
            .loadedAt()
            .isAfter(clock.instant().minusSeconds(properties.getCodex().getProjectCacheSeconds()));
    }

    private List<Project> loadProjects() {
        Map<String, CodexAppServerClient.CodexThread> latestByPath = new LinkedHashMap<>();
        client
            .listThreads()
            .stream()
            .filter(thread -> Files.isDirectory(thread.cwd()) && !excluded(thread.cwd()))
            .sorted(Comparator.comparingLong(CodexAppServerClient.CodexThread::updatedAt).reversed())
            .forEach(thread -> latestByPath.putIfAbsent(key(thread.cwd()), thread));
        return latestByPath
            .values()
            .stream()
            .map(thread -> toProject(thread.cwd()))
            .filter(project -> !project.getRepositories().isEmpty())
            .toList();
    }

    private Project toProject(Path workspace) {
        RequestCancellation.check();
        Project project = new Project();
        project.setId("codex-" + digest(key(workspace)).substring(0, 16));
        project.setName(workspace.getFileName() == null ? workspace.toString() : workspace.getFileName().toString());
        project.setRepositories(repositories(workspace));
        return project;
    }

    private List<Repository> repositories(Path workspace) {
        Path normalized = workspace.toAbsolutePath().normalize();
        if (excluded(normalized)) return List.of();
        List<Repository> children = childRepositories(normalized);
        // A workspace containing multiple applications is presented as a single project with separate repositories.
        if (children.size() > 1) return children;
        if (isRepository(normalized) && !hasExcludedDescendant(normalized)) return List.of(repository(normalized));
        if (!children.isEmpty()) return children;
        return looksLikeSourceProject(normalized) && !hasExcludedDescendant(normalized)
            ? List.of(repository(normalized))
            : List.of();
    }

    private List<Repository> childRepositories(Path workspace) {
        List<Path> directories = directories(workspace);
        List<Path> repositories = directories.stream().filter(this::isRepository).toList();
        if (repositories.isEmpty()) {
            // Codex can open a Git workspace whose actual applications live inside one wrapper folder.
            repositories = directories
                .stream()
                .filter(path -> !path.getFileName().toString().startsWith("."))
                .flatMap(path -> directories(path).stream())
                .filter(this::isRepository)
                .sorted()
                .limit(20)
                .toList();
        }
        return repositories.stream().map(this::repository).toList();
    }

    private List<Path> directories(Path parent) {
        try (Stream<Path> paths = Files.list(parent)) {
            return paths
                .filter(Files::isDirectory)
                .filter(path -> !IGNORED_DIRECTORIES.contains(path.getFileName().toString().toLowerCase(Locale.ROOT)))
                .filter(path -> !excluded(path))
                .sorted()
                .limit(20)
                .toList();
        } catch (IOException ignored) {
            return List.of();
        }
    }

    private Repository repository(Path path) {
        Repository repository = new Repository();
        repository.setId("repo-" + digest(key(path)).substring(0, 16));
        repository.setName(path.getFileName() == null ? path.toString() : path.getFileName().toString());
        repository.setPath(path.toAbsolutePath().normalize());
        repository.setType(detectType(path));
        return repository;
    }

    private boolean isRepository(Path path) {
        if (excluded(path) || hasExcludedDescendant(path)) return false;
        return Files.exists(path.resolve(".git")) || looksLikeSourceProject(path);
    }

    private boolean looksLikeSourceProject(Path path) {
        return (
            PROJECT_MANIFESTS.stream().anyMatch(name -> Files.isRegularFile(path.resolve(name))) ||
            Files.isDirectory(path.resolve("src"))
        );
    }

    private RepositoryType detectType(Path path) {
        if (
            Files.exists(path.resolve("angular.json")) ||
            Files.exists(path.resolve("next.config.js")) ||
            Files.exists(path.resolve("next.config.mjs"))
        ) return RepositoryType.FRONTEND;
        if (
            Files.exists(path.resolve("package.json")) &&
            !Files.exists(path.resolve("pom.xml")) &&
            !Files.exists(path.resolve("build.gradle")) &&
            !Files.exists(path.resolve("build.gradle.kts"))
        ) return RepositoryType.FRONTEND;
        return RepositoryType.BACKEND;
    }

    private boolean excluded(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        return properties
            .getCodex()
            .getExcludedPaths()
            .stream()
            .map(value -> value.toAbsolutePath().normalize())
            .anyMatch(normalized::startsWith);
    }

    private boolean hasExcludedDescendant(Path workspace) {
        Path normalized = workspace.toAbsolutePath().normalize();
        return properties
            .getCodex()
            .getExcludedPaths()
            .stream()
            .map(value -> value.toAbsolutePath().normalize())
            .anyMatch(path -> path.startsWith(normalized) && !path.equals(normalized));
    }

    private String key(Path path) {
        return path.toAbsolutePath().normalize().toString().toLowerCase(Locale.ROOT);
    }

    private String digest(String value) {
        try {
            return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private record Cache(Instant loadedAt, List<Project> projects) {}
}
