package com.projectsknowledge.business.source.schema.response;

import java.util.List;
import lombok.Builder;

@Builder
public record DtoSourceContent(
    String repositoryId,
    String filePath,
    int startLine,
    int endLine,
    List<SourceLine> lines
) {
    public record SourceLine(int number, String content, boolean highlighted) {}
}
