package com.projectsknowledge.general.integration.codex.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.projectsknowledge.business.knowledge.enums.SearchMode;
import com.projectsknowledge.general.cancellation.RequestCancellation;
import com.projectsknowledge.general.config.ProjectsKnowledgeProperties;
import com.projectsknowledge.general.exception.ApiErrorCode;
import com.projectsknowledge.general.exception.KnowledgeException;
import com.projectsknowledge.general.integration.codex.schema.response.DtoCodexKnowledgeResult;
import com.projectsknowledge.general.integration.codex.schema.response.DtoCodexProjectOverview;
import com.projectsknowledge.general.integration.codex.service.CodexModelService;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
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
public class CodexAppServerClient {

    private final ObjectMapper mapper;
    private final ProjectsKnowledgeProperties properties;
    private final CodexAppServerTransport transport;
    private final CodexResponseParser responseParser;
    private final CodexModelService modelService;

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
            return responseParser.parseAnswer(answer, mode);
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
            return responseParser.parseOverview(answer);
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
            var selectedRuntime = modelService.selectionForRequest();
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

    public record CodexThread(Path cwd, long updatedAt) {}
}
