package com.projectsknowledge.business.project.service.impl;

import com.projectsknowledge.business.project.entity.Project;
import com.projectsknowledge.business.project.entity.Repository;
import com.projectsknowledge.business.project.enums.RepositoryType;
import com.projectsknowledge.business.project.schema.response.DtoProject;
import com.projectsknowledge.business.project.schema.response.DtoProjectOverview;
import com.projectsknowledge.business.project.schema.response.DtoRepository;
import com.projectsknowledge.business.project.service.ProjectOverviewService;
import com.projectsknowledge.general.cache.CacheClearable;
import com.projectsknowledge.general.cache.PersistentKnowledgeCache;
import com.projectsknowledge.general.cancellation.RequestCancellation;
import com.projectsknowledge.general.cancellation.SharedAnalysis;
import com.projectsknowledge.general.config.ProjectsKnowledgeProperties;
import com.projectsknowledge.general.exception.KnowledgeException;
import com.projectsknowledge.general.integration.codex.client.CodexAppServerClient;
import com.projectsknowledge.general.integration.codex.schema.response.DtoCodexProjectOverview;
import com.projectsknowledge.general.scanner.RepositoryScanner;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** One evidence-based overview per project snapshot, shared until its configured expiry. */
@Service
public class ProjectOverviewServiceImpl implements ProjectOverviewService, CacheClearable {

    private static final String OVERVIEW_NAMESPACE = "overview-v2";

    private final CodexAppServerClient client;
    private final RepositoryScanner scanner;
    private final ProjectsKnowledgeProperties properties;
    private final Clock clock;
    private final PersistentKnowledgeCache persistentCache;
    private final Map<CacheKey, CacheEntry> cache = new LinkedHashMap<>(16, .75f, true);
    private final SharedAnalysis<CacheKey, DtoProject> inFlight = new SharedAnalysis<>();
    private long cacheGeneration;

    @Autowired
    public ProjectOverviewServiceImpl(
        CodexAppServerClient client,
        RepositoryScanner scanner,
        ProjectsKnowledgeProperties properties,
        Clock clock,
        PersistentKnowledgeCache persistentCache
    ) {
        this.client = client;
        this.scanner = scanner;
        this.properties = properties;
        this.clock = clock;
        this.persistentCache = persistentCache;
    }

    ProjectOverviewServiceImpl(
        CodexAppServerClient client,
        RepositoryScanner scanner,
        ProjectsKnowledgeProperties properties,
        Clock clock
    ) {
        this(client, scanner, properties, clock, PersistentKnowledgeCache.disabled());
    }

    @Override
    public DtoProject get(Project project) {
        return load(project, false);
    }

    @Override
    public DtoProject refresh(Project project) {
        return load(project, true);
    }

    @Override
    public void clearCache() {
        synchronized (cache) {
            cache.clear();
            cacheGeneration++;
        }
    }

    private DtoProject load(Project project, boolean forceRefresh) {
        RequestCancellation.check();
        CacheKey key = cacheKey(project);
        DtoProject cached = forceRefresh ? null : cached(key);
        if (cached != null) return cached;

        // Concurrent opens of the same project share a model call; unrelated projects do not block each other.
        return inFlight.run(key, () -> {
            DtoProject snapshot = forceRefresh ? null : cached(key);
            DtoProject result = snapshot != null ? snapshot : build(project);
            // A successful refresh replaces the stable project cache entry.
            if (snapshot == null) RequestCancellation.publish(() -> remember(cacheKey(project), result));
            return result;
        });
    }

    private CacheKey cacheKey(Project project) {
        return new CacheKey(
            project.getId(),
            project.getName(),
            project
                .getRepositories()
                .stream()
                .map(repo ->
                    new RepositoryKey(
                        repo.getId(),
                        repo.getName(),
                        repo.getPath().toAbsolutePath().normalize(),
                        repo.getType()
                    )
                )
                .sorted(Comparator.comparing(repo -> repo.path().toString()))
                .toList()
        );
    }

    private DtoProject build(Project project) {
        List<Repository> repositories = project.getRepositories();
        if (repositories.isEmpty()) return new DtoProject(
            project.getId(),
            project.getName(),
            List.of(),
            new DtoProjectOverview(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of()),
            clock.instant()
        );
        if (repositories.stream().anyMatch(repo -> !Files.isDirectory(repo.getPath()))) {
            throw new KnowledgeException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "A project repository is not available. Please retry when it is accessible."
            );
        }
        DtoCodexProjectOverview result = client.overview(
            repositories.stream().map(Repository::getPath).distinct().toList()
        );
        List<DtoRepository> repositoryDtos = repositories
            .stream()
            .map(repo -> {
                RequestCancellation.check();
                scanner.invalidateFiles(repo);
                var metadata = scanner.metadata(repo);
                return new DtoRepository(
                    repo.getId(),
                    repo.getName(),
                    repo.getType().name(),
                    metadata.available(),
                    metadata.languages(),
                    metadata.frameworks(),
                    metadata.buildTools()
                );
            })
            .toList();
        List<String> integrations = result
            .integrations()
            .stream()
            .filter(value -> hasEvidence(repositories, value))
            .map(DtoCodexProjectOverview.IntegrationEvidence::name)
            .toList();
        return new DtoProject(
            project.getId(),
            project.getName(),
            repositoryDtos,
            new DtoProjectOverview(
                names(result.frontend()),
                names(result.backend()),
                names(result.databases()),
                names(result.domains()),
                names(integrations),
                names(result.messaging()),
                names(result.scheduledJobs())
            ),
            clock.instant()
        );
    }

    private boolean hasEvidence(List<Repository> repositories, DtoCodexProjectOverview.IntegrationEvidence evidence) {
        if (
            evidence == null || evidence.name() == null || evidence.name().isBlank() || evidence.filePath() == null
        ) return false;
        try {
            if (Path.of(evidence.filePath()).isAbsolute()) return false;
            for (Repository repo : repositories) {
                if (!repo.getName().equals(evidence.repositoryName())) continue;
                try {
                    scanner.resolveSource(repo, evidence.filePath()); // Reject missing, ignored, traversal and escaping symlink paths.
                    return true;
                } catch (KnowledgeException ignored) {
                    /* Another selected repository may have the same directory name. */
                }
            }
        } catch (InvalidPathException ignored) {}
        return false;
    }

    private List<String> names(List<String> values) {
        TreeSet<String> names = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        values
            .stream()
            .filter(Objects::nonNull)
            .map(String::strip)
            .filter(value -> !value.isBlank())
            .map(value -> value.substring(0, Math.min(value.length(), 120)))
            .forEach(names::add);
        return names.stream().limit(30).toList();
    }

    private DtoProject cached(CacheKey key) {
        int ttlSeconds = properties.getCodex().getOverviewCacheSeconds();
        int maxEntries = properties.getCodex().getOverviewCacheMaxEntries();
        if (ttlSeconds <= 0 || maxEntries <= 0) return null;

        long observedGeneration;
        synchronized (cache) {
            prune(ttlSeconds);
            CacheEntry entry = cache.get(key);
            if (entry != null) return entry.project();
            observedGeneration = cacheGeneration;
        }

        // SQLite access stays outside the in-memory monitor so unrelated cached projects remain responsive.
        DtoProject persisted = persistentCache
            .find(OVERVIEW_NAMESPACE, key.toString(), DtoProject.class, clock.instant())
            .orElse(null);
        if (persisted == null) return null;

        synchronized (cache) {
            if (cacheGeneration != observedGeneration) return null;
            prune(ttlSeconds);
            CacheEntry current = cache.get(key);
            if (current != null) return current.project();
            rememberInMemory(key, persisted, maxEntries);
            return persisted;
        }
    }

    private void remember(CacheKey key, DtoProject project) {
        int ttlSeconds = properties.getCodex().getOverviewCacheSeconds();
        int maxEntries = properties.getCodex().getOverviewCacheMaxEntries();
        if (ttlSeconds <= 0 || maxEntries <= 0) return;
        synchronized (cache) {
            prune(ttlSeconds);
            // Timestamp and TTL both start at successful completion, never on page reads or failed attempts.
            rememberInMemory(key, project, maxEntries);
        }
        persistentCache.put(
            OVERVIEW_NAMESPACE,
            key.toString(),
            project,
            project.overviewUpdatedAt(),
            project.overviewUpdatedAt().plusSeconds(ttlSeconds),
            maxEntries
        );
    }

    private void rememberInMemory(CacheKey key, DtoProject project, int maxEntries) {
        cache.put(key, new CacheEntry(project.overviewUpdatedAt(), project));
        while (cache.size() > maxEntries) cache.remove(cache.keySet().iterator().next());
    }

    private void prune(int ttlSeconds) {
        Instant cutoff = clock.instant().minusSeconds(ttlSeconds);
        cache.entrySet().removeIf(entry -> !entry.getValue().loadedAt().isAfter(cutoff));
    }

    private record RepositoryKey(String id, String name, Path path, RepositoryType type) {}

    private record CacheKey(String projectId, String name, List<RepositoryKey> repositories) {}

    private record CacheEntry(Instant loadedAt, DtoProject project) {}
}
