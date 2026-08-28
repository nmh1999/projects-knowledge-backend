package com.projectsknowledge.business.project.service;

import com.projectsknowledge.business.project.entity.Project;
import com.projectsknowledge.business.project.entity.Repository;
import com.projectsknowledge.business.project.schema.response.DtoProject;
import java.util.List;

/** Business boundary for project retrieval service. */
public interface ProjectRetrievalService {
    List<DtoProject> findAll();

    List<DtoProject> refreshProjects();

    DtoProject findById(String projectId);

    DtoProject refreshOverview(String projectId);

    Project requireProject(String projectId);

    Repository requireRepository(String repositoryId);
}
