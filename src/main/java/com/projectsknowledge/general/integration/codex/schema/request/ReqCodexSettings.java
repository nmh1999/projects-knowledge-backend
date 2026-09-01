package com.projectsknowledge.general.integration.codex.schema.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record ReqCodexSettings(@NotNull String model, @NotBlank String reasoningEffort) {}
