package com.projectsknowledge.business.project.service;

import com.projectsknowledge.business.project.entity.Project;
import com.projectsknowledge.business.project.schema.response.DtoProject;

/** Business boundary for project overview service. */
public interface ProjectOverviewService {
    DtoProject get(Project project);

    DtoProject refresh(Project project);
}
