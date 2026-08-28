package com.projectsknowledge.business.project.controller;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.projectsknowledge.business.project.schema.response.DtoProject;
import com.projectsknowledge.business.project.schema.response.DtoProjectOverview;
import com.projectsknowledge.business.project.service.ProjectRetrievalService;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ProjectRetrievalControllerTest {

    @Test
    void refreshUsesPostAndReturnsAnIsoTimestamp() throws Exception {
        var service = mock(ProjectRetrievalService.class);
        var snapshot = new DtoProject(
            "dynamic",
            "Runtime project",
            List.of(),
            new DtoProjectOverview(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of()),
            Instant.parse("2026-01-01T10:30:00Z")
        );
        when(service.findById("dynamic")).thenReturn(snapshot);
        when(service.refreshOverview("dynamic")).thenReturn(snapshot);
        var mvc = MockMvcBuilders.standaloneSetup(new ProjectRetrievalController(service)).build();
        mvc
            .perform(get("/api/projects/dynamic"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.overviewUpdatedAt").value("2026-01-01T10:30:00Z"));
        verify(service, never()).refreshOverview(anyString());
        mvc
            .perform(post("/api/projects/dynamic/overview/refresh"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.overviewUpdatedAt").value("2026-01-01T10:30:00Z"));
        verify(service).refreshOverview("dynamic");
        mvc.perform(get("/api/projects/dynamic/overview/refresh")).andExpect(status().isMethodNotAllowed());
    }
}
