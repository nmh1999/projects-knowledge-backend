package com.projectsknowledge.business.knowledge.controller;

import com.projectsknowledge.business.knowledge.schema.request.ReqQuestion;
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
@RequestMapping("/api/questions")
@RequiredArgsConstructor
public class QuestionAskController {

    private final QuestionAskService questionService;

    @PostMapping
    public ResponseEntity<DtoKnowledgeAnswer> ask(@Valid @RequestBody ReqQuestion request) {
        return ResponseEntity.ok(questionService.ask(request));
    }
}
