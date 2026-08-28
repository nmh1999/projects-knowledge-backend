package com.projectsknowledge.controller;

import com.projectsknowledge.dto.SourceContentResponse;
import com.projectsknowledge.service.SourceService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/sources")
public class SourceController {
    private final SourceService sourceService;
    public SourceController(SourceService sourceService) { this.sourceService = sourceService; }

    @GetMapping("/content")
    public SourceContentResponse content(@RequestParam String repositoryId,
                                         @RequestParam String filePath,
                                         @RequestParam(defaultValue = "1") int startLine,
                                         @RequestParam(defaultValue = "80") int endLine) {
        return sourceService.content(repositoryId, filePath, startLine, endLine);
    }
}
