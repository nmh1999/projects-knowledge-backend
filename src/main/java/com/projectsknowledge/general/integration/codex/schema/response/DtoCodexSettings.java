package com.projectsknowledge.general.integration.codex.schema.response;

import java.util.List;

public record DtoCodexSettings(DtoCodexStatus status, String selectedModel, List<DtoCodexModel> models) {
    public static DtoCodexSettings unavailable(DtoCodexStatus status, String selectedModel) {
        return new DtoCodexSettings(status, selectedModel == null ? "" : selectedModel.strip(), List.of());
    }
}
