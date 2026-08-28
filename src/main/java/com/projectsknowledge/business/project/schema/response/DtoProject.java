package com.projectsknowledge.business.project.schema.response;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.Instant;
import java.util.List;
import lombok.Builder;

@Builder
public record DtoProject(
    String id,
    String name,
    List<DtoRepository> repositories,
    DtoProjectOverview overview,
    @JsonFormat(shape = JsonFormat.Shape.STRING) Instant overviewUpdatedAt
) {}
