package com.projectsknowledge.controller;

import com.projectsknowledge.dto.IntegrationDetailsRequest;
import com.projectsknowledge.dto.KnowledgeAnswer;
import com.projectsknowledge.service.QuestionService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/integrations")
public class IntegrationController {
    private final QuestionService questionService;

    public IntegrationController(QuestionService questionService) { this.questionService = questionService; }

    @PostMapping("/details")
    public KnowledgeAnswer details(@Valid @RequestBody IntegrationDetailsRequest request) {
        return questionService.explainIntegration(request);
    }
}
