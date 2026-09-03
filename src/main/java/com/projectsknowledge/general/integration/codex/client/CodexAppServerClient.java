package com.projectsknowledge.general.integration.codex.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.projectsknowledge.business.knowledge.enums.SearchMode;
import com.projectsknowledge.general.cache.CacheClearable;
import com.projectsknowledge.general.cache.PersistentKnowledgeCache;
import com.projectsknowledge.general.cancellation.RequestCancellation;
import com.projectsknowledge.general.config.CodexRuntimeSettings;
import com.projectsknowledge.general.config.ProjectsKnowledgeProperties;
import com.projectsknowledge.general.exception.ApiErrorCode;
import com.projectsknowledge.general.exception.KnowledgeException;
import com.projectsknowledge.general.integration.codex.schema.request.ReqCodexSettings;
import com.projectsknowledge.general.integration.codex.schema.response.DtoBasicKnowledgeResult;
import com.projectsknowledge.general.integration.codex.schema.response.DtoCodexKnowledgeResult;
import com.projectsknowledge.general.integration.codex.schema.response.DtoCodexModel;
import com.projectsknowledge.general.integration.codex.schema.response.DtoCodexProjectOverview;
import com.projectsknowledge.general.integration.codex.schema.response.DtoCodexReasoningEffort;
import com.projectsknowledge.general.integration.codex.schema.response.DtoCodexSettings;
import com.projectsknowledge.general.integration.codex.schema.response.DtoCodexStatus;
import com.projectsknowledge.general.integration.codex.schema.response.DtoDatabaseKnowledgeResult;
import com.projectsknowledge.general.integration.codex.schema.response.DtoWorkflowKnowledgeResult;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Small JSON-RPC client for the local Codex app-server. Every knowledge request runs in an
 * ephemeral, read-only thread and must conform to the structured output schema below.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CodexAppServerClient implements CacheClearable {

    private static final String MODEL_CACHE_NAMESPACE = "codex-model-catalog-v1";
    private static final String MODEL_CACHE_KEY = "available-models";
    private final ObjectMapper mapper;
    private final ProjectsKnowledgeProperties properties;
    private final CodexRuntimeSettings runtimeSettings;
    private final CodexAppServerTransport transport;
    private final Clock clock;
    private final PersistentKnowledgeCache persistentCache;
    private final Object modelCacheMonitor = new Object();
    private volatile CodexModelCatalogSnapshot modelCache;

    @Override
    public void clearCache() {
        synchronized (modelCacheMonitor) {
            modelCache = null;
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
        var config = properties.getCodex();
        var selected = runtimeSettings.current();
        if (!config.isEnabled()) {
            return DtoCodexSettings.unavailable(
                DtoCodexStatus.disabled(selected.model(), selected.reasoningEffort()),
                selected.model()
            );
        }
        try {
            CodexAppServerConnection connection = transport.connection();
            return readSettings(connection, selected);
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

    public List<CodexThread> listThreads() {
        RequestCancellation.check();
        JsonNode result = transport
            .connection()
            .request(
                "thread/list",
                Map.of("limit", 100, "archived", false, "sortKey", "updated_at", "sortDirection", "desc"),
                setupTimeout()
            );
        List<CodexThread> threads = new ArrayList<>();
        RequestCancellation.check();
        for (JsonNode thread : result.path("data")) {
            String cwd = thread.path("cwd").asText("");
            if (!cwd.isBlank()) threads.add(new CodexThread(Path.of(cwd), thread.path("updatedAt").asLong(0)));
        }
        return threads;
    }

    public DtoCodexKnowledgeResult ask(List<Path> workspaceRoots, String question, String language, SearchMode mode) {
        String languageInstruction = "ar".equalsIgnoreCase(language)
            ? "أجب باللغة العربية، مع إبقاء أسماء الكلاسات والدوال والصلاحيات كما تظهر في الكود."
            : "Answer in English.";
        String answer = runTurn(
            workspaceRoots,
            languageInstruction + "\n\nQuestion: " + question,
            CodexPromptFactory.instructions(mode),
            CodexSchemaFactory.answer(mapper, mode)
        );
        try {
            return parseAnswer(answer, mode);
        } catch (IOException exception) {
            throw new KnowledgeException(
                HttpStatus.SERVICE_UNAVAILABLE,
                ApiErrorCode.CODEX_INVALID_RESPONSE,
                "Codex returned an invalid structured answer.",
                true
            );
        }
    }

    public DtoCodexProjectOverview overview(List<Path> workspaceRoots) {
        String answer = runTurn(
            workspaceRoots,
            "Build the selected project's overview and discover its actual external integrations.",
            CodexPromptFactory.overviewInstructions(),
            CodexSchemaFactory.overview(mapper)
        );
        try {
            return parseOverview(answer);
        } catch (IOException exception) {
            throw new KnowledgeException(
                HttpStatus.SERVICE_UNAVAILABLE,
                ApiErrorCode.CODEX_INVALID_RESPONSE,
                "Codex returned an invalid project overview.",
                true
            );
        }
    }

    // Questions and overview discovery use the same read-only transport, without additional model turns.
    private String runTurn(List<Path> workspaceRoots, String prompt, String developerInstructions, ObjectNode schema) {
        RequestCancellation.check();
        if (workspaceRoots.isEmpty()) throw new KnowledgeException(
            HttpStatus.BAD_REQUEST,
            "The selected Codex project has no readable workspace."
        );
        long started = System.nanoTime();
        CodexAppServerConnection connection = transport.connection();
        String threadId = null;
        long turnStarted = 0;
        boolean completed = false;
        try {
            var selectedRuntime = runtimeSettings.current();
            List<String> roots = workspaceRoots
                .stream()
                .map(path -> path.toAbsolutePath().normalize().toString())
                .toList();
            Map<String, Object> startParams = new LinkedHashMap<>();
            startParams.put("cwd", roots.getFirst());
            startParams.put("runtimeWorkspaceRoots", roots);
            startParams.put("sandbox", "read-only");
            startParams.put("approvalPolicy", "never");
            startParams.put("ephemeral", true);
            startParams.put("developerInstructions", developerInstructions);
            if (!selectedRuntime.model().isBlank()) {
                startParams.put("model", selectedRuntime.model());
            }
            RequestCancellation.check();
            JsonNode threadResult = connection.request("thread/start", startParams, setupTimeout());
            threadId = threadResult.path("thread").path("id").asText();
            if (threadId.isBlank()) {
                connection.close();
                throw new KnowledgeException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    ApiErrorCode.CODEX_INVALID_RESPONSE,
                    "Codex did not create a thread.",
                    true
                );
            }

            Map<String, Object> turnParams = new LinkedHashMap<>();
            turnParams.put("threadId", threadId);
            turnParams.put("input", List.of(Map.of("type", "text", "text", prompt)));
            turnParams.put("effort", selectedRuntime.reasoningEffort());
            turnParams.put("summary", "concise");
            turnParams.put("outputSchema", schema);
            turnStarted = System.nanoTime();
            String answer = connection.runTurn(
                threadId,
                turnParams,
                Duration.ofSeconds(Math.max(1, properties.getCodex().getTimeoutSeconds()))
            );
            RequestCancellation.check();
            completed = true;
            return answer;
        } finally {
            long finished = System.nanoTime();
            // Timing only: never log questions, answers, repository paths or protocol payloads.
            log.info(
                "Codex analysis: completed={}, setupMs={}, turnMs={}",
                completed,
                Duration.ofNanos((turnStarted == 0 ? finished : turnStarted) - started).toMillis(),
                turnStarted == 0 ? 0 : Duration.ofNanos(finished - turnStarted).toMillis()
            );
            if (threadId != null && !threadId.isBlank()) connection.unsubscribe(threadId);
        }
    }

    private Duration setupTimeout() {
        return Duration.ofSeconds(Math.max(1, Math.min(30, properties.getCodex().getTimeoutSeconds())));
    }

    private DtoCodexSettings readSettings(
        CodexAppServerConnection connection,
        CodexRuntimeSettings.Selection selected
    ) {
        return buildSettings(connection, readAccount(connection), models(connection), selected);
    }

    private JsonNode readAccount(CodexAppServerConnection connection) {
        return connection.request("account/read", Map.of("refreshToken", false), setupTimeout());
    }

    private JsonNode readModels(CodexAppServerConnection connection) {
        return connection.request(
            "model/list",
            Map.of("limit", 100, "includeHidden", false),
            setupTimeout()
        );
    }

    /** Model metadata changes rarely, so one backend snapshot is shared by every browser session. */
    private List<DtoCodexModel> models(CodexAppServerConnection connection) {
        int ttlSeconds = Math.max(0, properties.getCodex().getModelCacheSeconds());
        if (ttlSeconds == 0) return loadModels(connection);
        Instant now = clock.instant();
        CodexModelCatalogSnapshot observed = modelCache;
        if (isFresh(observed, now, ttlSeconds)) return observed.models();
        synchronized (modelCacheMonitor) {
            now = clock.instant();
            if (isFresh(modelCache, now, ttlSeconds)) return modelCache.models();
            CodexModelCatalogSnapshot persisted = persistentCache
                .find(MODEL_CACHE_NAMESPACE, MODEL_CACHE_KEY, CodexModelCatalogSnapshot.class, now)
                .orElse(null);
            if (isFresh(persisted, now, ttlSeconds)) {
                modelCache = persisted;
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
            modelCache = snapshot;
            return snapshot.models();
        }
    }

    private List<DtoCodexModel> loadModels(CodexAppServerConnection connection) {
        return parseModels(readModels(connection));
    }

    private boolean isFresh(CodexModelCatalogSnapshot snapshot, Instant now, int ttlSeconds) {
        return snapshot != null &&
            snapshot.cachedAt() != null &&
            !snapshot.cachedAt().isAfter(now) &&
            now.isBefore(snapshot.cachedAt().plusSeconds(ttlSeconds));
    }

    private DtoCodexSettings buildSettings(
        CodexAppServerConnection connection,
        JsonNode account,
        List<DtoCodexModel> models,
        CodexRuntimeSettings.Selection selected
    ) {
        DtoCodexModel effective = findModel(models, selected.model());
        String effectiveModel = effective == null ? selected.model() : effective.id();
        DtoCodexStatus status = buildStatus(
            connection,
            account,
            effectiveModel,
            selected.reasoningEffort()
        );
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

    private List<DtoCodexModel> parseModels(JsonNode response) {
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

    private DtoCodexModel findModel(List<DtoCodexModel> models, String selectedModel) {
        if (selectedModel == null || selectedModel.isBlank()) {
            return models.stream().filter(DtoCodexModel::defaultModel).findFirst().orElse(null);
        }
        return models.stream().filter(model -> model.id().equals(selectedModel.strip())).findFirst().orElse(null);
    }

    DtoCodexProjectOverview parseOverview(String answer) throws IOException {
        JsonNode json = mapper.readTree(answer);
        if (json == null || !json.isObject()) throw new IOException("Missing overview.");
        for (String field : List.of(
            "frontend",
            "backend",
            "databases",
            "domains",
            "messaging",
            "scheduledJobs",
            "integrations"
        )) {
            JsonNode items = json.path(field);
            if (!items.isArray() || items.size() > 30) throw new IOException("Invalid overview section.");
            for (JsonNode item : items) {
                if (field.equals("integrations")) {
                    for (String key : List.of("name", "repositoryName", "filePath")) {
                        if (!item.path(key).isTextual() || item.path(key).asText().isBlank()) throw new IOException(
                            "Missing integration evidence."
                        );
                    }
                } else if (!item.isTextual() || item.asText().length() > 120) throw new IOException(
                    "Invalid overview value."
                );
            }
        }
        return mapper.treeToValue(json, DtoCodexProjectOverview.class);
    }

    DtoCodexKnowledgeResult parseAnswer(String answer, SearchMode mode) throws IOException {
        JsonNode json = mapper.readTree(answer);
        // Fail closed: old/malformed responses must not bypass the scope decision.
        if (json == null || !json.path("inScope").isBoolean()) throw new IOException(
            "Missing or invalid project scope decision."
        );
        return switch (mode) {
            case BASIC -> mapper.treeToValue(json, DtoBasicKnowledgeResult.class).toKnowledgeResult();
            case WORKFLOW -> mapper.treeToValue(json, DtoWorkflowKnowledgeResult.class).toKnowledgeResult();
            case DATABASE -> mapper.treeToValue(json, DtoDatabaseKnowledgeResult.class).toKnowledgeResult();
            case ADVANCED -> mapper.treeToValue(json, DtoCodexKnowledgeResult.class);
        };
    }

    public record CodexThread(Path cwd, long updatedAt) {}
}
