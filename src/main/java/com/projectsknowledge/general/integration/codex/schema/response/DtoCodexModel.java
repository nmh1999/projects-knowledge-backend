package com.projectsknowledge.general.integration.codex.schema.response;

import java.util.List;

public record DtoCodexModel(
    String id,
    String displayName,
    String description,
    boolean defaultModel,
    String defaultReasoningEffort,
    List<DtoCodexReasoningEffort> reasoningEfforts
) {}
