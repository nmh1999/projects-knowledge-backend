package com.projectsknowledge.controller;

import com.projectsknowledge.dto.ProjectDto;
import com.projectsknowledge.service.ProjectService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/projects")
public class ProjectController {
    private final ProjectService projectService;
    public ProjectController(ProjectService projectService) { this.projectService = projectService; }

    @GetMapping
    public List<ProjectDto> findAll() { return projectService.findAll(); }

    @GetMapping("/{projectId}")
    public ProjectDto findById(@PathVariable String projectId) { return projectService.findById(projectId); }

    @PostMapping("/{projectId}/overview/refresh")
    public ProjectDto refreshOverview(@PathVariable String projectId) { return projectService.refreshOverview(projectId); }
}
