package com.projectsknowledge.dto;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Reject malformed graphs as a whole rather than silently drawing a misleading partial flow. */
public record WorkflowDiagram(List<Node> nodes, List<Edge> edges) {
    public WorkflowDiagram {
        nodes = nodes == null ? List.of() : nodes;
        edges = edges == null ? List.of() : edges;
        Set<String> ids = new HashSet<>();
        boolean valid = nodes.size() <= 10 && edges.size() <= 16;
        for (Node node : nodes) {
            valid &= node != null && text(node.id()) && text(node.title()) && node.actor() != null
                    && node.type() != null && Set.of("start", "action", "decision", "end").contains(node.type())
                    && ids.add(node.id());
        }
        for (Edge edge : edges) {
            valid &= edge != null && ids.contains(edge.from()) && ids.contains(edge.to()) && edge.label() != null;
        }
        nodes = valid ? List.copyOf(nodes) : List.of();
        edges = valid ? edges.stream().distinct().toList() : List.of();
    }

    public static WorkflowDiagram empty() { return new WorkflowDiagram(List.of(), List.of()); }
    private static boolean text(String value) { return value != null && !value.isBlank(); }
    public record Node(String id, String title, String actor, String type) {}
    public record Edge(String from, String to, String label) {}
}
