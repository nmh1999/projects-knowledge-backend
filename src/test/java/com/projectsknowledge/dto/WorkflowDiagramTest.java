package com.projectsknowledge.dto;

import org.junit.jupiter.api.Test;
import java.util.Arrays;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class WorkflowDiagramTest {
    private final WorkflowDiagram.Node review = new WorkflowDiagram.Node("review", "Review request", "REVIEWER", "decision");
    private final WorkflowDiagram.Node approved = new WorkflowDiagram.Node("approved", "Approved", "", "end");

    @Test void preservesVerifiedBranchesAndReturnLoops() {
        var edges = List.of(new WorkflowDiagram.Edge("review", "approved", "Approve"), new WorkflowDiagram.Edge("approved", "review", "Reopen"));
        var graph = new WorkflowDiagram(List.of(review, approved), edges);
        assertThat(graph.nodes()).hasSize(2);
        assertThat(graph.edges()).containsExactlyElementsOf(edges);
    }

    @Test void rejectsTheWholeGraphWhenAnEdgeReferencesAMissingNode() {
        var graph = new WorkflowDiagram(List.of(review, approved), List.of(new WorkflowDiagram.Edge("review", "missing", "Approve")));
        assertThat(graph.nodes()).isEmpty();
        assertThat(graph.edges()).isEmpty();
    }

    @Test void rejectsDuplicateIdsAndNullNodes() {
        assertThat(new WorkflowDiagram(List.of(review, review), List.of()).nodes()).isEmpty();
        assertThat(new WorkflowDiagram(Arrays.asList(review, null), List.of()).nodes()).isEmpty();
    }

    @Test void missingDiagramDataIsSafeForExistingResponses() {
        assertThat(new WorkflowDiagram(null, null)).isEqualTo(WorkflowDiagram.empty());
    }

    @Test void doesNotInventConnectionsForUnconnectedNodes() {
        var graph = new WorkflowDiagram(List.of(review, approved), List.of());
        assertThat(graph.nodes()).hasSize(2);
        assertThat(graph.edges()).isEmpty();
    }
}
