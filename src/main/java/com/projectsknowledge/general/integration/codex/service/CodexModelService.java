package com.projectsknowledge.general.integration.codex.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.projectsknowledge.general.cache.CacheClearable;
import com.projectsknowledge.general.cache.PersistentKnowledgeCache;
import com.projectsknowledge.general.config.CodexRuntimeSettings;
import com.projectsknowledge.general.config.ProjectsKnowledgeProperties;
import com.projectsknowledge.general.exception.ApiErrorCode;
import com.projectsknowledge.general.exception.KnowledgeException;
import com.projectsknowledge.general.integration.codex.client.CodexAppServerTransport;
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
    private volatile ModelCatalogSnapshot cache;

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
            return buildStatus(readAccount(), selected.model(), selected.reasoningEffort());
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
            return buildSettings(readAccount(), models(), selected);
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
        JsonNode account = readAccount();
        List<DtoCodexModel> models = models();
        DtoCodexModel selectedModel = findModel(models, request.model());
        if (selectedModel == null) throw new KnowledgeException(HttpStatus.BAD_REQUEST, "The selected model is unavailable.");
        String effort = request.reasoningEffort().strip().toLowerCase(java.util.Locale.ROOT);
        boolean supported = selectedModel.reasoningEfforts().stream().anyMatch(option -> option.value().equals(effort));
        if (!supported) throw new KnowledgeException(
            HttpStatus.BAD_REQUEST,
            "The selected reasoning effort is not supported by this model."
        );
        var saved = runtimeSettings.update(request.model(), effort);
        return buildSettings(account, models, saved);
    }

    /** Resolves the automatic option to the highest model offered by the connected Codex runtime. */
    public CodexRuntimeSettings.Selection selectionForRequest() {
        var selected = runtimeSettings.current();
        if (!selected.model().isBlank()) return selected;
        DtoCodexModel model = findModel(models(), selected.model());
        if (model == null) return selected;
        return new CodexRuntimeSettings.Selection(model.id(), supportedEffort(model, selected.reasoningEffort()));
    }

    private JsonNode readAccount() {
        return transport.request("account/read", Map.of("refreshToken", false), setupTimeout());
    }

    private List<DtoCodexModel> models() {
        int ttlSeconds = Math.max(0, properties.getCodex().getModelCacheSeconds());
        if (ttlSeconds == 0) return loadModels();
        Instant now = clock.instant();
        ModelCatalogSnapshot observed = cache;
        if (isFresh(observed, now, ttlSeconds)) return observed.models();
        synchronized (cacheMonitor) {
            now = clock.instant();
            if (isFresh(cache, now, ttlSeconds)) return cache.models();
            ModelCatalogSnapshot persisted = persistentCache
                .find(MODEL_CACHE_NAMESPACE, MODEL_CACHE_KEY, ModelCatalogSnapshot.class, now)
                .orElse(null);
            if (isFresh(persisted, now, ttlSeconds)) {
                cache = persisted;
                return persisted.models();
            }
            List<DtoCodexModel> loaded = loadModels();
            // A transient empty response must not hide the model catalog for five hours.
            if (loaded.isEmpty()) return loaded;
            ModelCatalogSnapshot snapshot = new ModelCatalogSnapshot(now, loaded);
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

    private List<DtoCodexModel> loadModels() {
        JsonNode response = transport.request(
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
        JsonNode account,
        List<DtoCodexModel> models,
        CodexRuntimeSettings.Selection selected
    ) {
        DtoCodexModel effective = findModel(models, selected.model());
        String effectiveModel = effective == null ? selected.model() : effective.id();
        String effectiveEffort = effective == null
            ? selected.reasoningEffort()
            : supportedEffort(effective, selected.reasoningEffort());
        DtoCodexStatus status = buildStatus(account, effectiveModel, effectiveEffort);
        return new DtoCodexSettings(status, selected.model(), models);
    }

    private DtoCodexStatus buildStatus(
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
            transport.activeTurns()
        );
    }

    private DtoCodexModel findModel(List<DtoCodexModel> models, String selectedModel) {
        if (selectedModel == null || selectedModel.isBlank()) {
            return models.isEmpty() ? null : models.getFirst();
        }
        return models.stream().filter(model -> model.id().equals(selectedModel.strip())).findFirst().orElse(null);
    }

    private String supportedEffort(DtoCodexModel model, String selectedEffort) {
        boolean supported = model.reasoningEfforts().stream().anyMatch(option -> option.value().equals(selectedEffort));
        if (supported) return selectedEffort;
        if (!model.defaultReasoningEffort().isBlank()) return model.defaultReasoningEffort();
        return model.reasoningEfforts().isEmpty() ? selectedEffort : model.reasoningEfforts().getFirst().value();
    }

    private boolean isFresh(ModelCatalogSnapshot snapshot, Instant now, int ttlSeconds) {
        return snapshot != null &&
            snapshot.cachedAt() != null &&
            !snapshot.cachedAt().isAfter(now) &&
            now.isBefore(snapshot.cachedAt().plusSeconds(ttlSeconds));
    }

    private Duration setupTimeout() {
        return Duration.ofSeconds(Math.max(1, Math.min(30, properties.getCodex().getTimeoutSeconds())));
    }

    private record ModelCatalogSnapshot(Instant cachedAt, List<DtoCodexModel> models) {
        private ModelCatalogSnapshot {
            models = models == null ? List.of() : List.copyOf(models);
        }
    }
}
