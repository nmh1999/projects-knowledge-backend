package com.projectsknowledge.support;

import com.projectsknowledge.business.knowledge.schema.response.DtoWorkflowDiagram;
import com.projectsknowledge.business.project.entity.Project;
import com.projectsknowledge.business.project.entity.Repository;
import com.projectsknowledge.business.project.enums.RepositoryType;
import com.projectsknowledge.general.integration.codex.schema.response.DtoCodexKnowledgeResult;
import com.projectsknowledge.general.integration.codex.schema.response.DtoCodexProjectOverview;
import java.nio.file.Path;
import java.util.List;

/** Shared builders for concise tests whose assertions do not depend on unrelated DTO fields. */
public final class TestFixtures {

    private TestFixtures() {}

    public static Project project(String id, String name, Repository... repositories) {
        return project(id, name, List.of(repositories));
    }

    public static Project project(String id, String name, List<Repository> repositories) {
        return Project.builder().id(id).name(name).repositories(repositories).build();
    }

    public static Repository repository(String id, String name, Path path, RepositoryType type) {
        return Repository.builder().id(id).name(name).path(path).type(type).build();
    }

    public static Repository backendRepository(String id, Path path) {
        return repository(id, id, path, RepositoryType.BACKEND);
    }

    public static DtoCodexKnowledgeResult.DtoCodexKnowledgeResultBuilder codexAnswerBuilder(String answer) {
        return DtoCodexKnowledgeResult.builder()
            .answer(answer)
            .confidence("high")
            .keyFindings(List.of())
            .businessFlow(List.of())
            .technicalFlow(List.of())
            .apis(List.of())
            .database(List.of())
            .integrations(List.of())
            .scheduledJobs(List.of())
            .technicalDetails(List.of())
            .roles(List.of())
            .risks(List.of())
            .followUpQuestions(List.of())
            .sources(List.of())
            .workflowExample("")
            .workflowDiagram(DtoWorkflowDiagram.empty())
            .inScope(true);
    }

    public static DtoCodexProjectOverview.DtoCodexProjectOverviewBuilder projectOverviewBuilder() {
        return DtoCodexProjectOverview.builder()
            .frontend(List.of())
            .backend(List.of())
            .databases(List.of())
            .domains(List.of())
            .integrations(List.of())
            .messaging(List.of())
            .scheduledJobs(List.of());
    }
}
