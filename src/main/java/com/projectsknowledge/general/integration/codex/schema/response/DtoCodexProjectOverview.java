package com.projectsknowledge.general.integration.codex.schema.response;

import java.util.List;
import lombok.Builder;

/** Compact overview output; each integration must point to its implementation evidence. */
@Builder
public record DtoCodexProjectOverview(
    List<String> frontend,
    List<String> backend,
    List<String> databases,
    List<String> domains,
    List<IntegrationEvidence> integrations,
    List<String> messaging,
    List<String> scheduledJobs
) {
    public record IntegrationEvidence(String name, String repositoryName, String filePath) {}
}
