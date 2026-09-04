package com.projectsknowledge.business.knowledge.service.impl;

import com.projectsknowledge.business.knowledge.cache.QuestionAnswerCache;
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
import com.projectsknowledge.general.exception.KnowledgeException;
import com.projectsknowledge.general.integration.codex.client.CodexAppServerClient;
import com.projectsknowledge.general.integration.codex.schema.response.DtoCodexKnowledgeResult;
import com.projectsknowledge.general.scanner.RepositoryScanner;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Orchestrates Codex questions and validates the source evidence returned with each answer. */
@Service
@RequiredArgsConstructor
public class QuestionAskServiceImpl implements QuestionAskService {

    private static final String OUT_OF_SCOPE_EN =
        "This question is outside the project scope. I can only answer about the selected project's code, features, workflows, and integrations. Please ask a project-specific question.";
    private static final String OUT_OF_SCOPE_AR =
        "هذا السؤال خارج نطاق المشاريع. أستطيع الإجابة فقط عن الكود والوظائف ومسارات العمل والتكاملات في المشروع المحدد. أعد صياغة سؤالك ليكون متعلقًا به.";

    private final ProjectRetrievalService projectService;
    private final CodexAppServerClient codexClient;
    private final QuestionAnswerCache answerCache;
    private final RepositoryScanner scanner;

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
        return answerCache.resolveAnswer(
            project,
            language,
            normalizeQuestion(request.question()),
            request.mode(),
            refresh,
            () -> query(project, request.question(), language, request.mode())
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

    private DtoKnowledgeAnswer integrationDetails(ReqIntegrationDetails request, boolean refresh) {
        Project project = projectService.requireProject(request.projectId());
        String language = normalizeLanguage(request.language());
        String name = request.name().strip();
        return answerCache.resolveIntegration(
            project,
            language,
            name.toLowerCase(Locale.ROOT),
            refresh,
            () -> query(project, integrationQuestion(name, language), language, SearchMode.ADVANCED)
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
            Path candidate = requested.isAbsolute() ? requested.normalize() : root.resolve(requested).normalize();
            try {
                scanner.resolveSource(repository, source.filePath());
                String relative = root.relativize(candidate).toString().replace('\\', '/');
                int start = Math.max(1, source.startLine());
                int end = Math.max(start, Math.min(source.endLine(), start + 200));
                return Optional.of(
                    new SourceReference(
                        repository.getId(),
                        repository.getName(),
                        relative,
                        candidate.getFileName().toString(),
                        source.symbol(),
                        start,
                        end,
                        ""
                    )
                );
            } catch (KnowledgeException | InvalidPathException ignored) {
                // Another selected repository may contain the same relative source path.
            }
        }
        return Optional.empty();
    }

}
