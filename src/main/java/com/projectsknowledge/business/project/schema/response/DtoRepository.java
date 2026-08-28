package com.projectsknowledge.business.project.schema.response;

import java.util.List;
import lombok.Builder;

@Builder
public record DtoRepository(
    String id,
    String name,
    String type,
    boolean available,
    List<String> languages,
    List<String> frameworks,
    List<String> buildTools
) {}
