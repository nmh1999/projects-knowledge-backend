package com.projectsknowledge.business.knowledge.schema.request;

import com.projectsknowledge.business.knowledge.enums.SearchMode;
import jakarta.validation.constraints.NotBlank;

public record ReqQuestion(@NotBlank String projectId, @NotBlank String question, String language, SearchMode mode) {
    // Older clients that omit mode retain the existing detailed behavior.
    public ReqQuestion {
        if (mode == null) mode = SearchMode.ADVANCED;
    }

    public ReqQuestion(String projectId, String question, String language) {
        this(projectId, question, language, SearchMode.ADVANCED);
    }
}
