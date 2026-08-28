package com.projectsknowledge.business.source.service.impl;

import com.projectsknowledge.business.project.entity.Repository;
import com.projectsknowledge.business.project.service.ProjectRetrievalService;
import com.projectsknowledge.business.source.schema.response.DtoSourceContent;
import com.projectsknowledge.business.source.schema.response.DtoSourceContent.SourceLine;
import com.projectsknowledge.business.source.service.SourceRetrievalService;
import com.projectsknowledge.general.exception.KnowledgeException;
import com.projectsknowledge.general.scanner.RepositoryScanner;
import com.projectsknowledge.general.security.SecretRedactionService;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** Returns a bounded, redacted source-code window for evidence links in an answer. */
@Service
@RequiredArgsConstructor
public class SourceRetrievalServiceImpl implements SourceRetrievalService {

    private static final int MAX_LINES = 400;
    private final ProjectRetrievalService projectService;
    private final RepositoryScanner scanner;
    private final SecretRedactionService redactionService;

    @Override
    public DtoSourceContent content(String repositoryId, String filePath, int startLine, int endLine) {
        if (startLine < 1 || endLine < startLine || endLine - startLine + 1 > MAX_LINES) {
            throw new KnowledgeException(HttpStatus.BAD_REQUEST, "The requested source line range is invalid.");
        }
        Repository repository = projectService.requireRepository(repositoryId);
        Path source = scanner.resolveSource(repository, filePath);
        List<String> allLines = scanner.readLines(repository, source);
        if (allLines.isEmpty() || startLine > allLines.size()) {
            throw new KnowledgeException(HttpStatus.BAD_REQUEST, "The requested source line range is invalid.");
        }
        int safeEnd = Math.min(endLine, allLines.size());
        int contextStart = Math.max(1, startLine - 3);
        int contextEnd = Math.min(allLines.size(), safeEnd + 3);
        List<SourceLine> lines = new ArrayList<>();
        for (int number = contextStart; number <= contextEnd; number++) {
            lines.add(
                new SourceLine(
                    number,
                    redactionService.redact(allLines.get(number - 1)),
                    number >= startLine && number <= safeEnd
                )
            );
        }
        return new DtoSourceContent(repositoryId, filePath, contextStart, contextEnd, lines);
    }
}
