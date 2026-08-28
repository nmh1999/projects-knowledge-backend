package com.projectsknowledge.service;

import com.projectsknowledge.codex.CodexAppServerClient;
import com.projectsknowledge.config.ProjectsKnowledgeProperties;
import com.projectsknowledge.dto.CodexProjectOverview;
import com.projectsknowledge.dto.ProjectDto;
import com.projectsknowledge.dto.ProjectOverviewDto;
import com.projectsknowledge.dto.RepositoryDto;
import com.projectsknowledge.exception.KnowledgeException;
import com.projectsknowledge.model.Project;
import com.projectsknowledge.model.Repository;
import com.projectsknowledge.model.RepositoryType;
import com.projectsknowledge.scanner.RepositoryScanner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;

/** One evidence-based overview per project snapshot, shared for five hours by all page visits. */
@Service
public class ProjectOverviewService {
    private final CodexAppServerClient client;
    private final RepositoryScanner scanner;
    private final ProjectsKnowledgeProperties properties;
    private final Clock clock;
    private final Map<CacheKey, CacheEntry> cache = new LinkedHashMap<>(16, .75f, true);
    private final ConcurrentHashMap<CacheKey, CompletableFuture<ProjectDto>> inFlight = new ConcurrentHashMap<>();

    @Autowired
    public ProjectOverviewService(CodexAppServerClient client, RepositoryScanner scanner, ProjectsKnowledgeProperties properties) {
        this(client, scanner, properties, Clock.systemUTC());
    }

    ProjectOverviewService(CodexAppServerClient client, RepositoryScanner scanner, ProjectsKnowledgeProperties properties, Clock clock) {
        this.client = client; this.scanner = scanner; this.properties = properties; this.clock = clock;
    }

    public ProjectDto get(Project project) {
        return load(project, false);
    }

    public ProjectDto refresh(Project project) {
        return load(project, true);
    }

    private ProjectDto load(Project project, boolean forceRefresh) {
        CacheKey key = new CacheKey(project.getId(), project.getName(), project.getRepositories().stream()
                .map(repo -> new RepositoryKey(repo.getId(), repo.getName(), repo.getPath().toAbsolutePath().normalize(), repo.getType()))
                .sorted(Comparator.comparing(repo -> repo.path().toString())).toList());
        ProjectDto cached = forceRefresh ? null : cached(key);
        if (cached != null) return cached;

        // Concurrent opens of the same project share a model call; unrelated projects do not block each other.
        CompletableFuture<ProjectDto> request = new CompletableFuture<>();
        CompletableFuture<ProjectDto> existing = inFlight.putIfAbsent(key, request);
        if (existing != null) {
            try { return existing.join(); }
            catch (CompletionException exception) {
                if (exception.getCause() instanceof RuntimeException cause) throw cause;
                throw exception;
            }
        }
        try {
            cached = forceRefresh ? null : cached(key); // Manual refresh bypasses valid cache but shares an in-flight analysis.
            ProjectDto result = cached != null ? cached : build(project);
            if (cached == null) remember(key, result);
            request.complete(result);
            return result;
        } catch (RuntimeException | Error exception) {
            request.completeExceptionally(exception);
            throw exception; // Preserve the previous successful snapshot when manual refresh fails.
        } finally { inFlight.remove(key, request); }
    }

    private ProjectDto build(Project project) {
        List<Repository> repositories = project.getRepositories();
        if (repositories.isEmpty()) return new ProjectDto(project.getId(), project.getName(), List.of(),
                new ProjectOverviewDto(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of()), clock.instant());
        if (repositories.stream().anyMatch(repo -> !Files.isDirectory(repo.getPath()))) {
            throw new KnowledgeException(HttpStatus.SERVICE_UNAVAILABLE, "A project repository is not available. Please retry when it is accessible.");
        }
        CodexProjectOverview result = client.overview(repositories.stream().map(Repository::getPath).distinct().toList());
        List<RepositoryDto> repositoryDtos = repositories.stream().map(repo -> {
            scanner.invalidateFiles(repo);
            var metadata = scanner.metadata(repo);
            return new RepositoryDto(repo.getId(), repo.getName(), repo.getType().name(), metadata.available(),
                    metadata.languages(), metadata.frameworks(), metadata.buildTools());
        }).toList();
        List<String> integrations = result.integrations().stream().filter(value -> hasEvidence(repositories, value))
                .map(CodexProjectOverview.IntegrationEvidence::name).toList();
        return new ProjectDto(project.getId(), project.getName(), repositoryDtos, new ProjectOverviewDto(
                names(result.frontend()), names(result.backend()), names(result.databases()), names(result.domains()),
                names(integrations), names(result.messaging()), names(result.scheduledJobs())), clock.instant());
    }

    private boolean hasEvidence(List<Repository> repositories, CodexProjectOverview.IntegrationEvidence evidence) {
        if (evidence == null || evidence.name() == null || evidence.name().isBlank() || evidence.filePath() == null) return false;
        try {
            if (Path.of(evidence.filePath()).isAbsolute()) return false;
            for (Repository repo : repositories) {
                if (!repo.getName().equals(evidence.repositoryName())) continue;
                try {
                    scanner.resolveSource(repo, evidence.filePath()); // Reject missing, ignored, traversal and escaping symlink paths.
                    return true;
                } catch (KnowledgeException ignored) { /* Another selected repository may have the same directory name. */ }
            }
        } catch (InvalidPathException ignored) { }
        return false;
    }

    private List<String> names(List<String> values) {
        TreeSet<String> names = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        values.stream().filter(Objects::nonNull).map(String::strip).filter(value -> !value.isBlank())
                .map(value -> value.substring(0, Math.min(value.length(), 120))).forEach(names::add);
        return names.stream().limit(30).toList();
    }

    private ProjectDto cached(CacheKey key) {
        synchronized (cache) {
            if (properties.getCodex().getOverviewCacheSeconds() <= 0 || properties.getCodex().getOverviewCacheMaxEntries() <= 0) return null;
            prune();
            CacheEntry entry = cache.get(key);
            return entry == null ? null : entry.project();
        }
    }

    private void remember(CacheKey key, ProjectDto project) {
        synchronized (cache) {
            int maxEntries = properties.getCodex().getOverviewCacheMaxEntries();
            if (properties.getCodex().getOverviewCacheSeconds() <= 0 || maxEntries <= 0) return;
            prune();
            // Timestamp and TTL both start at successful completion, never on page reads or failed attempts.
            cache.put(key, new CacheEntry(project.overviewUpdatedAt(), project));
            while (cache.size() > maxEntries) cache.remove(cache.keySet().iterator().next());
        }
    }

    private void prune() {
        Instant cutoff = clock.instant().minusSeconds(properties.getCodex().getOverviewCacheSeconds());
        cache.entrySet().removeIf(entry -> !entry.getValue().loadedAt().isAfter(cutoff));
    }

    private record RepositoryKey(String id, String name, Path path, RepositoryType type) {}
    private record CacheKey(String projectId, String name, List<RepositoryKey> repositories) {}
    private record CacheEntry(Instant loadedAt, ProjectDto project) {}
}
