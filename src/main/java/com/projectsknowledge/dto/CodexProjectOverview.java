package com.projectsknowledge.dto;

import java.util.List;

/** Compact overview output; each integration must point to its implementation evidence. */
public record CodexProjectOverview(List<String> frontend, List<String> backend, List<String> databases,
                                   List<String> domains, List<IntegrationEvidence> integrations,
                                   List<String> messaging, List<String> scheduledJobs) {
    public record IntegrationEvidence(String name, String repositoryName, String filePath) {}
}
