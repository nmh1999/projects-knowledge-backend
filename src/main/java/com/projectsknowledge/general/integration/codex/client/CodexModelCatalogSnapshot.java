package com.projectsknowledge.general.integration.codex.client;

import com.projectsknowledge.general.integration.codex.schema.response.DtoCodexModel;
import java.time.Instant;
import java.util.List;

/** Serializable backend snapshot used by the memory and SQLite model catalog cache. */
public record CodexModelCatalogSnapshot(Instant cachedAt, List<DtoCodexModel> models) {

    public CodexModelCatalogSnapshot {
        models = models == null ? List.of() : List.copyOf(models);
    }
}
