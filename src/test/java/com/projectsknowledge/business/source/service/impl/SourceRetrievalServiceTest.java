package com.projectsknowledge.business.source.service.impl;

import static com.projectsknowledge.support.TestFixtures.backendRepository;
import static com.projectsknowledge.support.TestFixtures.project;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.projectsknowledge.business.project.catalog.CodexProjectCatalog;
import com.projectsknowledge.business.project.entity.Project;
import com.projectsknowledge.business.project.entity.Repository;
import com.projectsknowledge.business.project.service.ProjectOverviewService;
import com.projectsknowledge.business.project.service.impl.ProjectRetrievalServiceImpl;
import com.projectsknowledge.business.source.schema.response.DtoSourceContent;
import com.projectsknowledge.business.source.service.SourceRetrievalService;
import com.projectsknowledge.general.config.ProjectsKnowledgeProperties;
import com.projectsknowledge.general.exception.KnowledgeException;
import com.projectsknowledge.general.scanner.RepositoryScanner;
import com.projectsknowledge.general.security.SecretRedactionService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SourceRetrievalServiceTest {

    @TempDir
    Path root;

    @Test
    void returnsValidRedactedLineRanges() throws Exception {
        Files.writeString(root.resolve("application.properties"), "name=demo\npayment.api-key=ABC123\ntimeout=30\n");
        ProjectsKnowledgeProperties properties = new ProjectsKnowledgeProperties();
        Repository repository = backendRepository("repo", root);
        Project project = project("project", "Project", repository);
        CodexProjectCatalog catalog = mock(CodexProjectCatalog.class);
        when(catalog.projects()).thenReturn(List.of(project));
        SecretRedactionService redaction = new SecretRedactionService();
        RepositoryScanner scanner = new RepositoryScanner(properties);
        SourceRetrievalService service = new SourceRetrievalServiceImpl(
            new ProjectRetrievalServiceImpl(mock(ProjectOverviewService.class), catalog),
            scanner,
            redaction
        );

        DtoSourceContent response = service.content("repo", "application.properties", 2, 2);
        assertThat(response.lines()).anyMatch(line -> line.content().contains("[REDACTED]"));
        assertThatThrownBy(() -> service.content("repo", "application.properties", 0, 2)).isInstanceOf(
            KnowledgeException.class
        );
    }
}
