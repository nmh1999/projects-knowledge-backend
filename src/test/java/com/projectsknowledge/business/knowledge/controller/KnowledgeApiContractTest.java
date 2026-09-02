package com.projectsknowledge.business.knowledge.controller;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.projectsknowledge.business.knowledge.enums.SearchMode;
import com.projectsknowledge.business.knowledge.schema.request.ReqIntegrationDetails;
import com.projectsknowledge.business.knowledge.schema.request.ReqQuestion;
import com.projectsknowledge.business.knowledge.service.QuestionAskService;
import com.projectsknowledge.general.exception.ApiExceptionHandler;
import com.projectsknowledge.general.exception.ApiErrorCode;
import com.projectsknowledge.general.exception.KnowledgeException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Request names and service interfaces must not change the existing browser API contract. */
class KnowledgeApiContractTest {

    private final QuestionAskService service = mock(QuestionAskService.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(
            new QuestionAskController(service),
            new IntegrationDetailsController(service)
        )
            .setControllerAdvice(new ApiExceptionHandler())
            .build();
    }

    @Test
    void preservesTheAdvancedDefaultForOlderClients() throws Exception {
        mvc
            .perform(
                post("/api/questions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {"projectId":"sample","question":"Explain the flow","language":"ar"}
                        """
                    )
            )
            .andExpect(status().isOk());
        verify(service).ask(new ReqQuestion("sample", "Explain the flow", "ar", SearchMode.ADVANCED));
    }

    @Test
    void acceptsDatabaseModeWithoutChangingTheEndpoint() throws Exception {
        mvc
            .perform(
                post("/api/questions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {"projectId":"sample","question":"Explain order tables","language":"ar","mode":"database"}
                        """
                    )
            )
            .andExpect(status().isOk());
        verify(service).ask(new ReqQuestion("sample", "Explain order tables", "ar", SearchMode.DATABASE));
    }

    @Test
    void rejectsBlankQuestionsBeforeTheServiceIsCalled() throws Exception {
        mvc
            .perform(
                post("/api/questions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {"projectId":"sample","question":" ","mode":"basic"}
                        """
                    )
            )
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("The request is invalid."))
            .andExpect(jsonPath("$.timestamp").exists());
        verifyNoInteractions(service);
    }

    @Test
    void preservesTheIntegrationDetailsRequest() throws Exception {
        mvc
            .perform(
                post("/api/integrations/details")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {"projectId":"sample","name":"Example integration","language":"en"}
                        """
                    )
            )
            .andExpect(status().isOk());
        verify(service).explainIntegration(new ReqIntegrationDetails("sample", "Example integration", "en"));
    }

    @Test
    void refreshUsesTheSameValidatedQuestionBodyAndReturnsIsoCacheDates() throws Exception {
        var question = new ReqQuestion("sample", "Explain tables", "ar", SearchMode.DATABASE);
        var updated = java.time.Instant.parse("2026-08-28T10:00:00Z");
        when(service.refresh(question)).thenReturn(
            com.projectsknowledge.business.knowledge.schema.response.DtoKnowledgeAnswer.builder()
                .updatedAt(updated)
                .expiresAt(updated.plusSeconds(18_000))
                .build()
        );
        mvc
            .perform(
                post("/api/questions/refresh")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {"projectId":"sample","question":"Explain tables","language":"ar","mode":"database"}
                        """
                    )
            )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.updatedAt").value("2026-08-28T10:00:00Z"))
            .andExpect(jsonPath("$.expiresAt").value("2026-08-28T15:00:00Z"));
        verify(service).refresh(question);
        verify(service, never()).ask(any());
    }

    @Test
    void refreshRejectsInvalidQuestionAndIntegrationBeforeAnalysis() throws Exception {
        for (String endpoint : new String[] { "/api/questions/refresh", "/api/integrations/details/refresh" }) {
            mvc
                .perform(post(endpoint).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(service);
    }

    @Test
    void refreshesIntegrationWithoutUsingTheNormalCacheEndpoint() throws Exception {
        mvc
            .perform(
                post("/api/integrations/details/refresh")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {"projectId":"sample","name":"Example integration","language":"en"}
                        """
                    )
            )
            .andExpect(status().isOk());
        verify(service).refreshIntegration(new ReqIntegrationDetails("sample", "Example integration", "en"));
        verify(service, never()).explainIntegration(any());
    }

    @Test
    void returnsAStableRetryContractWithoutExposingTransportDetails() throws Exception {
        when(service.ask(any())).thenThrow(
            new KnowledgeException(
                HttpStatus.GATEWAY_TIMEOUT,
                ApiErrorCode.CODEX_TIMEOUT,
                "Timed out waiting for Codex.",
                true
            )
        );

        mvc
            .perform(
                post("/api/questions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {"projectId":"sample","question":"Explain the flow","language":"en","mode":"basic"}
                        """
                    )
            )
            .andExpect(status().isGatewayTimeout())
            .andExpect(jsonPath("$.code").value("CODEX_TIMEOUT"))
            .andExpect(jsonPath("$.message").value("Timed out waiting for Codex."))
            .andExpect(jsonPath("$.retryable").value(true))
            .andExpect(jsonPath("$.timestamp").exists());
    }
}
