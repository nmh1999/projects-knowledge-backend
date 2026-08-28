package com.projectsknowledge.service;

import com.projectsknowledge.config.ProjectsKnowledgeProperties;
import com.projectsknowledge.codex.CodexProjectCatalog;
import com.projectsknowledge.model.Project;
import com.projectsknowledge.model.Repository;
import com.projectsknowledge.dto.SourceContentResponse;
import com.projectsknowledge.exception.KnowledgeException;
import com.projectsknowledge.scanner.RepositoryScanner;
import com.projectsknowledge.scanner.RepositoryScannerTest;
import com.projectsknowledge.security.SecretRedactionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SourceServiceTest {
    @TempDir Path root;

    @Test
    void returnsValidRedactedLineRanges() throws Exception {
        Files.writeString(root.resolve("application.properties"), "name=demo\npayment.api-key=ABC123\ntimeout=30\n");
        ProjectsKnowledgeProperties properties = new ProjectsKnowledgeProperties();
        Repository repository = RepositoryScannerTest.repository("repo", root);
        Project project = new Project(); project.setId("project"); project.setName("Project"); project.setRepositories(List.of(repository));
        CodexProjectCatalog catalog = mock(CodexProjectCatalog.class);
        when(catalog.projects()).thenReturn(List.of(project));
        SecretRedactionService redaction = new SecretRedactionService(); RepositoryScanner scanner = new RepositoryScanner(properties);
        SourceService service = new SourceService(new ProjectService(mock(ProjectOverviewService.class), catalog), scanner, redaction);

        SourceContentResponse response = service.content("repo", "application.properties", 2, 2);
        assertThat(response.lines()).anyMatch(line -> line.content().contains("[REDACTED]"));
        assertThatThrownBy(() -> service.content("repo", "application.properties", 0, 2)).isInstanceOf(KnowledgeException.class);
    }
}
