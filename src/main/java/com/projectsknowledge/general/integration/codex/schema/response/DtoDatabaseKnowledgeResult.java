package com.projectsknowledge.general.integration.codex.schema.response;

import com.projectsknowledge.business.knowledge.schema.response.DtoKnowledgeAnswer.DatabaseInfo;
import java.util.List;

/** Focused schema analysis, adapted to the shared answer without unrelated output sections. */
public record DtoDatabaseKnowledgeResult(
    String answer,
    String confidence,
    List<String> keyFindings,
    List<DatabaseInfo> database,
    List<String> risks,
    List<DtoCodexKnowledgeResult.SourceEvidence> sources,
    boolean inScope
) {
    public DtoCodexKnowledgeResult toKnowledgeResult() {
        return DtoCodexKnowledgeResult.builder()
            .answer(answer)
            .confidence(confidence)
            .keyFindings(safe(keyFindings))
            .database(safe(database))
            .risks(safe(risks))
            .sources(safe(sources))
            .businessFlow(List.of())
            .technicalFlow(List.of())
            .apis(List.of())
            .integrations(List.of())
            .scheduledJobs(List.of())
            .technicalDetails(List.of())
            .roles(List.of())
            .followUpQuestions(List.of())
            .inScope(inScope)
            .build();
    }

    private static <T> List<T> safe(List<T> value) {
        return value == null ? List.of() : List.copyOf(value);
    }
}
