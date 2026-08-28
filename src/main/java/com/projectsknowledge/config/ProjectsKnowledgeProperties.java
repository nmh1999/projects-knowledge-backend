package com.projectsknowledge.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@ConfigurationProperties(prefix = "projects-knowledge")
public class ProjectsKnowledgeProperties {
    private Scan scan = new Scan();
    private Codex codex = new Codex();

    public Scan getScan() { return scan; }
    public void setScan(Scan scan) { this.scan = scan; }
    public Codex getCodex() { return codex; }
    public void setCodex(Codex codex) { this.codex = codex; }

    public static class Scan {
        private long maxFileBytes = 1_000_000;
        private int fileCacheSeconds = 30;
        public long getMaxFileBytes() { return maxFileBytes; }
        public void setMaxFileBytes(long maxFileBytes) { this.maxFileBytes = maxFileBytes; }
        public int getFileCacheSeconds() { return fileCacheSeconds; }
        public void setFileCacheSeconds(int fileCacheSeconds) { this.fileCacheSeconds = fileCacheSeconds; }
    }

    public static class Codex {
        private boolean enabled = true;
        private String command = System.getProperty("os.name", "").toLowerCase().contains("win") ? "codex.exe" : "codex";
        private int timeoutSeconds = 300;
        private int projectCacheSeconds = 30;
        private int answerCacheSeconds = 900;
        private int integrationCacheSeconds = 18_000;
        private int overviewCacheSeconds = 18_000;
        private int overviewCacheMaxEntries = 50;
        private int answerCacheMaxEntries = 100;
        private List<Path> excludedPaths = new ArrayList<>();
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getCommand() { return command; }
        public void setCommand(String command) { this.command = command; }
        public int getTimeoutSeconds() { return timeoutSeconds; }
        public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
        public int getProjectCacheSeconds() { return projectCacheSeconds; }
        public void setProjectCacheSeconds(int projectCacheSeconds) { this.projectCacheSeconds = projectCacheSeconds; }
        public int getAnswerCacheSeconds() { return answerCacheSeconds; }
        public void setAnswerCacheSeconds(int answerCacheSeconds) { this.answerCacheSeconds = answerCacheSeconds; }
        public int getIntegrationCacheSeconds() { return integrationCacheSeconds; }
        public void setIntegrationCacheSeconds(int integrationCacheSeconds) { this.integrationCacheSeconds = integrationCacheSeconds; }
        public int getOverviewCacheSeconds() { return overviewCacheSeconds; }
        public void setOverviewCacheSeconds(int overviewCacheSeconds) { this.overviewCacheSeconds = overviewCacheSeconds; }
        public int getOverviewCacheMaxEntries() { return overviewCacheMaxEntries; }
        public void setOverviewCacheMaxEntries(int overviewCacheMaxEntries) { this.overviewCacheMaxEntries = overviewCacheMaxEntries; }
        public int getAnswerCacheMaxEntries() { return answerCacheMaxEntries; }
        public void setAnswerCacheMaxEntries(int answerCacheMaxEntries) { this.answerCacheMaxEntries = answerCacheMaxEntries; }
        public List<Path> getExcludedPaths() { return excludedPaths; }
        public void setExcludedPaths(List<Path> excludedPaths) { this.excludedPaths = excludedPaths; }
    }

}
