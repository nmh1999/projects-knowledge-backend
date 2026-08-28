package com.projectsknowledge.dto;

import java.util.List;

public record SourceContentResponse(
        String repositoryId,
        String filePath,
        int startLine,
        int endLine,
        List<SourceLine> lines
) {
    public record SourceLine(int number, String content, boolean highlighted) {}
}
