package com.projectsknowledge.general.integration.codex.client;

import com.projectsknowledge.general.config.ProjectsKnowledgeProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Starts only this application's private stdio process; never attaches to the desktop app's process. */
@Component
@RequiredArgsConstructor
public class CodexProcessFactory {

    private final ProjectsKnowledgeProperties properties;

    public Process start() throws IOException {
        return new ProcessBuilder(resolveCommand(), "app-server", "--listen", "stdio://").start();
    }

    private String resolveCommand() {
        String configured = properties.getCodex().getCommand();
        Path configuredPath = Path.of(configured);
        if (configuredPath.isAbsolute() && Files.isRegularFile(configuredPath)) return configuredPath.toString();
        String appData = System.getenv("APPDATA");
        if (appData != null && configured.toLowerCase(Locale.ROOT).startsWith("codex")) {
            Path npmPackage = Path.of(appData, "npm", "node_modules", "@openai", "codex", "node_modules");
            if (Files.isDirectory(npmPackage)) {
                try (Stream<Path> candidates = Files.walk(npmPackage, 8)) {
                    Optional<Path> executable = candidates
                        .filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().equalsIgnoreCase("codex.exe"))
                        .findFirst();
                    if (executable.isPresent()) return executable.get().toString();
                } catch (IOException ignored) {}
            }
        }
        for (String directory : Optional.ofNullable(System.getenv("PATH"))
            .orElse("")
            .split(java.io.File.pathSeparator)) {
            if (directory.isBlank()) continue;
            Path candidate = Path.of(directory).resolve(configured);
            if (Files.isRegularFile(candidate)) return candidate.toString();
            if (!configured.toLowerCase(Locale.ROOT).endsWith(".exe")) {
                Path executable = Path.of(directory).resolve(configured + ".exe");
                if (Files.isRegularFile(executable)) return executable.toString();
            }
        }
        return configured;
    }
}
