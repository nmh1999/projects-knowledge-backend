package com.projectsknowledge.dto;

import java.util.List;

/** Basic asks Codex for only these fields, not a full answer that is hidden by the UI. */
public record BasicKnowledgeResult(
        String answer, String confidence, boolean inScope
) {
    public CodexKnowledgeResult toKnowledgeResult() {
        return new CodexKnowledgeResult(answer, confidence,
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), "", WorkflowDiagram.empty(), inScope);
    }
}
