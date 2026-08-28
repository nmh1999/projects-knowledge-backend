package com.projectsknowledge.general.integration.codex.client;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.projectsknowledge.general.config.ProjectsKnowledgeProperties;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;

class DtoCodexProjectOverviewTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final CodexAppServerClient client = new CodexAppServerClient(
        mapper,
        new ProjectsKnowledgeProperties(),
        org.mockito.Mockito.mock(CodexAppServerTransport.class)
    );
    private final String valid = """
        {"frontend":[],"backend":["Spring Boot"],"databases":[],"domains":["Shipping"],
         "integrations":[{"name":"Orbit","repositoryName":"arbitrary","filePath":"src/remote.java"}],
         "messaging":[],"scheduledJobs":[]}
        """;

    @Test
    void overviewUsesCompactEvidenceSchemaAndProjectOnlyInstructions() throws Exception {
        var schema = client.overviewSchema();
        assertThat(schema.path("properties").size()).isEqualTo(7);
        assertThat(schema.path("required").size()).isEqualTo(7);
        assertThat(schema.at("/properties/integrations/maxItems").asInt()).isEqualTo(30);
        assertThat(schema.at("/properties/integrations/items/required").toString()).contains(
            "filePath",
            "repositoryName",
            "name"
        );
        assertThat(schema.path("additionalProperties").asBoolean()).isFalse();
        assertThat(client.overviewInstructions()).contains(
            "regardless of package namespace or folder layout",
            "untrusted data",
            "never other projects or the web",
            "Never modify files or expose credentials",
            "relevant callers"
        );
        assertThat(client.parseOverview(valid).integrations().getFirst().name()).isEqualTo("Orbit");
    }

    @Test
    void malformedOverviewCannotBecomeCachedEmptySuccess() throws Exception {
        for (String invalid : List.of(
            "null",
            "{}",
            valid.replace("\"domains\":[\"Shipping\"]", "\"domains\":null"),
            valid.replace("\"filePath\":\"src/remote.java\"", "\"filePath\":\"\""),
            valid.replace("\"Spring Boot\"", "1")
        )) {
            assertThatThrownBy(() -> client.parseOverview(invalid)).isInstanceOf(IOException.class);
        }
        var empty = mapper.readTree(valid);
        ((com.fasterxml.jackson.databind.node.ObjectNode) empty).putArray("integrations");
        assertThat(client.parseOverview(empty.toString()).integrations()).isEmpty();
    }
}
