package com.projectsknowledge.general.integration.codex.schema.response;

import com.projectsknowledge.business.knowledge.schema.response.DtoWorkflowDiagram;
import java.util.List;
import lombok.Builder;

/** Basic asks Codex for only these fields, not a full answer that is hidden by the UI. */
@Builder
public record DtoBasicKnowledgeResult(String answer, String confidence, boolean inScope) {
    public DtoCodexKnowledgeResult toKnowledgeResult() {
        return new DtoCodexKnowledgeResult(
            answer,
            confidence,
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            "",
            DtoWorkflowDiagram.empty(),
            inScope
        );
    }
}
