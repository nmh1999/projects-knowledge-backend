package com.projectsknowledge.dto;

import jakarta.validation.constraints.NotBlank;

public record QuestionRequest(
        @NotBlank String projectId,
        @NotBlank String question,
        String language,
        SearchMode mode
) {
    // Older clients that omit mode retain the existing detailed behavior.
    public QuestionRequest {
        if (mode == null) mode = SearchMode.ADVANCED;
    }

    public QuestionRequest(String projectId, String question, String language) {
        this(projectId, question, language, SearchMode.ADVANCED);
    }
}
