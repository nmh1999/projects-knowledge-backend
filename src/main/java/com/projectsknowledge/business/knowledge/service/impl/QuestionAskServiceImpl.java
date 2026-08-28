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
import com.projectsknowledge.general.config.ProjectsKnowledgeProperties;
import com.projectsknowledge.general.integration.codex.client.CodexAppServerClient;
import com.projectsknowledge.general.integration.codex.schema.response.DtoCodexKnowledgeResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Orchestrates Codex questions, validates source evidence, and caches repeated answers locally. */
@Service
@RequiredArgsConstructor
public class QuestionAskServiceImpl implements QuestionAskService {

    private final ProjectRetrievalService projectService;
    private final CodexAppServerClient codexClient;
    private final ProjectsKnowledgeProperties properties;
    // The cache avoids a second Codex turn for the same normalized question and is bounded by configuration.
    private final ConcurrentHashMap<CacheKey, CacheEntry> answerCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<CacheKey, CacheEntry> integrationCache = new ConcurrentHashMap<>();

    @Override
    public DtoKnowledgeAnswer ask(ReqQuestion request) {
        Project project = projectService.requireProject(request.projectId());
        String language = "ar".equalsIgnoreCase(request.language()) ? "ar" : "en";
        CacheKey key = new CacheKey(project.getId(), language, normalizeQuestion(request.question()), request.mode());
        int ttlSeconds = properties.getCodex().getAnswerCacheSeconds();
        DtoKnowledgeAnswer cached = cachedAnswer(answerCache, key, ttlSeconds);
        if (cached != null) return cached;

        DtoKnowledgeAnswer answer = query(project, request.question(), language, request.mode());
        cacheAnswer(answerCache, key, answer, ttlSeconds);
        return answer;
    }

    @Override
    public DtoKnowledgeAnswer explainIntegration(ReqIntegrationDetails request) {
        Project project = projectService.requireProject(request.projectId());
        String language = "ar".equalsIgnoreCase(request.language()) ? "ar" : "en";
        String name = request.name().strip();
        CacheKey key = new CacheKey(project.getId(), language, name.toLowerCase(Locale.ROOT), SearchMode.ADVANCED);
        int ttlSeconds = properties.getCodex().getIntegrationCacheSeconds();
        DtoKnowledgeAnswer cached = cachedAnswer(integrationCache, key, ttlSeconds);
        if (cached != null) return cached;

        String question = integrationQuestion(name, language);
        DtoKnowledgeAnswer answer = query(project, question, language, SearchMode.ADVANCED);
        cacheAnswer(integrationCache, key, answer, ttlSeconds);
        return answer;
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
        String message = "ar".equals(language)
            ? "هذا السؤال خارج نطاق المشاريع. أستطيع الإجابة فقط عن الكود والوظائف ومسارات العمل والتكاملات في المشروع المحدد. أعد صياغة سؤالك ليكون متعلقًا به."
            : "This question is outside the project scope. I can only answer about the selected project's code, features, workflows, and integrations. Please ask a project-specific question.";
        return new DtoKnowledgeAnswer(
            project.getName(),
            question,
            message,
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            "low",
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            false,
            "",
            com.projectsknowledge.business.knowledge.schema.response.DtoWorkflowDiagram.empty(),
            false
        );
    }

    private DtoKnowledgeAnswer cachedAnswer(
        ConcurrentHashMap<CacheKey, CacheEntry> cache,
        CacheKey key,
        int ttlSeconds
    ) {
        if (ttlSeconds <= 0) return null;
        CacheEntry entry = cache.get(key);
        if (entry == null) return null;
        if (entry.loadedAt().isBefore(Instant.now().minusSeconds(ttlSeconds))) {
            cache.remove(key, entry);
            return null;
        }
        return entry.answer();
    }

    private void cacheAnswer(
        ConcurrentHashMap<CacheKey, CacheEntry> cache,
        CacheKey key,
        DtoKnowledgeAnswer answer,
        int ttlSeconds
    ) {
        int maxEntries = properties.getCodex().getAnswerCacheMaxEntries();
        if (ttlSeconds <= 0 || maxEntries <= 0) return;
        Instant validAfter = Instant.now().minusSeconds(ttlSeconds);
        cache.entrySet().removeIf(entry -> entry.getValue().loadedAt().isBefore(validAfter));
        if (cache.size() >= maxEntries) {
            cache
                .entrySet()
                .stream()
                .min(Comparator.comparing(entry -> entry.getValue().loadedAt()))
                .ifPresent(entry -> cache.remove(entry.getKey(), entry.getValue()));
        }
        cache.put(key, new CacheEntry(Instant.now(), answer));
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

    private List<SourceReference> mapSources(Project project, List<DtoCodexKnowledgeResult.SourceEvidence> sources) {
        return sources
            .stream()
            .map(source -> mapSource(project, source))
            .flatMap(Optional::stream)
            .toList();
    }

    private Optional<SourceReference> mapSource(Project project, DtoCodexKnowledgeResult.SourceEvidence source) {
        List<Repository> repositories = project
            .getRepositories()
            .stream()
            .sorted(Comparator.comparing(repository -> !repository.getName().equalsIgnoreCase(source.repositoryName())))
            .toList();
        for (Repository repository : repositories) {
            Path root = repository.getPath().toAbsolutePath().normalize();
            Path requested = Path.of(source.filePath());
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
                    source.excerpt()
                )
            );
        }
        return Optional.empty();
    }

    private record CacheKey(String projectId, String language, String question, SearchMode mode) {}

    private record CacheEntry(Instant loadedAt, DtoKnowledgeAnswer answer) {}
}
