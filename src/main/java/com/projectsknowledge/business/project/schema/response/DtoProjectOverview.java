package com.projectsknowledge.business.project.schema.response;

import java.util.List;
import lombok.Builder;

@Builder
public record DtoProjectOverview(
    List<String> frontend,
    List<String> backend,
    List<String> databases,
    List<String> domains,
    List<String> integrations,
    List<String> messaging,
    List<String> scheduledJobs
) {
    public static DtoProjectOverview empty() {
        return new DtoProjectOverview(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
    }
}
