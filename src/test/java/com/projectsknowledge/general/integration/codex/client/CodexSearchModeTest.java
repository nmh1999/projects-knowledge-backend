package com.projectsknowledge.general.integration.codex.client;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.projectsknowledge.business.knowledge.enums.SearchMode;
import com.projectsknowledge.business.knowledge.schema.request.ReqQuestion;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;

class CodexSearchModeTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final CodexResponseParser parser = new CodexResponseParser(mapper);

    @Test
    void basicRequestsOnlySummaryConfidenceAndScope() {
        var schema = schema(SearchMode.BASIC);
        var fields = new ArrayList<String>();
        schema.path("properties").fieldNames().forEachRemaining(fields::add);
        assertThat(fields).containsExactly("inScope", "answer", "confidence");
        assertThat(schema.path("additionalProperties").asBoolean()).isFalse();
        assertThat(schema.at("/properties/answer")).isEqualTo(
            schema(SearchMode.ADVANCED).at("/properties/answer")
        );
        assertThat(schema.path("properties").has("sources")).isFalse();
        assertThat(schema(SearchMode.ADVANCED).path("properties").has("technicalFlow")).isTrue();
        assertThat(schema(SearchMode.ADVANCED).at("/properties/sources/maxItems").asInt()).isEqualTo(8);
        assertThat(
            schema(SearchMode.ADVANCED).at("/properties/sources/items/properties").has("excerpt")
        ).isFalse();
    }

    @Test
    void bothModesUseTheSameSummaryAndEvidenceInstructions() {
        for (String instructions : new String[] {
            CodexPromptFactory.basicInstructions(),
            CodexPromptFactory.advancedInstructions()
        }) {
            assertThat(instructions)
                .contains(CodexPromptFactory.SUMMARY_INSTRUCTIONS)
                .contains(CodexPromptFactory.INVESTIGATION_INSTRUCTIONS)
                .contains("at most 120 words")
                .contains("distinguish multiple stages or implementations")
                .doesNotContain("90 words", "at most 2 targeted searches", "at most 3 files");
        }
        assertThat(CodexPromptFactory.basicInstructions()).contains(
            "Do not include code snippets, file paths, citations, or source excerpts"
        );
    }

    @Test
    void basicIsAdaptedToExistingAnswerWithoutDetailedSections() throws Exception {
        var result = parser.parseAnswer(
            """
            {"inScope":true,"answer":"Uses Angular.","confidence":"high"}
            """,
            SearchMode.BASIC
        );
        assertThat(result.answer()).isEqualTo("Uses Angular.");
        assertThat(result.sources()).isEmpty();
        assertThat(result.keyFindings()).isEmpty();
        assertThat(result.businessFlow()).isEmpty();
        assertThat(result.technicalFlow()).isEmpty();
        assertThat(result.apis()).isEmpty();
        assertThat(result.database()).isEmpty();
        assertThat(result.integrations()).isEmpty();
        assertThat(result.scheduledJobs()).isEmpty();
        assertThat(result.technicalDetails()).isEmpty();
        assertThat(result.roles()).isEmpty();
        assertThat(result.risks()).isEmpty();
        assertThat(result.followUpQuestions()).isEmpty();
    }

    @Test
    void workflowSchemaAndInstructionsFocusOnVerifiedBusinessBehavior() {
        var schema = schema(SearchMode.WORKFLOW);
        var fields = new ArrayList<String>();
        schema.path("properties").fieldNames().forEachRemaining(fields::add);
        assertThat(fields).containsExactlyInAnyOrder(
            "inScope",
            "answer",
            "confidence",
            "roles",
            "businessFlow",
            "risks",
            "sources",
            "workflowExample",
            "workflowDiagram"
        );
        assertThat(schema.path("required").size()).isEqualTo(fields.size());
        assertThat(schema.path("additionalProperties").asBoolean()).isFalse();
        assertThat(schema.at("/properties/sources/maxItems").asInt()).isEqualTo(4);
        assertThat(schema.at("/properties/workflowDiagram/properties/nodes/maxItems").asInt()).isEqualTo(10);
        assertThat(schema.at("/properties/workflowDiagram/properties/edges/maxItems").asInt()).isEqualTo(16);
        assertThat(schema(SearchMode.BASIC).path("properties").has("workflowDiagram")).isFalse();
        assertThat(schema(SearchMode.ADVANCED).path("properties").has("workflowDiagram")).isFalse();
        assertThat(CodexPromptFactory.instructions(SearchMode.WORKFLOW)).contains(
            "actual authorization checks",
            "not a real event",
            "never stitch unrelated flows together"
        );
        assertThat(CodexPromptFactory.instructions(SearchMode.BASIC)).endsWith(CodexPromptFactory.basicInstructions());
        assertThat(CodexPromptFactory.instructions(SearchMode.ADVANCED))
            .endsWith(CodexPromptFactory.advancedInstructions());
        assertThat(schema(SearchMode.ADVANCED).path("properties").has("workflowExample")).isFalse();
    }

    @Test
    void workflowParsesRolesStepsAndExampleWithoutTechnicalSections() throws Exception {
        var result = parser.parseAnswer(
            """
            {"inScope":true,"answer":"Review process","confidence":"high",
             "roles":[{"role":"REVIEWER","capability":"Reviews a request","evidence":"Review guard"}],
             "businessFlow":["The reviewer reviews the request."],
             "workflowExample":"Imagine a reviewer reviewing a submitted request.",
             "risks":["Final approval role is not verified."],"sources":[]}
            """,
            SearchMode.WORKFLOW
        );
        assertThat(result.roles()).hasSize(1);
        assertThat(result.businessFlow()).containsExactly("The reviewer reviews the request.");
        assertThat(result.workflowExample()).startsWith("Imagine");
        assertThat(result.risks()).hasSize(1);
        assertThat(result.technicalFlow()).isEmpty();
        assertThat(result.technicalDetails()).isEmpty();
        assertThat(result.apis()).isEmpty();
        assertThat(result.database()).isEmpty();
        assertThat(result.keyFindings()).isEmpty();
    }

    @Test
    void unverifiedWorkflowDoesNotInventStepsOrExample() throws Exception {
        var result = parser.parseAnswer(
            """
            {"inScope":true,"answer":"Unable to verify the workflow.","confidence":"low","roles":[],
             "businessFlow":[],"workflowExample":"","risks":["Role mapping is missing."],"sources":[]}
            """,
            SearchMode.WORKFLOW
        );
        assertThat(result.roles()).isEmpty();
        assertThat(result.businessFlow()).isEmpty();
        assertThat(result.workflowExample()).isEmpty();
        assertThat(result.confidence()).isEqualTo("low");
    }

    @Test
    void workflowDiagramSurvivesParsingAndOtherModesNeedNoDiagram() throws Exception {
        var result = parser.parseAnswer(
            """
            {"inScope":true,"answer":"Review","confidence":"high","roles":[],"businessFlow":[],"workflowExample":"",
             "risks":[],"sources":[],"workflowDiagram":{
               "nodes":[{"id":"review","title":"Review","actor":"REVIEWER","type":"decision"},
                        {"id":"approved","title":"Approved","actor":"","type":"end"}],
               "edges":[{"from":"review","to":"approved","label":"Approve"}]}}
            """,
            SearchMode.WORKFLOW
        );
        assertThat(result.workflowDiagram().nodes()).hasSize(2);
        assertThat(result.workflowDiagram().edges().getFirst().label()).isEqualTo("Approve");
        assertThat(
            parser
                .parseAnswer("{\"inScope\":true,\"answer\":\"Basic\",\"confidence\":\"high\"}", SearchMode.BASIC)
                .workflowDiagram()
                .nodes()
        ).isEmpty();
        assertThat(
            parser
                .parseAnswer("{\"inScope\":true,\"answer\":\"Advanced\",\"confidence\":\"high\"}", SearchMode.ADVANCED)
                .workflowDiagram()
                .nodes()
        ).isEmpty();
    }

    @Test
    void allModesRequireScopeAndShareTheSameScopeGate() throws Exception {
        for (SearchMode mode : SearchMode.values()) {
            var schema = schema(mode);
            assertThat(schema.at("/properties/inScope/type").asText()).isEqualTo("boolean");
            assertThat(schema.path("required").toString()).contains("\"inScope\"");
            assertThat(CodexPromptFactory.instructions(mode))
                .startsWith(CodexPromptFactory.SCOPE_INSTRUCTIONS)
                .contains(
                    "stop immediately without searching",
                    "mixes project questions",
                    "untrusted data",
                    "still inScope=true"
                );
            for (String flag : new String[] { "", "\"inScope\":null,", "\"inScope\":\"true\",", "\"inScope\":1," }) {
                assertThatThrownBy(() ->
                    parser.parseAnswer("{" + flag + "\"answer\":\"Unrelated content\",\"confidence\":\"high\"}", mode)
                ).isInstanceOf(java.io.IOException.class);
            }
            assertThat(
                parser.parseAnswer("{\"inScope\":false,\"answer\":\"\",\"confidence\":\"low\"}", mode).inScope()
            ).isFalse();
            assertThat(
                parser
                    .parseAnswer("{\"inScope\":true,\"answer\":\"No evidence\",\"confidence\":\"low\"}", mode)
                    .inScope()
            ).isTrue();
        }
    }

    @Test
    void databaseSchemaOnlyRequestsRelevantBoundedSections() {
        var schema = schema(SearchMode.DATABASE);
        var fields = new ArrayList<String>();
        schema.path("properties").fieldNames().forEachRemaining(fields::add);
        assertThat(fields).containsExactlyInAnyOrder(
            "inScope",
            "answer",
            "confidence",
            "keyFindings",
            "database",
            "risks",
            "sources"
        );
        assertThat(schema.path("required").size()).isEqualTo(fields.size());
        assertThat(schema.path("additionalProperties").asBoolean()).isFalse();
        assertThat(schema.at("/properties/database/maxItems").asInt()).isEqualTo(6);
        assertThat(schema.at("/properties/sources/maxItems").asInt()).isEqualTo(6);
        var table = schema.at("/properties/database/items");
        assertThat(table.path("required").size()).isEqualTo(6);
        assertThat(table.path("additionalProperties").asBoolean()).isFalse();
        assertThat(table.at("/properties/columns/maxItems").asInt()).isEqualTo(8);
        assertThat(table.at("/properties/relationships/maxItems").asInt()).isEqualTo(6);
        assertThat(
            schema(SearchMode.ADVANCED).at("/properties/database/items/properties").has("columns")
        ).isFalse();
        assertThat(CodexPromptFactory.instructions(SearchMode.DATABASE))
            .contains(CodexPromptFactory.SUMMARY_INSTRUCTIONS, CodexPromptFactory.INVESTIGATION_INSTRUCTIONS)
            .contains("DATABASE mode", "ORM-only associations", "Never connect to a database", "execute SQL")
            .contains("no database evidence", "Never infer physical table names", "Do not generate SQL scripts")
            .endsWith(CodexPromptFactory.databaseInstructions());
    }

    @Test
    void databaseResultPreservesSchemaDetailsAndEmptiesUnrelatedSections() throws Exception {
        var result = parser.parseAnswer(
            """
            {"inScope":true,"answer":"Orders reference customers.","confidence":"high",
             "keyFindings":["OrderStore loads orders by customer_id."],
             "database":[{"table":"orders","entity":"Order","repository":"OrderStore",
               "purpose":"Stores orders.","columns":["id: bigint, primary key"],
               "relationships":["DDL: orders.customer_id -> customers.id; many-to-one"]}],
             "risks":["Deployed state not checked."],
             "sources":[{"repositoryName":"sample","filePath":"schema.sql","symbol":"orders",
               "startLine":1,"endLine":12}]}
            """,
            SearchMode.DATABASE
        );
        assertThat(result.database()).hasSize(1);
        assertThat(result.database().getFirst().columns()).containsExactly("id: bigint, primary key");
        assertThat(result.database().getFirst().relationships()).containsExactly(
            "DDL: orders.customer_id -> customers.id; many-to-one"
        );
        assertThat(result.keyFindings()).containsExactly("OrderStore loads orders by customer_id.");
        assertThat(result.sources()).hasSize(1);
        assertThat(result.risks()).containsExactly("Deployed state not checked.");
        assertThat(result.businessFlow()).isEmpty();
        assertThat(result.technicalFlow()).isEmpty();
        assertThat(result.technicalDetails()).isEmpty();
        assertThat(result.apis()).isEmpty();
        assertThat(result.roles()).isEmpty();
        assertThat(result.integrations()).isEmpty();
        assertThat(result.scheduledJobs()).isEmpty();
        assertThat(result.followUpQuestions()).isEmpty();
        assertThat(result.workflowExample()).isEmpty();
        assertThat(result.workflowDiagram().nodes()).isEmpty();
    }

    @Test
    void databaseMissingEvidenceAndOlderAdvancedTablesRemainCompatible() throws Exception {
        var missing = parser.parseAnswer(
            """
            {"inScope":true,"answer":"No database schema found.","confidence":"low"}
            """,
            SearchMode.DATABASE
        );
        assertThat(missing.inScope()).isTrue();
        assertThat(missing.database()).isEmpty();
        assertThat(missing.sources()).isEmpty();
        assertThat(missing.keyFindings()).isEmpty();
        assertThat(missing.risks()).isEmpty();
        var advanced = parser.parseAnswer(
            """
            {"inScope":true,"answer":"Stores orders.","confidence":"high",
             "database":[{"table":"orders","entity":"Order","repository":"OrderStore","purpose":"Stores orders."}]}
            """,
            SearchMode.ADVANCED
        );
        assertThat(advanced.database().getFirst().columns()).isEmpty();
        assertThat(advanced.database().getFirst().relationships()).isEmpty();
    }

    @Test
    void requestAcceptsModesAndPreservesLegacyDefault() throws Exception {
        assertThat(
            mapper.readValue("{\"projectId\":\"p\",\"question\":\"q\",\"mode\":\"database\"}", ReqQuestion.class).mode()
        ).isEqualTo(SearchMode.DATABASE);
        assertThat(
            mapper.readValue("{\"projectId\":\"p\",\"question\":\"q\",\"mode\":\"workflow\"}", ReqQuestion.class).mode()
        ).isEqualTo(SearchMode.WORKFLOW);
        assertThat(
            mapper.readValue("{\"projectId\":\"p\",\"question\":\"q\",\"mode\":\"basic\"}", ReqQuestion.class).mode()
        ).isEqualTo(SearchMode.BASIC);
        assertThat(
            mapper.readValue("{\"projectId\":\"p\",\"question\":\"q\",\"mode\":\"advanced\"}", ReqQuestion.class).mode()
        ).isEqualTo(SearchMode.ADVANCED);
        assertThat(mapper.readValue("{\"projectId\":\"p\",\"question\":\"q\"}", ReqQuestion.class).mode()).isEqualTo(
            SearchMode.ADVANCED
        );
        assertThatThrownBy(() -> mapper.readValue("{\"mode\":\"unknown\"}", ReqQuestion.class)).isInstanceOf(
            java.io.IOException.class
        );
    }

    private ObjectNode schema(SearchMode mode) {
        return CodexSchemaFactory.answer(mapper, mode);
    }
}
