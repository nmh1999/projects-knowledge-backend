package com.projectsknowledge;

import static org.assertj.core.api.Assertions.assertThat;

import com.projectsknowledge.business.knowledge.service.QuestionAskService;
import com.projectsknowledge.business.knowledge.service.impl.QuestionAskServiceImpl;
import com.projectsknowledge.business.project.service.ProjectOverviewService;
import com.projectsknowledge.business.project.service.ProjectRetrievalService;
import com.projectsknowledge.business.project.service.impl.ProjectOverviewServiceImpl;
import com.projectsknowledge.business.project.service.impl.ProjectRetrievalServiceImpl;
import com.projectsknowledge.business.source.service.SourceRetrievalService;
import com.projectsknowledge.business.source.service.impl.SourceRetrievalServiceImpl;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

/** Verifies package scanning and constructor wiring without starting any Codex analysis. */
@SpringBootTest(properties = "projects-knowledge.codex.enabled=false")
class ProjectsKnowledgeApplicationTest {

    @Autowired
    private ApplicationContext context;

    @Test
    void discoversTheBusinessImplementationsAndSharedConfiguration() {
        assertThat(context.getBean(ProjectRetrievalService.class)).isInstanceOf(ProjectRetrievalServiceImpl.class);
        assertThat(context.getBean(ProjectOverviewService.class)).isInstanceOf(ProjectOverviewServiceImpl.class);
        assertThat(context.getBean(QuestionAskService.class)).isInstanceOf(QuestionAskServiceImpl.class);
        assertThat(context.getBean(SourceRetrievalService.class)).isInstanceOf(SourceRetrievalServiceImpl.class);
        assertThat(context.getBean(Clock.class).getZone().getId()).isEqualTo("Z");
    }
}
