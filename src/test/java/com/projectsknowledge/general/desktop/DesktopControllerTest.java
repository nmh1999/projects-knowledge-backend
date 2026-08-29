package com.projectsknowledge.general.desktop;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.projectsknowledge.general.cache.KnowledgeCacheManager;
import com.projectsknowledge.general.config.ProjectsKnowledgeProperties;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class DesktopControllerTest {

    @Test
    void shutdownIsUnavailableOutsideThePackagedDesktopApplication() throws Exception {
        var properties = new ProjectsKnowledgeProperties();
        var desktopApplication = mock(DesktopApplicationService.class);
        var cacheManager = mock(KnowledgeCacheManager.class);
        var mvc = MockMvcBuilders.standaloneSetup(new DesktopController(properties, desktopApplication, cacheManager))
            .build();

        mvc.perform(post("/api/desktop/shutdown").header(DesktopController.DESKTOP_HEADER, "true"))
            .andExpect(status().isNotFound());
        verifyNoInteractions(desktopApplication);
        verifyNoInteractions(cacheManager);
    }

    @Test
    void shutdownRequiresTheDesktopHeaderAndALoopbackRequest() throws Exception {
        var properties = enabledProperties();
        var desktopApplication = mock(DesktopApplicationService.class);
        var cacheManager = mock(KnowledgeCacheManager.class);
        var mvc = MockMvcBuilders.standaloneSetup(new DesktopController(properties, desktopApplication, cacheManager))
            .build();

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
        verifyNoInteractions(cacheManager);
    }

    @Test
    void shutdownAcceptsThePackagedLocalInterface() throws Exception {
        var properties = enabledProperties();
        var desktopApplication = mock(DesktopApplicationService.class);
        var cacheManager = mock(KnowledgeCacheManager.class);
        var mvc = MockMvcBuilders.standaloneSetup(new DesktopController(properties, desktopApplication, cacheManager))
            .build();

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
        verifyNoInteractions(cacheManager);
    }

    @Test
    void cacheClearRequiresThePackagedLocalInterface() throws Exception {
        var properties = enabledProperties();
        var desktopApplication = mock(DesktopApplicationService.class);
        var cacheManager = mock(KnowledgeCacheManager.class);
        var mvc = MockMvcBuilders.standaloneSetup(new DesktopController(properties, desktopApplication, cacheManager))
            .build();

        mvc.perform(delete("/api/desktop/cache")).andExpect(status().isForbidden());
        mvc.perform(
                delete("/api/desktop/cache")
                    .header(DesktopController.DESKTOP_HEADER, "true")
                    .with(request -> {
                        request.setRemoteAddr("127.0.0.1");
                        return request;
                    })
            )
            .andExpect(status().isNoContent());
        verify(cacheManager).clearAll();
        verifyNoInteractions(desktopApplication);
    }

    private ProjectsKnowledgeProperties enabledProperties() {
        var properties = new ProjectsKnowledgeProperties();
        properties.getDesktop().setEnabled(true);
        return properties;
    }
}
