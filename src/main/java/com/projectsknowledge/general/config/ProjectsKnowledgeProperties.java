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
    private Desktop desktop = new Desktop();
    private Storage storage = new Storage();

    @Getter
    @Setter
    public static class Desktop {

        private boolean enabled;
    }

    @Getter
    @Setter
    public static class Storage {

        private boolean persistentCacheEnabled = true;
        private Path persistentCachePath = defaultCachePath();
        private Path codexSettingsPath = defaultApplicationDataPath().resolve("codex-settings.json");

        private static Path defaultCachePath() {
            return defaultApplicationDataPath().resolve("cache").resolve("knowledge.db");
        }

        private static Path defaultApplicationDataPath() {
            String localAppData = System.getenv("LOCALAPPDATA");
            return localAppData == null || localAppData.isBlank()
                ? Path.of(System.getProperty("user.home"), ".projects-knowledge")
                : Path.of(localAppData, "ProjectsKnowledge");
        }
    }

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
        private String model = "";
        private String reasoningEffort = "medium";
        private int timeoutSeconds = 300;
        private int modelCacheSeconds = 18_000;
        private int projectCacheSeconds = 86_400;
        private int answerCacheSeconds = 86_400;
        private int integrationCacheSeconds = 86_400;
        private int overviewCacheSeconds = 86_400;
        private int overviewCacheMaxEntries = 50;
        private int answerCacheMaxEntries = 100;
        private List<Path> excludedPaths = new ArrayList<>();
    }
}
