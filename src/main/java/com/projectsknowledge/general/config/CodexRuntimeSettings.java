package com.projectsknowledge.general.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.projectsknowledge.general.exception.KnowledgeException;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Keeps the user-selected Codex runtime separate from disposable answer caches. */
@Component
@Slf4j
public class CodexRuntimeSettings {

    private final ObjectMapper mapper;
    private final Path settingsPath;
    private final Selection defaults;
    private volatile Selection current;

    @Autowired
    public CodexRuntimeSettings(ObjectMapper mapper, ProjectsKnowledgeProperties properties) {
        this(
            mapper,
            properties.getStorage().getCodexSettingsPath(),
            properties.getCodex().getModel(),
            properties.getCodex().getReasoningEffort()
        );
    }

    public CodexRuntimeSettings(ObjectMapper mapper, Path settingsPath, String model, String reasoningEffort) {
        this.mapper = mapper;
        this.settingsPath = settingsPath.toAbsolutePath().normalize();
        this.defaults = selection(model, reasoningEffort);
        this.current = defaults;
    }

    @PostConstruct
    public synchronized void initialize() {
        if (!Files.isRegularFile(settingsPath)) return;
        try {
            Selection saved = mapper.readValue(settingsPath.toFile(), Selection.class);
            current = selection(saved.model(), saved.reasoningEffort());
        } catch (IOException | RuntimeException exception) {
            current = defaults;
            log.warn("Could not read Codex settings; using configured defaults: {}", exception.getMessage());
        }
    }

    public Selection current() {
        return current;
    }

    public synchronized Selection update(String model, String reasoningEffort) {
        Selection next = selection(model, reasoningEffort);
        Path temporary = null;
        try {
            Path parent = settingsPath.getParent();
            if (parent != null) Files.createDirectories(parent);
            temporary = Files.createTempFile(parent, "codex-settings-", ".json");
            mapper.writeValue(temporary.toFile(), next);
            move(temporary, settingsPath);
            current = next;
            return next;
        } catch (IOException | RuntimeException exception) {
            throw new KnowledgeException(HttpStatus.SERVICE_UNAVAILABLE, "Could not save the Codex settings.");
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // The successful move already removed the temporary file in normal operation.
                }
            }
        }
    }

    private void move(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static Selection selection(String model, String reasoningEffort) {
        String safeModel = model == null ? "" : model.strip();
        String safeEffort = reasoningEffort == null || reasoningEffort.isBlank()
            ? "medium"
            : reasoningEffort.strip().toLowerCase(Locale.ROOT);
        return new Selection(safeModel, safeEffort);
    }

    public record Selection(String model, String reasoningEffort) {}
}
