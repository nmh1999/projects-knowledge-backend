package com.projectsknowledge;

import com.projectsknowledge.general.config.ProjectsKnowledgeProperties;
import com.projectsknowledge.general.desktop.DesktopInstanceCoordinator;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(ProjectsKnowledgeProperties.class)
public class ProjectsKnowledgeApplication {

    public static void main(String[] args) {
        if (!DesktopInstanceCoordinator.startOrActivateRunningInstance(args)) return;
        SpringApplication.run(ProjectsKnowledgeApplication.class, args);
    }
}
