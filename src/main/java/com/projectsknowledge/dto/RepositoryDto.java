package com.projectsknowledge.dto;

import java.util.List;

public record RepositoryDto(
        String id,
        String name,
        String type,
        boolean available,
        List<String> languages,
        List<String> frameworks,
        List<String> buildTools
) {}
