package com.projectsknowledge.general.integration.codex.controller;

import com.projectsknowledge.general.integration.codex.schema.request.ReqCodexSettings;
import com.projectsknowledge.general.integration.codex.schema.response.DtoCodexSettings;
import com.projectsknowledge.general.integration.codex.schema.response.DtoCodexStatus;
import com.projectsknowledge.general.integration.codex.service.CodexModelService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/codex")
@RequiredArgsConstructor
public class CodexStatusController {

    private final CodexModelService models;

    @GetMapping("/status")
    public ResponseEntity<DtoCodexStatus> status() {
        return ResponseEntity.ok(models.status());
    }

    @GetMapping("/settings")
    public ResponseEntity<DtoCodexSettings> settings() {
        return ResponseEntity.ok(models.settings());
    }

    @PutMapping("/settings")
    public ResponseEntity<DtoCodexSettings> updateSettings(@Valid @RequestBody ReqCodexSettings request) {
        return ResponseEntity.ok(models.updateSettings(request));
    }
}
