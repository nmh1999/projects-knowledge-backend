package com.projectsknowledge.general.config;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "projects-knowledge")
@Getter
@Setter
public class ProjectsKnowledgeProperties {

    private Scan scan = new Scan();
    private Codex codex = new Codex();

    @Getter
    @Setter
    public static class Scan {

        private long maxFileBytes = 1_000_000;
        private int fileCacheSeconds = 30;
    }

    @Getter
    @Setter
    public static class Codex {

        private boolean enabled = true;
        private String command = System.getProperty("os.name", "").toLowerCase().contains("win")
            ? "codex.exe"
            : "codex";
        private int timeoutSeconds = 300;
        private int projectCacheSeconds = 3_600;
        private int answerCacheSeconds = 900;
        private int integrationCacheSeconds = 18_000;
        private int overviewCacheSeconds = 18_000;
        private int overviewCacheMaxEntries = 50;
        private int answerCacheMaxEntries = 100;
        private List<Path> excludedPaths = new ArrayList<>();
    }
}
