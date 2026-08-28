package com.projectsknowledge.general.integration.codex.schema.response;

import com.projectsknowledge.business.knowledge.schema.response.DtoKnowledgeAnswer;
import com.projectsknowledge.business.knowledge.schema.response.DtoWorkflowDiagram;
import java.util.List;
import lombok.Builder;

/** Business-oriented result: only actors, ordered steps, caveats, and an illustrative scenario. */
@Builder
public record DtoWorkflowKnowledgeResult(
    String answer,
    String confidence,
    List<DtoKnowledgeAnswer.RoleInfo> roles,
    List<String> businessFlow,
    String workflowExample,
    List<String> risks,
    List<DtoCodexKnowledgeResult.SourceEvidence> sources,
    DtoWorkflowDiagram workflowDiagram,
    boolean inScope
) {
    public DtoCodexKnowledgeResult toKnowledgeResult() {
        return new DtoCodexKnowledgeResult(
            answer,
            confidence,
            List.of(),
            safe(businessFlow),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            safe(roles),
            safe(risks),
            List.of(),
            safe(sources),
            workflowExample,
            workflowDiagram,
            inScope
        );
    }

    private static <T> List<T> safe(List<T> value) {
        return value == null ? List.of() : value;
    }
}
