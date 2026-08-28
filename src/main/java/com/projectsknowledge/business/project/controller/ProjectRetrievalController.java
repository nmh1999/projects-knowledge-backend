package com.projectsknowledge.business.project.controller;

import com.projectsknowledge.business.project.schema.response.DtoProject;
import com.projectsknowledge.business.project.service.ProjectRetrievalService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/projects")
@RequiredArgsConstructor
public class ProjectRetrievalController {

    private final ProjectRetrievalService projectService;

    @GetMapping
    public ResponseEntity<List<DtoProject>> findAll() {
        return ResponseEntity.ok(projectService.findAll());
    }

    @GetMapping("/{projectId}")
    public ResponseEntity<DtoProject> findById(@PathVariable String projectId) {
        return ResponseEntity.ok(projectService.findById(projectId));
    }

    @PostMapping("/{projectId}/overview/refresh")
    public ResponseEntity<DtoProject> refreshOverview(@PathVariable String projectId) {
        return ResponseEntity.ok(projectService.refreshOverview(projectId));
    }
}
