package com.projectsknowledge.service;

import com.projectsknowledge.model.Repository;
import com.projectsknowledge.dto.SourceContentResponse;
import com.projectsknowledge.dto.SourceContentResponse.SourceLine;
import com.projectsknowledge.exception.KnowledgeException;
import com.projectsknowledge.scanner.RepositoryScanner;
import com.projectsknowledge.security.SecretRedactionService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Returns a bounded, redacted source-code window for evidence links in an answer. */
@Service
public class SourceService {
    private static final int MAX_LINES = 400;
    private final ProjectService projectService;
    private final RepositoryScanner scanner;
    private final SecretRedactionService redactionService;

    public SourceService(ProjectService projectService, RepositoryScanner scanner, SecretRedactionService redactionService) {
        this.projectService = projectService;
        this.scanner = scanner;
        this.redactionService = redactionService;
    }

    public SourceContentResponse content(String repositoryId, String filePath, int startLine, int endLine) {
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
            lines.add(new SourceLine(number, redactionService.redact(allLines.get(number - 1)), number >= startLine && number <= safeEnd));
        }
        return new SourceContentResponse(repositoryId, filePath, contextStart, contextEnd, lines);
    }
}
