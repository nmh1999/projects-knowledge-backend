package com.projectsknowledge.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record IntegrationDetailsRequest(
        @NotBlank String projectId,
        @NotBlank @Size(max = 200) String name,
        String language
) {}
