package com.projectsknowledge.dto;

import java.util.List;

public record ProjectOverviewDto(
        List<String> frontend,
        List<String> backend,
        List<String> databases,
        List<String> domains,
        List<String> integrations,
        List<String> messaging,
        List<String> scheduledJobs
) {}
