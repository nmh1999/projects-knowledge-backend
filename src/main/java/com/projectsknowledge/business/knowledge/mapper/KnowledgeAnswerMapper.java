package com.projectsknowledge.business.knowledge.mapper;

import com.projectsknowledge.business.knowledge.schema.response.DtoKnowledgeAnswer;
import com.projectsknowledge.business.knowledge.schema.response.DtoKnowledgeAnswer.SourceReference;
import com.projectsknowledge.general.integration.codex.schema.response.DtoCodexKnowledgeResult;
import java.util.List;

/** Converts a model result only after project scope and source evidence have been checked. */
public final class KnowledgeAnswerMapper {

    private KnowledgeAnswerMapper() {}

    public static DtoKnowledgeAnswer toDto(
        String project,
        String question,
        DtoCodexKnowledgeResult result,
        List<SourceReference> sources
    ) {
        return DtoKnowledgeAnswer.builder()
            .project(project)
            .question(question)
            .summary(result.answer())
            .businessFlow(result.businessFlow())
            .technicalFlow(result.technicalFlow())
            .apis(result.apis())
            .database(result.database())
            .integrations(result.integrations())
            .scheduledJobs(result.scheduledJobs())
            .technicalDetails(result.technicalDetails())
            .sources(sources)
            .confidence(result.confidence())
            .keyFindings(result.keyFindings())
            .roles(result.roles())
            .risks(result.risks())
            .followUpQuestions(result.followUpQuestions())
            .enoughEvidence(!"low".equalsIgnoreCase(result.confidence()))
            .workflowExample(result.workflowExample())
            .workflowDiagram(result.workflowDiagram())
            .inScope(true)
            .build();
    }
}
