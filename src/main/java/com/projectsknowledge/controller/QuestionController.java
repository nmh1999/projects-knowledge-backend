package com.projectsknowledge.controller;

import com.projectsknowledge.dto.KnowledgeAnswer;
import com.projectsknowledge.dto.QuestionRequest;
import com.projectsknowledge.service.QuestionService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/questions")
public class QuestionController {
    private final QuestionService questionService;
    public QuestionController(QuestionService questionService) { this.questionService = questionService; }

    @PostMapping
    public KnowledgeAnswer ask(@Valid @RequestBody QuestionRequest request) { return questionService.ask(request); }
}
