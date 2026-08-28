package com.projectsknowledge.business.source.controller;

import com.projectsknowledge.business.source.schema.response.DtoSourceContent;
import com.projectsknowledge.business.source.service.SourceRetrievalService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/sources")
@RequiredArgsConstructor
public class SourceRetrievalController {

    private final SourceRetrievalService sourceService;

    @GetMapping("/content")
    public ResponseEntity<DtoSourceContent> content(
        @RequestParam String repositoryId,
        @RequestParam String filePath,
        @RequestParam(defaultValue = "1") int startLine,
        @RequestParam(defaultValue = "80") int endLine
    ) {
        return ResponseEntity.ok(sourceService.content(repositoryId, filePath, startLine, endLine));
    }
}
