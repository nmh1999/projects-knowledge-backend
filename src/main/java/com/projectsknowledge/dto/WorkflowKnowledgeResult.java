package com.projectsknowledge.dto;

import java.util.List;

/** Business-oriented result: only actors, ordered steps, caveats, and an illustrative scenario. */
public record WorkflowKnowledgeResult(
        String answer, String confidence, List<KnowledgeAnswer.RoleInfo> roles,
        List<String> businessFlow, String workflowExample, List<String> risks,
        List<CodexKnowledgeResult.SourceEvidence> sources, WorkflowDiagram workflowDiagram, boolean inScope
) {
    public CodexKnowledgeResult toKnowledgeResult() {
        return new CodexKnowledgeResult(answer, confidence, List.of(), safe(businessFlow),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                safe(roles), safe(risks), List.of(), safe(sources), workflowExample, workflowDiagram, inScope);
    }

    private static <T> List<T> safe(List<T> value) { return value == null ? List.of() : value; }
}
