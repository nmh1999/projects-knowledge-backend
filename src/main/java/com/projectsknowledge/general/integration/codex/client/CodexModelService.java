package com.projectsknowledge.general.integration.codex.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.projectsknowledge.general.cache.CacheClearable;
import com.projectsknowledge.general.cache.PersistentKnowledgeCache;
import com.projectsknowledge.general.config.CodexRuntimeSettings;
import com.projectsknowledge.general.config.ProjectsKnowledgeProperties;
import com.projectsknowledge.general.exception.ApiErrorCode;
import com.projectsknowledge.general.exception.KnowledgeException;
import com.projectsknowledge.general.integration.codex.schema.request.ReqCodexSettings;
import com.projectsknowledge.general.integration.codex.schema.response.DtoCodexModel;
import com.projectsknowledge.general.integration.codex.schema.response.DtoCodexReasoningEffort;
import com.projectsknowledge.general.integration.codex.schema.response.DtoCodexSettings;
import com.projectsknowledge.general.integration.codex.schema.response.DtoCodexStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** Reads, validates, and caches the model capabilities exposed by the local Codex runtime. */
@Service
@RequiredArgsConstructor
public class CodexModelService implements CacheClearable {

    private static final String MODEL_CACHE_NAMESPACE = "codex-model-catalog-v1";
    private static final String MODEL_CACHE_KEY = "available-models";

    private final ProjectsKnowledgeProperties properties;
    private final CodexRuntimeSettings runtimeSettings;
    private final CodexAppServerTransport transport;
    private final Clock clock;
    private final PersistentKnowledgeCache persistentCache;
    private final Object cacheMonitor = new Object();
    private volatile CodexModelCatalogSnapshot cache;

    @Override
    public void clearCache() {
        synchronized (cacheMonitor) {
            cache = null;
        }
    }

    /** Lightweight capability check; never exposes account identifiers or starts a model turn. */
    public DtoCodexStatus status() {
        var selected = runtimeSettings.current();
        if (!properties.getCodex().isEnabled()) {
            return DtoCodexStatus.disabled(selected.model(), selected.reasoningEffort());
        }
        try {
            CodexAppServerConnection connection = transport.connection();
            return buildStatus(connection, readAccount(connection), selected.model(), selected.reasoningEffort());
        } catch (KnowledgeException exception) {
            return DtoCodexStatus.unavailable(selected.model(), selected.reasoningEffort());
        }
    }

    /** Available models and efforts come directly from the connected Codex runtime. */
    public DtoCodexSettings settings() {
        var selected = runtimeSettings.current();
        if (!properties.getCodex().isEnabled()) {
            return DtoCodexSettings.unavailable(
                DtoCodexStatus.disabled(selected.model(), selected.reasoningEffort()),
                selected.model()
            );
        }
        try {
            CodexAppServerConnection connection = transport.connection();
            return buildSettings(connection, readAccount(connection), models(connection), selected);
        } catch (KnowledgeException exception) {
            return DtoCodexSettings.unavailable(
                DtoCodexStatus.unavailable(selected.model(), selected.reasoningEffort()),
                selected.model()
            );
        }
    }

    public DtoCodexSettings updateSettings(ReqCodexSettings request) {
        if (!properties.getCodex().isEnabled()) throw new KnowledgeException(
            HttpStatus.SERVICE_UNAVAILABLE,
            ApiErrorCode.CODEX_UNAVAILABLE,
            "Codex is disabled.",
            true
        );
        CodexAppServerConnection connection = transport.connection();
        JsonNode account = readAccount(connection);
        List<DtoCodexModel> models = models(connection);
        DtoCodexModel selectedModel = findModel(models, request.model());
        if (selectedModel == null) throw new KnowledgeException(HttpStatus.BAD_REQUEST, "The selected model is unavailable.");
        String effort = request.reasoningEffort().strip().toLowerCase(java.util.Locale.ROOT);
        boolean supported = selectedModel.reasoningEfforts().stream().anyMatch(option -> option.value().equals(effort));
        if (!supported) throw new KnowledgeException(
            HttpStatus.BAD_REQUEST,
            "The selected reasoning effort is not supported by this model."
        );
        var saved = runtimeSettings.update(request.model(), effort);
        return buildSettings(connection, account, models, saved);
    }

    private JsonNode readAccount(CodexAppServerConnection connection) {
        return connection.request("account/read", Map.of("refreshToken", false), setupTimeout());
    }

    private List<DtoCodexModel> models(CodexAppServerConnection connection) {
        int ttlSeconds = Math.max(0, properties.getCodex().getModelCacheSeconds());
        if (ttlSeconds == 0) return loadModels(connection);
        Instant now = clock.instant();
        CodexModelCatalogSnapshot observed = cache;
        if (isFresh(observed, now, ttlSeconds)) return observed.models();
        synchronized (cacheMonitor) {
            now = clock.instant();
            if (isFresh(cache, now, ttlSeconds)) return cache.models();
            CodexModelCatalogSnapshot persisted = persistentCache
                .find(MODEL_CACHE_NAMESPACE, MODEL_CACHE_KEY, CodexModelCatalogSnapshot.class, now)
                .orElse(null);
            if (isFresh(persisted, now, ttlSeconds)) {
                cache = persisted;
                return persisted.models();
            }
            List<DtoCodexModel> loaded = loadModels(connection);
            // A transient empty response must not hide the model catalog for five hours.
            if (loaded.isEmpty()) return loaded;
            CodexModelCatalogSnapshot snapshot = new CodexModelCatalogSnapshot(now, loaded);
            persistentCache.put(
                MODEL_CACHE_NAMESPACE,
                MODEL_CACHE_KEY,
                snapshot,
                now,
                now.plusSeconds(ttlSeconds),
                1
            );
            cache = snapshot;
            return snapshot.models();
        }
    }

    private List<DtoCodexModel> loadModels(CodexAppServerConnection connection) {
        JsonNode response = connection.request(
            "model/list",
            Map.of("limit", 100, "includeHidden", false),
            setupTimeout()
        );
        List<DtoCodexModel> models = new ArrayList<>();
        for (JsonNode item : response.path("data")) {
            String id = item.path("model").asText(item.path("id").asText(""));
            if (id.isBlank()) continue;
            String defaultEffort = item.path("defaultReasoningEffort").asText("");
            List<DtoCodexReasoningEffort> efforts = new ArrayList<>();
            for (JsonNode option : item.path("supportedReasoningEfforts")) {
                String value = option.path("reasoningEffort").asText("");
                if (!value.isBlank()) efforts.add(
                    new DtoCodexReasoningEffort(value, option.path("description").asText(""))
                );
            }
            if (efforts.isEmpty() && !defaultEffort.isBlank()) {
                efforts.add(new DtoCodexReasoningEffort(defaultEffort, ""));
            }
            models.add(
                new DtoCodexModel(
                    id,
                    item.path("displayName").asText(id),
                    item.path("description").asText(""),
                    item.path("isDefault").asBoolean(false),
                    defaultEffort,
                    List.copyOf(efforts)
                )
            );
        }
        return List.copyOf(models);
    }

    private DtoCodexSettings buildSettings(
        CodexAppServerConnection connection,
        JsonNode account,
        List<DtoCodexModel> models,
        CodexRuntimeSettings.Selection selected
    ) {
        DtoCodexModel effective = findModel(models, selected.model());
        String effectiveModel = effective == null ? selected.model() : effective.id();
        DtoCodexStatus status = buildStatus(connection, account, effectiveModel, selected.reasoningEffort());
        return new DtoCodexSettings(status, selected.model(), models);
    }

    private DtoCodexStatus buildStatus(
        CodexAppServerConnection connection,
        JsonNode account,
        String model,
        String reasoningEffort
    ) {
        JsonNode accountDetails = account.path("account");
        boolean authenticated = !accountDetails.isMissingNode() && !accountDetails.isNull();
        boolean ready = authenticated || !account.path("requiresOpenaiAuth").asBoolean(true);
        return new DtoCodexStatus(
            true,
            true,
            ready,
            authenticated ? accountDetails.path("type").asText("") : "",
            model,
            reasoningEffort,
            connection.activeTurns()
        );
    }

    private DtoCodexModel findModel(List<DtoCodexModel> models, String selectedModel) {
        if (selectedModel == null || selectedModel.isBlank()) {
            return models.stream().filter(DtoCodexModel::defaultModel).findFirst().orElse(null);
        }
        return models.stream().filter(model -> model.id().equals(selectedModel.strip())).findFirst().orElse(null);
    }

    private boolean isFresh(CodexModelCatalogSnapshot snapshot, Instant now, int ttlSeconds) {
        return snapshot != null &&
            snapshot.cachedAt() != null &&
            !snapshot.cachedAt().isAfter(now) &&
            now.isBefore(snapshot.cachedAt().plusSeconds(ttlSeconds));
    }

    private Duration setupTimeout() {
        return Duration.ofSeconds(Math.max(1, Math.min(30, properties.getCodex().getTimeoutSeconds())));
    }
}
