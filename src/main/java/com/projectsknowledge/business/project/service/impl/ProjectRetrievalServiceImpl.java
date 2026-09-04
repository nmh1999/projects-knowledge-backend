package com.projectsknowledge.business.project.service.impl;

import com.projectsknowledge.business.project.catalog.CodexProjectCatalog;
import com.projectsknowledge.business.project.entity.Project;
import com.projectsknowledge.business.project.entity.Repository;
import com.projectsknowledge.business.project.mapper.ProjectMapper;
import com.projectsknowledge.business.project.schema.response.DtoProject;
import com.projectsknowledge.business.project.service.ProjectOverviewService;
import com.projectsknowledge.business.project.service.ProjectRetrievalService;
import com.projectsknowledge.general.exception.KnowledgeException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** Lists projects cheaply; only a selected project's detail endpoint requests a cached Codex overview. */
@Service
@RequiredArgsConstructor
public class ProjectRetrievalServiceImpl implements ProjectRetrievalService {

    private final ProjectOverviewService overviews;
    private final CodexProjectCatalog codexProjectCatalog;

    @Override
    public List<DtoProject> findAll() {
        return codexProjectCatalog.projects().stream().map(ProjectMapper::toSummaryDto).toList();
    }

    @Override
    public List<DtoProject> refreshProjects() {
        return codexProjectCatalog.refresh().stream().map(ProjectMapper::toSummaryDto).toList();
    }

    @Override
    public DtoProject findById(String projectId) {
        return overviews.get(requireProject(projectId));
    }

    @Override
    public DtoProject refreshOverview(String projectId) {
        return overviews.refresh(requireProject(projectId));
    }

    @Override
    public Project requireProject(String projectId) {
        if ("all".equalsIgnoreCase(projectId)) {
            return Project.builder()
                .id("all")
                .name("All Projects")
                .repositories(
                    codexProjectCatalog
                        .projects()
                        .stream()
                        .flatMap(project -> project.getRepositories().stream())
                        .toList()
                )
                .build();
        }
        return codexProjectCatalog
            .projects()
            .stream()
            .filter(project -> project.getId().equals(projectId))
            .findFirst()
            .orElseThrow(() -> new KnowledgeException(HttpStatus.NOT_FOUND, "Project not found."));
    }

    @Override
    public Repository requireRepository(String repositoryId) {
        return codexProjectCatalog
            .projects()
            .stream()
            .flatMap(project -> project.getRepositories().stream())
            .filter(repository -> repository.getId().equals(repositoryId))
            .findFirst()
            .orElseThrow(() -> new KnowledgeException(HttpStatus.NOT_FOUND, "Repository is not available."));
    }
}
