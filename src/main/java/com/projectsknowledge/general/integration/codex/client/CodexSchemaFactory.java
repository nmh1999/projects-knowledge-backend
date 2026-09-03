package com.projectsknowledge.general.integration.codex.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.projectsknowledge.business.knowledge.enums.SearchMode;
import java.util.List;
import java.util.Map;

/** Builds the bounded JSON schemas required for structured Codex responses. */
final class CodexSchemaFactory {

    private final ObjectMapper mapper;

    private CodexSchemaFactory(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    static ObjectNode overview(ObjectMapper mapper) {
        return new CodexSchemaFactory(mapper).overviewSchema();
    }

    static ObjectNode answer(ObjectMapper mapper, SearchMode mode) {
        return new CodexSchemaFactory(mapper).answerSchema(mode);
    }

    private ObjectNode overviewSchema() {
        ObjectNode schema = objectSchema();
        for (String name : List.of("frontend", "backend", "databases", "domains", "messaging", "scheduledJobs")) {
            add(schema, name, arraySchema(stringSchema().put("maxLength", 120), 30));
        }
        add(
            schema,
            "integrations",
            arraySchema(
                objectOf(
                    Map.of(
                        "name",
                        stringSchema().put("maxLength", 120),
                        "repositoryName",
                        stringSchema(),
                        "filePath",
                        stringSchema()
                    )
                ),
                30
            )
        );
        return schema;
    }

    private ObjectNode answerSchema(SearchMode mode) {
        return switch (mode) {
            case BASIC -> basicSchema();
            case ADVANCED -> knowledgeSchema();
            case WORKFLOW -> workflowSchema();
            case DATABASE -> databaseSchema();
        };
    }

    private ObjectNode basicSchema() {
        ObjectNode schema = objectSchema();
        addCommonFields(schema);
        return schema;
    }

    private ObjectNode databaseSchema() {
        ObjectNode schema = objectSchema();
        JsonNode fields = knowledgeSchema().path("properties");
        copyFields(schema, fields, "inScope", "answer", "confidence", "keyFindings", "risks");
        ObjectNode table = fields.path("database").path("items").deepCopy();
        add(table, "columns", arraySchema(stringSchema().put("maxLength", 240), 8));
        add(table, "relationships", arraySchema(stringSchema().put("maxLength", 320), 6));
        add(schema, "database", arraySchema(table, 6));
        ObjectNode sources = fields.path("sources").deepCopy();
        sources.put("maxItems", 6);
        add(schema, "sources", sources);
        return schema;
    }

    private ObjectNode workflowSchema() {
        ObjectNode schema = objectSchema();
        JsonNode fields = knowledgeSchema().path("properties");
        copyFields(schema, fields, "inScope", "answer", "confidence", "roles", "businessFlow", "risks");
        ObjectNode sources = fields.path("sources").deepCopy();
        sources.put("maxItems", 4);
        add(schema, "sources", sources);
        add(schema, "workflowExample", stringSchema());
        add(schema, "workflowDiagram", workflowDiagramSchema());
        return schema;
    }

    private ObjectNode workflowDiagramSchema() {
        ObjectNode nodeType = stringSchema();
        nodeType.putArray("enum").add("start").add("action").add("decision").add("end");
        return objectOf(
            Map.of(
                "nodes",
                arraySchema(
                    objectOf(
                        Map.of(
                            "id",
                            stringSchema(),
                            "title",
                            stringSchema().put("maxLength", 100),
                            "actor",
                            stringSchema(),
                            "type",
                            nodeType
                        )
                    ),
                    10
                ),
                "edges",
                arraySchema(
                    objectOf(
                        Map.of(
                            "from",
                            stringSchema(),
                            "to",
                            stringSchema(),
                            "label",
                            stringSchema().put("maxLength", 40)
                        )
                    ),
                    16
                )
            )
        );
    }

    private ObjectNode knowledgeSchema() {
        ObjectNode schema = objectSchema();
        addCommonFields(schema);
        add(schema, "keyFindings", arraySchema(stringSchema(), 5));
        add(schema, "businessFlow", arraySchema(stringSchema(), 7));
        add(
            schema,
            "technicalFlow",
            arraySchema(objectOf(Map.of("type", stringSchema(), "name", stringSchema(), "detail", stringSchema())), 8)
        );
        add(
            schema,
            "apis",
            arraySchema(
                objectOf(
                    Map.of(
                        "method",
                        stringSchema(),
                        "path",
                        stringSchema(),
                        "controller",
                        stringSchema(),
                        "methodName",
                        stringSchema(),
                        "purpose",
                        stringSchema()
                    )
                ),
                8
            )
        );
        add(
            schema,
            "database",
            arraySchema(
                objectOf(
                    Map.of(
                        "table",
                        stringSchema(),
                        "entity",
                        stringSchema(),
                        "repository",
                        stringSchema(),
                        "purpose",
                        stringSchema()
                    )
                ),
                8
            )
        );
        add(
            schema,
            "integrations",
            arraySchema(
                objectOf(Map.of("name", stringSchema(), "usedBy", stringSchema(), "purpose", stringSchema())),
                6
            )
        );
        add(
            schema,
            "scheduledJobs",
            arraySchema(
                objectOf(Map.of("name", stringSchema(), "purpose", stringSchema(), "schedule", stringSchema())),
                6
            )
        );
        add(
            schema,
            "technicalDetails",
            arraySchema(
                objectOf(
                    Map.of(
                        "name",
                        stringSchema(),
                        "type",
                        stringSchema(),
                        "method",
                        stringSchema(),
                        "responsibility",
                        stringSchema()
                    )
                ),
                8
            )
        );
        add(
            schema,
            "roles",
            arraySchema(
                objectOf(Map.of("role", stringSchema(), "capability", stringSchema(), "evidence", stringSchema())),
                8
            )
        );
        add(schema, "risks", arraySchema(stringSchema(), 5));
        add(schema, "followUpQuestions", arraySchema(stringSchema(), 3));
        add(
            schema,
            "sources",
            arraySchema(
                objectOf(
                    Map.of(
                        "repositoryName",
                        stringSchema(),
                        "filePath",
                        stringSchema(),
                        "symbol",
                        stringSchema(),
                        "startLine",
                        integerSchema(),
                        "endLine",
                        integerSchema()
                    )
                ),
                8
            )
        );
        return schema;
    }

    private void addCommonFields(ObjectNode schema) {
        add(schema, "inScope", mapper.createObjectNode().put("type", "boolean"));
        add(schema, "answer", stringSchema());
        ObjectNode confidence = stringSchema();
        confidence.putArray("enum").add("high").add("medium").add("low");
        add(schema, "confidence", confidence);
    }

    private void copyFields(ObjectNode target, JsonNode source, String... names) {
        for (String name : names) add(target, name, source.get(name).deepCopy());
    }

    private ObjectNode objectSchema() {
        ObjectNode node = mapper.createObjectNode();
        node.put("type", "object");
        node.set("properties", mapper.createObjectNode());
        node.putArray("required");
        node.put("additionalProperties", false);
        return node;
    }

    private ObjectNode objectOf(Map<String, ObjectNode> properties) {
        ObjectNode node = objectSchema();
        properties.forEach((name, value) -> add(node, name, value));
        return node;
    }

    private void add(ObjectNode object, String name, JsonNode schema) {
        ((ObjectNode) object.path("properties")).set(name, schema);
        ((ArrayNode) object.path("required")).add(name);
    }

    private ObjectNode stringSchema() {
        return mapper.createObjectNode().put("type", "string");
    }

    private ObjectNode integerSchema() {
        return mapper.createObjectNode().put("type", "integer").put("minimum", 1);
    }

    private ObjectNode arraySchema(JsonNode items, int maxItems) {
        ObjectNode node = mapper.createObjectNode();
        node.put("type", "array");
        node.set("items", items);
        node.put("maxItems", maxItems);
        return node;
    }
}
