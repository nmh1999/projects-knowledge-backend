package com.projectsknowledge.service;

import com.projectsknowledge.codex.CodexAppServerClient;
import com.projectsknowledge.config.ProjectsKnowledgeProperties;
import com.projectsknowledge.model.Project;
import com.projectsknowledge.model.Repository;
import com.projectsknowledge.dto.CodexKnowledgeResult;
import com.projectsknowledge.dto.IntegrationDetailsRequest;
import com.projectsknowledge.dto.KnowledgeAnswer;
import com.projectsknowledge.dto.KnowledgeAnswer.SourceReference;
import com.projectsknowledge.dto.QuestionRequest;
import com.projectsknowledge.dto.SearchMode;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Orchestrates Codex questions, validates source evidence, and caches repeated answers locally. */
@Service
public class QuestionService {
    private final ProjectService projectService;
    private final CodexAppServerClient codexClient;
    private final ProjectsKnowledgeProperties properties;
    // The cache avoids a second Codex turn for the same normalized question and is bounded by configuration.
    private final ConcurrentHashMap<CacheKey, CacheEntry> answerCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<CacheKey, CacheEntry> integrationCache = new ConcurrentHashMap<>();

    public QuestionService(ProjectService projectService, CodexAppServerClient codexClient,
                           ProjectsKnowledgeProperties properties) {
        this.projectService = projectService;
        this.codexClient = codexClient;
        this.properties = properties;
    }

    public KnowledgeAnswer ask(QuestionRequest request) {
        Project project = projectService.requireProject(request.projectId());
        String language = "ar".equalsIgnoreCase(request.language()) ? "ar" : "en";
        CacheKey key = new CacheKey(project.getId(), language, normalizeQuestion(request.question()), request.mode());
        int ttlSeconds = properties.getCodex().getAnswerCacheSeconds();
        KnowledgeAnswer cached = cachedAnswer(answerCache, key, ttlSeconds);
        if (cached != null) return cached;

        KnowledgeAnswer answer = query(project, request.question(), language, request.mode());
        cacheAnswer(answerCache, key, answer, ttlSeconds);
        return answer;
    }

    public KnowledgeAnswer explainIntegration(IntegrationDetailsRequest request) {
        Project project = projectService.requireProject(request.projectId());
        String language = "ar".equalsIgnoreCase(request.language()) ? "ar" : "en";
        String name = request.name().strip();
        CacheKey key = new CacheKey(project.getId(), language, name.toLowerCase(Locale.ROOT), SearchMode.ADVANCED);
        int ttlSeconds = properties.getCodex().getIntegrationCacheSeconds();
        KnowledgeAnswer cached = cachedAnswer(integrationCache, key, ttlSeconds);
        if (cached != null) return cached;

        String question = integrationQuestion(name, language);
        KnowledgeAnswer answer = query(project, question, language, SearchMode.ADVANCED);
        cacheAnswer(integrationCache, key, answer, ttlSeconds);
        return answer;
    }

    private KnowledgeAnswer query(Project project, String question, String language, SearchMode mode) {
        CodexKnowledgeResult result = codexClient.ask(project.getRepositories().stream().map(Repository::getPath).toList(),
                question, language, mode);
        // Discard every model-generated section on refusal, even if the model populated them.
        if (!result.inScope()) return outOfScope(project, question, language);
        return new KnowledgeAnswer(project.getName(), question, result.answer(), result.businessFlow(),
                result.technicalFlow(), result.apis(), result.database(), result.integrations(), result.scheduledJobs(),
                result.technicalDetails(), mapSources(project, result.sources()), result.confidence(), result.keyFindings(),
                result.roles(), result.risks(), result.followUpQuestions(), !"low".equalsIgnoreCase(result.confidence()),
                result.workflowExample(), result.workflowDiagram(), true);
    }

    private KnowledgeAnswer outOfScope(Project project, String question, String language) {
        String message = "ar".equals(language)
                ? "هذا السؤال خارج نطاق المشاريع. أستطيع الإجابة فقط عن الكود والوظائف ومسارات العمل والتكاملات في المشروع المحدد. أعد صياغة سؤالك ليكون متعلقًا به."
                : "This question is outside the project scope. I can only answer about the selected project's code, features, workflows, and integrations. Please ask a project-specific question.";
        return new KnowledgeAnswer(project.getName(), question, message, List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), "low", List.of(), List.of(), List.of(), List.of(), false,
                "", com.projectsknowledge.dto.WorkflowDiagram.empty(), false);
    }

    private KnowledgeAnswer cachedAnswer(ConcurrentHashMap<CacheKey, CacheEntry> cache, CacheKey key, int ttlSeconds) {
        if (ttlSeconds <= 0) return null;
        CacheEntry entry = cache.get(key);
        if (entry == null) return null;
        if (entry.loadedAt().isBefore(Instant.now().minusSeconds(ttlSeconds))) {
            cache.remove(key, entry);
            return null;
        }
        return entry.answer();
    }

    private void cacheAnswer(ConcurrentHashMap<CacheKey, CacheEntry> cache, CacheKey key,
                             KnowledgeAnswer answer, int ttlSeconds) {
        int maxEntries = properties.getCodex().getAnswerCacheMaxEntries();
        if (ttlSeconds <= 0 || maxEntries <= 0) return;
        Instant validAfter = Instant.now().minusSeconds(ttlSeconds);
        cache.entrySet().removeIf(entry -> entry.getValue().loadedAt().isBefore(validAfter));
        if (cache.size() >= maxEntries) {
            cache.entrySet().stream().min(Comparator.comparing(entry -> entry.getValue().loadedAt()))
                    .ifPresent(entry -> cache.remove(entry.getKey(), entry.getValue()));
        }
        cache.put(key, new CacheEntry(Instant.now(), answer));
    }

    private String integrationQuestion(String name, String language) {
        if ("ar".equals(language)) {
            return "اشرح تكامل " + name + " اعتمادًا على الكود الفعلي فقط. وضّح الغرض منه، مكان الإعداد، " +
                    "المكونات التي تستخدمه، مسار الطلب والبيانات، المصادقة، واجهات API أو الأحداث، معالجة الأخطاء، " +
                    "والمخاطر. أرفق أدلة دقيقة من ملفات المستودعات.";
        }
        return "Explain the " + name + " integration using only the actual code. Cover its purpose, configuration, " +
                "callers, request and data flow, authentication, APIs or events, error handling, and risks. " +
                "Include precise repository source evidence.";
    }

    private String normalizeQuestion(String question) {
        return question.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private List<SourceReference> mapSources(Project project, List<CodexKnowledgeResult.SourceEvidence> sources) {
        return sources.stream().map(source -> mapSource(project, source)).flatMap(Optional::stream).toList();
    }

    private Optional<SourceReference> mapSource(Project project, CodexKnowledgeResult.SourceEvidence source) {
        List<Repository> repositories = project.getRepositories().stream()
                .sorted(Comparator.comparing(repository ->
                        !repository.getName().equalsIgnoreCase(source.repositoryName())))
                .toList();
        for (Repository repository : repositories) {
            Path root = repository.getPath().toAbsolutePath().normalize();
            Path requested = Path.of(source.filePath());
            Path file = requested.isAbsolute() ? requested.normalize() : root.resolve(requested).normalize();
            if (!file.startsWith(root) || !Files.isRegularFile(file)) continue;
            String relative = root.relativize(file).toString().replace('\\', '/');
            int start = Math.max(1, source.startLine());
            int end = Math.max(start, Math.min(source.endLine(), start + 200));
            return Optional.of(new SourceReference(repository.getId(), repository.getName(), relative,
                    file.getFileName().toString(), source.symbol(), start, end, source.excerpt()));
        }
        return Optional.empty();
    }

    private record CacheKey(String projectId, String language, String question, SearchMode mode) {}
    private record CacheEntry(Instant loadedAt, KnowledgeAnswer answer) {}
}
