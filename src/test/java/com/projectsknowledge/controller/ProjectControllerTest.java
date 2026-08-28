package com.projectsknowledge.controller;

import com.projectsknowledge.dto.ProjectDto;
import com.projectsknowledge.dto.ProjectOverviewDto;
import com.projectsknowledge.service.ProjectService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ProjectControllerTest {
    @Test void refreshUsesPostAndReturnsAnIsoTimestamp() throws Exception {
        var service = mock(ProjectService.class);
        var snapshot = new ProjectDto("dynamic", "Runtime project", List.of(),
                new ProjectOverviewDto(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of()),
                Instant.parse("2026-01-01T10:30:00Z"));
        when(service.findById("dynamic")).thenReturn(snapshot);
        when(service.refreshOverview("dynamic")).thenReturn(snapshot);
        var mvc = MockMvcBuilders.standaloneSetup(new ProjectController(service)).build();
        mvc.perform(get("/api/projects/dynamic")).andExpect(status().isOk())
                .andExpect(jsonPath("$.overviewUpdatedAt").value("2026-01-01T10:30:00Z"));
        verify(service, never()).refreshOverview(anyString());
        mvc.perform(post("/api/projects/dynamic/overview/refresh")).andExpect(status().isOk())
                .andExpect(jsonPath("$.overviewUpdatedAt").value("2026-01-01T10:30:00Z"));
        verify(service).refreshOverview("dynamic");
        mvc.perform(get("/api/projects/dynamic/overview/refresh")).andExpect(status().isMethodNotAllowed());
    }
}
