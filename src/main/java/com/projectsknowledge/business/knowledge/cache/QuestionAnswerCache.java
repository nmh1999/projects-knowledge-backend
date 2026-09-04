package com.projectsknowledge.business.knowledge.cache;

import com.projectsknowledge.business.knowledge.enums.SearchMode;
import com.projectsknowledge.business.knowledge.schema.response.DtoKnowledgeAnswer;
import com.projectsknowledge.business.project.entity.Project;
import com.projectsknowledge.business.project.entity.Repository;
import com.projectsknowledge.general.cache.CacheClearable;
import com.projectsknowledge.general.cache.PersistentKnowledgeCache;
import com.projectsknowledge.general.cancellation.RequestCancellation;
import com.projectsknowledge.general.cancellation.SharedAnalysis;
import com.projectsknowledge.general.config.ProjectsKnowledgeProperties;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/** Stores successful question results and shares identical analyses until their configured expiry. */
@Component
public class QuestionAnswerCache implements CacheClearable {

    private static final String ANSWER_NAMESPACE = "answer-v2";
    private static final String INTEGRATION_NAMESPACE = "integration-v2";

    private final ProjectsKnowledgeProperties properties;
    private final Clock clock;
    private final PersistentKnowledgeCache persistentCache;
    private final ConcurrentHashMap<CacheKey, DtoKnowledgeAnswer> answers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<CacheKey, DtoKnowledgeAnswer> integrations = new ConcurrentHashMap<>();
    private final SharedAnalysis<CacheKey, DtoKnowledgeAnswer> inFlight = new SharedAnalysis<>();

    public QuestionAnswerCache(
        ProjectsKnowledgeProperties properties,
        Clock clock,
        PersistentKnowledgeCache persistentCache
    ) {
        this.properties = properties;
        this.clock = clock;
        this.persistentCache = persistentCache;
    }

    public DtoKnowledgeAnswer resolveAnswer(
        Project project,
        String language,
        String normalizedQuestion,
        SearchMode mode,
        boolean refresh,
        Supplier<DtoKnowledgeAnswer> analyze
    ) {
        CacheKey key = cacheKey(project, language, normalizedQuestion, mode, false);
        return resolve(
            answers,
            ANSWER_NAMESPACE,
            key,
            properties.getCodex().getAnswerCacheSeconds(),
            refresh,
            analyze
        );
    }

    public DtoKnowledgeAnswer resolveIntegration(
        Project project,
        String language,
        String normalizedName,
        boolean refresh,
        Supplier<DtoKnowledgeAnswer> analyze
    ) {
        CacheKey key = cacheKey(project, language, normalizedName, SearchMode.ADVANCED, true);
        return resolve(
            integrations,
            INTEGRATION_NAMESPACE,
            key,
            properties.getCodex().getIntegrationCacheSeconds(),
            refresh,
            analyze
        );
    }

    @Override
    public void clearCache() {
        answers.clear();
        integrations.clear();
    }

    /** Share concurrent work; refresh replaces the snapshot only after a successful analysis. */
    private DtoKnowledgeAnswer resolve(
        ConcurrentHashMap<CacheKey, DtoKnowledgeAnswer> cache,
        String namespace,
        CacheKey key,
        int ttlSeconds,
        boolean refresh,
        Supplier<DtoKnowledgeAnswer> analyze
    ) {
        RequestCancellation.check();
        boolean cacheEnabled = ttlSeconds > 0 && properties.getCodex().getAnswerCacheMaxEntries() > 0;
        if (!refresh && cacheEnabled) {
            DtoKnowledgeAnswer cached = cachedAnswer(cache, namespace, key);
            if (cached != null) return cached;
        }
        return inFlight.run(key, () -> {
            // Another request may have completed between the first cache lookup and acquiring this slot.
            DtoKnowledgeAnswer answer = !refresh && cacheEnabled ? cachedAnswer(cache, namespace, key) : null;
            if (answer == null) {
                DtoKnowledgeAnswer result = analyze.get();
                RequestCancellation.check();
                Instant completedAt = clock.instant();
                answer = result
                    .toBuilder()
                    .updatedAt(completedAt)
                    .expiresAt(cacheEnabled ? completedAt.plusSeconds(ttlSeconds) : null)
                    .build();
                DtoKnowledgeAnswer snapshot = answer;
                if (cacheEnabled) RequestCancellation.publish(() -> cacheAnswer(cache, namespace, key, snapshot));
            }
            return answer;
        });
    }

    private DtoKnowledgeAnswer cachedAnswer(
        ConcurrentHashMap<CacheKey, DtoKnowledgeAnswer> cache,
        String namespace,
        CacheKey key
    ) {
        DtoKnowledgeAnswer answer = cache.get(key);
        if (answer != null && !clock.instant().isBefore(answer.expiresAt())) {
            cache.remove(key, answer);
            return null;
        }
        if (answer != null) return answer;
        DtoKnowledgeAnswer persisted = persistentCache
            .find(namespace, key.toString(), DtoKnowledgeAnswer.class, clock.instant())
            .orElse(null);
        if (persisted != null) rememberInMemory(cache, key, persisted);
        return persisted;
    }

    private void cacheAnswer(
        ConcurrentHashMap<CacheKey, DtoKnowledgeAnswer> cache,
        String namespace,
        CacheKey key,
        DtoKnowledgeAnswer answer
    ) {
        rememberInMemory(cache, key, answer);
        persistentCache.put(
            namespace,
            key.toString(),
            answer,
            answer.updatedAt(),
            answer.expiresAt(),
            properties.getCodex().getAnswerCacheMaxEntries()
        );
    }

    private void rememberInMemory(
        ConcurrentHashMap<CacheKey, DtoKnowledgeAnswer> cache,
        CacheKey key,
        DtoKnowledgeAnswer answer
    ) {
        // Serialize only bounded cache maintenance, never the expensive model request.
        synchronized (cache) {
            Instant now = clock.instant();
            cache.entrySet().removeIf(entry -> !now.isBefore(entry.getValue().expiresAt()));
            if (!cache.containsKey(key) && cache.size() >= properties.getCodex().getAnswerCacheMaxEntries()) {
                cache
                    .entrySet()
                    .stream()
                    .min(Comparator.comparing(entry -> entry.getValue().updatedAt()))
                    .ifPresent(entry -> cache.remove(entry.getKey(), entry.getValue()));
            }
            cache.put(key, answer);
        }
    }

    private CacheKey cacheKey(Project project, String language, String question, SearchMode mode, boolean integration) {
        // Keep cache reads independent of repository size. Manual refresh and TTL control source freshness.
        List<String> roots = project
            .getRepositories()
            .stream()
            .map(Repository::getPath)
            .map(path -> path.toAbsolutePath().normalize().toString())
            .sorted()
            .toList();
        return new CacheKey(project.getId(), project.getName(), roots, language, question, mode, integration);
    }

    /** Keep these fields unchanged so existing persistent cache keys remain valid. */
    private record CacheKey(
        String projectId,
        String projectName,
        List<String> roots,
        String language,
        String question,
        SearchMode mode,
        boolean integration
    ) {}
}
