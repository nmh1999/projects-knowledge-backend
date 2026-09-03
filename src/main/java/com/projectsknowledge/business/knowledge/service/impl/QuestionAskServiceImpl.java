package com.projectsknowledge.business.knowledge.service.impl;

import com.projectsknowledge.business.knowledge.enums.SearchMode;
import com.projectsknowledge.business.knowledge.mapper.KnowledgeAnswerMapper;
import com.projectsknowledge.business.knowledge.schema.request.ReqIntegrationDetails;
import com.projectsknowledge.business.knowledge.schema.request.ReqQuestion;
import com.projectsknowledge.business.knowledge.schema.response.DtoKnowledgeAnswer;
import com.projectsknowledge.business.knowledge.schema.response.DtoKnowledgeAnswer.SourceReference;
import com.projectsknowledge.business.knowledge.schema.response.DtoWorkflowDiagram;
import com.projectsknowledge.business.knowledge.service.QuestionAskService;
import com.projectsknowledge.business.project.entity.Project;
import com.projectsknowledge.business.project.entity.Repository;
import com.projectsknowledge.business.project.service.ProjectRetrievalService;
import com.projectsknowledge.general.cache.CacheClearable;
import com.projectsknowledge.general.cache.PersistentKnowledgeCache;
import com.projectsknowledge.general.cancellation.RequestCancellation;
import com.projectsknowledge.general.cancellation.SharedAnalysis;
import com.projectsknowledge.general.config.ProjectsKnowledgeProperties;
import com.projectsknowledge.general.integration.codex.client.CodexAppServerClient;
import com.projectsknowledge.general.integration.codex.schema.response.DtoCodexKnowledgeResult;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** Orchestrates Codex questions, validates source evidence, and caches repeated answers locally. */
@Service
public class QuestionAskServiceImpl implements QuestionAskService, CacheClearable {

    private static final String ANSWER_NAMESPACE = "answer-v2";
    private static final String INTEGRATION_NAMESPACE = "integration-v2";
    private static final String OUT_OF_SCOPE_EN =
        "This question is outside the project scope. I can only answer about the selected project's code, features, workflows, and integrations. Please ask a project-specific question.";
    private static final String OUT_OF_SCOPE_AR =
        "هذا السؤال خارج نطاق المشاريع. أستطيع الإجابة فقط عن الكود والوظائف ومسارات العمل والتكاملات في المشروع المحدد. أعد صياغة سؤالك ليكون متعلقًا به.";

    private final ProjectRetrievalService projectService;
    private final CodexAppServerClient codexClient;
    private final ProjectsKnowledgeProperties properties;
    private final Clock clock;
    private final PersistentKnowledgeCache persistentCache;
    // The cache avoids a second Codex turn for the same normalized question and is bounded by configuration.
    private final ConcurrentHashMap<CacheKey, DtoKnowledgeAnswer> answerCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<CacheKey, DtoKnowledgeAnswer> integrationCache = new ConcurrentHashMap<>();
    private final SharedAnalysis<CacheKey, DtoKnowledgeAnswer> inFlight = new SharedAnalysis<>();

    @Autowired
    public QuestionAskServiceImpl(
        ProjectRetrievalService projectService,
        CodexAppServerClient codexClient,
        ProjectsKnowledgeProperties properties,
        Clock clock,
        PersistentKnowledgeCache persistentCache
    ) {
        this.projectService = projectService;
        this.codexClient = codexClient;
        this.properties = properties;
        this.clock = clock;
        this.persistentCache = persistentCache;
    }

    QuestionAskServiceImpl(
        ProjectRetrievalService projectService,
        CodexAppServerClient codexClient,
        ProjectsKnowledgeProperties properties,
        Clock clock
    ) {
        this(projectService, codexClient, properties, clock, PersistentKnowledgeCache.disabled());
    }

    @Override
    public DtoKnowledgeAnswer ask(ReqQuestion request) {
        return answerQuestion(request, false);
    }

    @Override
    public DtoKnowledgeAnswer refresh(ReqQuestion request) {
        return answerQuestion(request, true);
    }

    private DtoKnowledgeAnswer answerQuestion(ReqQuestion request, boolean refresh) {
        Project project = projectService.requireProject(request.projectId());
        String language = normalizeLanguage(request.language());
        CacheKey key = cacheKey(project, language, normalizeQuestion(request.question()), request.mode(), false);
        return resolve(answerCache, ANSWER_NAMESPACE, key, properties.getCodex().getAnswerCacheSeconds(), refresh, () ->
            query(project, request.question(), language, request.mode())
        );
    }

    @Override
    public DtoKnowledgeAnswer explainIntegration(ReqIntegrationDetails request) {
        return integrationDetails(request, false);
    }

    @Override
    public DtoKnowledgeAnswer refreshIntegration(ReqIntegrationDetails request) {
        return integrationDetails(request, true);
    }

    @Override
    public void clearCache() {
        answerCache.clear();
        integrationCache.clear();
    }

    private DtoKnowledgeAnswer integrationDetails(ReqIntegrationDetails request, boolean refresh) {
        Project project = projectService.requireProject(request.projectId());
        String language = normalizeLanguage(request.language());
        String name = request.name().strip();
        CacheKey key = cacheKey(project, language, name.toLowerCase(Locale.ROOT), SearchMode.ADVANCED, true);
        return resolve(
            integrationCache,
            INTEGRATION_NAMESPACE,
            key,
            properties.getCodex().getIntegrationCacheSeconds(),
            refresh,
            () ->
            query(project, integrationQuestion(name, language), language, SearchMode.ADVANCED)
        );
    }

    private DtoKnowledgeAnswer query(Project project, String question, String language, SearchMode mode) {
        DtoCodexKnowledgeResult result = codexClient.ask(
            project.getRepositories().stream().map(Repository::getPath).toList(),
            question,
            language,
            mode
        );
        // Discard every model-generated section on refusal, even if the model populated them.
        if (!result.inScope()) return outOfScope(project, question, language);
        return KnowledgeAnswerMapper.toDto(project.getName(), question, result, mapSources(project, result.sources()));
    }

    private DtoKnowledgeAnswer outOfScope(Project project, String question, String language) {
        String message = "ar".equals(language) ? OUT_OF_SCOPE_AR : OUT_OF_SCOPE_EN;
        return DtoKnowledgeAnswer.builder()
            .project(project.getName())
            .question(question)
            .summary(message)
            .businessFlow(List.of())
            .technicalFlow(List.of())
            .apis(List.of())
            .database(List.of())
            .integrations(List.of())
            .scheduledJobs(List.of())
            .technicalDetails(List.of())
            .sources(List.of())
            .confidence("low")
            .keyFindings(List.of())
            .roles(List.of())
            .risks(List.of())
            .followUpQuestions(List.of())
            .enoughEvidence(false)
            .workflowExample("")
            .workflowDiagram(DtoWorkflowDiagram.empty())
            .inScope(false)
            .build();
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
            .map(repository -> repository.getPath().toAbsolutePath().normalize().toString())
            .sorted()
            .toList();
        return new CacheKey(project.getId(), project.getName(), roots, language, question, mode, integration);
    }

    private String integrationQuestion(String name, String language) {
        if ("ar".equals(language)) {
            return (
                "اشرح تكامل " +
                name +
                " اعتمادًا على الكود الفعلي فقط. وضّح الغرض منه، مكان الإعداد، " +
                "المكونات التي تستخدمه، مسار الطلب والبيانات، المصادقة، واجهات API أو الأحداث، معالجة الأخطاء، " +
                "والمخاطر. أرفق أدلة دقيقة من ملفات المستودعات."
            );
        }
        return (
            "Explain the " +
            name +
            " integration using only the actual code. Cover its purpose, configuration, " +
            "callers, request and data flow, authentication, APIs or events, error handling, and risks. " +
            "Include precise repository source evidence."
        );
    }

    private String normalizeQuestion(String question) {
        return question.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private String normalizeLanguage(String language) {
        return "ar".equalsIgnoreCase(language) ? "ar" : "en";
    }

    private List<SourceReference> mapSources(Project project, List<DtoCodexKnowledgeResult.SourceEvidence> sources) {
        return sources
            .stream()
            .map(source -> mapSource(project, source))
            .flatMap(Optional::stream)
            .toList();
    }

    private Optional<SourceReference> mapSource(Project project, DtoCodexKnowledgeResult.SourceEvidence source) {
        if (source == null || source.filePath() == null || source.filePath().isBlank()) return Optional.empty();
        Path requested;
        try {
            requested = Path.of(source.filePath());
        } catch (InvalidPathException exception) {
            return Optional.empty();
        }
        List<Repository> repositories = project
            .getRepositories()
            .stream()
            .sorted(
                Comparator.comparing(repository ->
                    source.repositoryName() == null || !repository.getName().equalsIgnoreCase(source.repositoryName())
                )
            )
            .toList();
        for (Repository repository : repositories) {
            Path root = repository.getPath().toAbsolutePath().normalize();
            Path file = requested.isAbsolute() ? requested.normalize() : root.resolve(requested).normalize();
            if (!file.startsWith(root) || !Files.isRegularFile(file)) continue;
            String relative = root.relativize(file).toString().replace('\\', '/');
            int start = Math.max(1, source.startLine());
            int end = Math.max(start, Math.min(source.endLine(), start + 200));
            return Optional.of(
                new SourceReference(
                    repository.getId(),
                    repository.getName(),
                    relative,
                    file.getFileName().toString(),
                    source.symbol(),
                    start,
                    end,
                    ""
                )
            );
        }
        return Optional.empty();
    }

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
