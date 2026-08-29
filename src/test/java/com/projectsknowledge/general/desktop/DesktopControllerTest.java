package com.projectsknowledge.general.desktop;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.projectsknowledge.general.config.ProjectsKnowledgeProperties;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class DesktopControllerTest {

    @Test
    void shutdownIsUnavailableOutsideThePackagedDesktopApplication() throws Exception {
        var properties = new ProjectsKnowledgeProperties();
        var desktopApplication = mock(DesktopApplicationService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new DesktopController(properties, desktopApplication)).build();

        mvc.perform(post("/api/desktop/shutdown").header(DesktopController.DESKTOP_HEADER, "true"))
            .andExpect(status().isNotFound());
        verifyNoInteractions(desktopApplication);
    }

    @Test
    void shutdownRequiresTheDesktopHeaderAndALoopbackRequest() throws Exception {
        var properties = enabledProperties();
        var desktopApplication = mock(DesktopApplicationService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new DesktopController(properties, desktopApplication)).build();

        mvc.perform(post("/api/desktop/shutdown")).andExpect(status().isForbidden());
        mvc.perform(
                post("/api/desktop/shutdown")
                    .header(DesktopController.DESKTOP_HEADER, "true")
                    .with(request -> {
                        request.setRemoteAddr("192.0.2.1");
                        return request;
                    })
            )
            .andExpect(status().isForbidden());
        verifyNoInteractions(desktopApplication);
    }

    @Test
    void shutdownAcceptsThePackagedLocalInterface() throws Exception {
        var properties = enabledProperties();
        var desktopApplication = mock(DesktopApplicationService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new DesktopController(properties, desktopApplication)).build();

        mvc.perform(
                post("/api/desktop/shutdown")
                    .header(DesktopController.DESKTOP_HEADER, "true")
                    .with(request -> {
                        request.setRemoteAddr("127.0.0.1");
                        return request;
                    })
            )
            .andExpect(status().isAccepted());
        verify(desktopApplication).requestShutdown();
    }

    private ProjectsKnowledgeProperties enabledProperties() {
        var properties = new ProjectsKnowledgeProperties();
        properties.getDesktop().setEnabled(true);
        return properties;
    }
}
