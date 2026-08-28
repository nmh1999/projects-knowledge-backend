package com.projectsknowledge;

import com.projectsknowledge.config.ProjectsKnowledgeProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(ProjectsKnowledgeProperties.class)
public class ProjectsKnowledgeApplication {
    public static void main(String[] args) {
        SpringApplication.run(ProjectsKnowledgeApplication.class, args);
    }
}
