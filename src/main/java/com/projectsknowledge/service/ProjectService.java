package com.projectsknowledge.service;

import com.projectsknowledge.codex.CodexProjectCatalog;
import com.projectsknowledge.model.Project;
import com.projectsknowledge.model.Repository;
import com.projectsknowledge.dto.ProjectDto;
import com.projectsknowledge.dto.ProjectOverviewDto;
import com.projectsknowledge.dto.RepositoryDto;
import com.projectsknowledge.exception.KnowledgeException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.util.List;

/** Lists projects cheaply; only a selected project's detail endpoint requests a cached Codex overview. */
@Service
public class ProjectService {
    private final ProjectOverviewService overviews;
    private final CodexProjectCatalog codexProjectCatalog;

    public ProjectService(ProjectOverviewService overviews, CodexProjectCatalog codexProjectCatalog) {
        this.overviews = overviews;
        this.codexProjectCatalog = codexProjectCatalog;
    }

    public List<ProjectDto> findAll() {
        return codexProjectCatalog.projects().stream().map(this::toSummaryDto).toList();
    }

    public ProjectDto findById(String projectId) {
        return overviews.get(requireProject(projectId));
    }

    public ProjectDto refreshOverview(String projectId) {
        return overviews.refresh(requireProject(projectId));
    }

    public Project requireProject(String projectId) {
        if ("all".equalsIgnoreCase(projectId)) {
            Project all = new Project();
            all.setId("all");
            all.setName("All Projects");
            all.setRepositories(codexProjectCatalog.projects().stream().flatMap(p -> p.getRepositories().stream()).toList());
            return all;
        }
        return codexProjectCatalog.projects().stream().filter(project -> project.getId().equals(projectId)).findFirst()
                .orElseThrow(() -> new KnowledgeException(HttpStatus.NOT_FOUND, "Project not found."));
    }

    public Repository requireRepository(String repositoryId) {
        return codexProjectCatalog.projects().stream().flatMap(project -> project.getRepositories().stream())
                .filter(repository -> repository.getId().equals(repositoryId)).findFirst()
                .orElseThrow(() -> new KnowledgeException(HttpStatus.NOT_FOUND, "Repository is not available."));
    }

    private ProjectDto toSummaryDto(Project project) {
        List<RepositoryDto> repositories = project.getRepositories().stream().map(repository -> new RepositoryDto(
                repository.getId(), repository.getName(), repository.getType().name(),
                Files.isDirectory(repository.getPath()), List.of(), List.of(), List.of()
        )).toList();
        return new ProjectDto(project.getId(), project.getName(), repositories,
                new ProjectOverviewDto(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of()), null);
    }

}
