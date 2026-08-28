package com.projectsknowledge.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.util.List;
import java.time.Instant;

public record ProjectDto(
        String id,
        String name,
        List<RepositoryDto> repositories,
        ProjectOverviewDto overview,
        @JsonFormat(shape = JsonFormat.Shape.STRING) Instant overviewUpdatedAt
) {}
