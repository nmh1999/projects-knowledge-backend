package com.projectsknowledge.general.integration.codex.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.projectsknowledge.business.knowledge.enums.SearchMode;
import com.projectsknowledge.general.integration.codex.schema.response.DtoBasicKnowledgeResult;
import com.projectsknowledge.general.integration.codex.schema.response.DtoCodexKnowledgeResult;
import com.projectsknowledge.general.integration.codex.schema.response.DtoCodexProjectOverview;
import com.projectsknowledge.general.integration.codex.schema.response.DtoDatabaseKnowledgeResult;
import com.projectsknowledge.general.integration.codex.schema.response.DtoWorkflowKnowledgeResult;
import java.io.IOException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Validates and maps structured Codex output without performing transport operations. */
@Component
@RequiredArgsConstructor
public class CodexResponseParser {

    private final ObjectMapper mapper;

    public DtoCodexProjectOverview parseOverview(String answer) throws IOException {
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

    public DtoCodexKnowledgeResult parseAnswer(String answer, SearchMode mode) throws IOException {
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
}
