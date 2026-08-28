package com.projectsknowledge.business.knowledge.schema.response;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class DtoWorkflowDiagramTest {

    private final DtoWorkflowDiagram.Node review = new DtoWorkflowDiagram.Node(
        "review",
        "Review request",
        "REVIEWER",
        "decision"
    );
    private final DtoWorkflowDiagram.Node approved = new DtoWorkflowDiagram.Node("approved", "Approved", "", "end");

    @Test
    void preservesVerifiedBranchesAndReturnLoops() {
        var edges = List.of(
            new DtoWorkflowDiagram.Edge("review", "approved", "Approve"),
            new DtoWorkflowDiagram.Edge("approved", "review", "Reopen")
        );
        var graph = new DtoWorkflowDiagram(List.of(review, approved), edges);
        assertThat(graph.nodes()).hasSize(2);
        assertThat(graph.edges()).containsExactlyElementsOf(edges);
    }

    @Test
    void rejectsTheWholeGraphWhenAnEdgeReferencesAMissingNode() {
        var graph = new DtoWorkflowDiagram(
            List.of(review, approved),
            List.of(new DtoWorkflowDiagram.Edge("review", "missing", "Approve"))
        );
        assertThat(graph.nodes()).isEmpty();
        assertThat(graph.edges()).isEmpty();
    }

    @Test
    void rejectsDuplicateIdsAndNullNodes() {
        assertThat(new DtoWorkflowDiagram(List.of(review, review), List.of()).nodes()).isEmpty();
        assertThat(new DtoWorkflowDiagram(Arrays.asList(review, null), List.of()).nodes()).isEmpty();
    }

    @Test
    void missingDiagramDataIsSafeForExistingResponses() {
        assertThat(new DtoWorkflowDiagram(null, null)).isEqualTo(DtoWorkflowDiagram.empty());
    }

    @Test
    void doesNotInventConnectionsForUnconnectedNodes() {
        var graph = new DtoWorkflowDiagram(List.of(review, approved), List.of());
        assertThat(graph.nodes()).hasSize(2);
        assertThat(graph.edges()).isEmpty();
    }
}
