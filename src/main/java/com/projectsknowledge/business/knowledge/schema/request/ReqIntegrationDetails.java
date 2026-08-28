package com.projectsknowledge.business.knowledge.schema.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ReqIntegrationDetails(
    @NotBlank String projectId,
    @NotBlank @Size(max = 200) String name,
    String language
) {}
