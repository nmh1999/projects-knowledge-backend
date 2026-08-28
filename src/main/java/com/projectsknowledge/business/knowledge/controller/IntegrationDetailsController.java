package com.projectsknowledge.business.knowledge.controller;

import com.projectsknowledge.business.knowledge.schema.request.ReqIntegrationDetails;
import com.projectsknowledge.business.knowledge.schema.response.DtoKnowledgeAnswer;
import com.projectsknowledge.business.knowledge.service.QuestionAskService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/integrations")
@RequiredArgsConstructor
public class IntegrationDetailsController {

    private final QuestionAskService questionService;

    @PostMapping("/details")
    public ResponseEntity<DtoKnowledgeAnswer> details(@Valid @RequestBody ReqIntegrationDetails request) {
        return ResponseEntity.ok(questionService.explainIntegration(request));
    }
}
