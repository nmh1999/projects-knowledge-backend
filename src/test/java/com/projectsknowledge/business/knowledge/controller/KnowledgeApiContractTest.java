package com.projectsknowledge.business.knowledge.controller;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.projectsknowledge.business.knowledge.enums.SearchMode;
import com.projectsknowledge.business.knowledge.schema.request.ReqIntegrationDetails;
import com.projectsknowledge.business.knowledge.schema.request.ReqQuestion;
import com.projectsknowledge.business.knowledge.service.QuestionAskService;
import com.projectsknowledge.general.exception.ApiExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
}
