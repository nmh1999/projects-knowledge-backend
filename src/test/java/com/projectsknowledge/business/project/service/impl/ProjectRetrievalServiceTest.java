package com.projectsknowledge.business.project.service.impl;

import static com.projectsknowledge.support.TestFixtures.backendRepository;
import static com.projectsknowledge.support.TestFixtures.project;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.projectsknowledge.business.project.catalog.CodexProjectCatalog;
import com.projectsknowledge.business.project.service.ProjectOverviewService;
import com.projectsknowledge.general.exception.KnowledgeException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;

class ProjectRetrievalServiceTest {

    @TempDir
    Path root;

    @Test
    void allEndpointsUseOnlyTheCurrentCatalogAndDoNotRetainRemovedProjects() {
        var repository = backendRepository("dynamic-repository", root);
        var project = project("dynamic-project", "Runtime name", repository);
        var catalog = mock(CodexProjectCatalog.class);
        when(catalog.projects()).thenReturn(List.of(project));
        var overviews = mock(ProjectOverviewService.class);
        var service = new ProjectRetrievalServiceImpl(overviews, catalog);
        assertThat(service.findAll())
            .extracting(value -> value.name())
            .containsExactly("Runtime name");
        verifyNoInteractions(overviews);
        var summary = service.findAll().getFirst();
        assertThat(summary.overviewUpdatedAt()).isNull();
        when(overviews.get(project)).thenReturn(summary);
        when(overviews.refresh(project)).thenReturn(summary);
        assertThat(service.refreshOverview("dynamic-project")).isSameAs(summary);
        assertThat(service.findById("dynamic-project").repositories()).hasSize(1);
        assertThat(service.requireRepository("dynamic-repository")).isSameAs(repository);
        assertThat(service.requireProject("all").getRepositories()).containsExactly(repository);
        when(catalog.projects()).thenReturn(List.of());
        assertThat(service.findAll()).isEmpty();
        assertThat(service.requireProject("all").getRepositories()).isEmpty();
        assertThatThrownBy(() -> service.requireProject("dynamic-project")).isInstanceOf(KnowledgeException.class);
        assertThatThrownBy(() -> service.requireRepository("dynamic-repository")).isInstanceOf(
            KnowledgeException.class
        );
        assertThatThrownBy(() -> service.findById("dynamic-project")).isInstanceOf(KnowledgeException.class);
        assertThatThrownBy(() -> service.refreshOverview("dynamic-project")).isInstanceOf(KnowledgeException.class);
        verify(overviews, times(1)).get(project);
        verify(overviews, times(1)).refresh(project);
    }

    @Test
    void propagatesCatalogFailureInsteadOfShowingConfiguredProjects() {
        var catalog = mock(CodexProjectCatalog.class);
        when(catalog.projects()).thenThrow(new KnowledgeException(HttpStatus.SERVICE_UNAVAILABLE, "Unavailable"));
        var service = new ProjectRetrievalServiceImpl(mock(ProjectOverviewService.class), catalog);
        assertThatThrownBy(service::findAll).isInstanceOf(KnowledgeException.class).hasMessage("Unavailable");
        assertThatThrownBy(() -> service.requireProject("all")).isInstanceOf(KnowledgeException.class);
    }

    @Test
    void refreshingTheCatalogNeverStartsOverviewAnalysis() {
        var catalog = mock(CodexProjectCatalog.class);
        var overviews = mock(ProjectOverviewService.class);
        var project = project("fresh", "New project");
        when(catalog.refresh()).thenReturn(List.of(project));
        var service = new ProjectRetrievalServiceImpl(overviews, catalog);
        assertThat(service.refreshProjects()).extracting(value -> value.id()).containsExactly("fresh");
        verify(catalog).refresh();
        verifyNoMoreInteractions(catalog);
        verifyNoInteractions(overviews);
    }
}
