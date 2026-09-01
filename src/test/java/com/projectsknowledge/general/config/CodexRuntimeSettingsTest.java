package com.projectsknowledge.general.config;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CodexRuntimeSettingsTest {

    @TempDir
    Path directory;

    @Test
    void persistsASelectionAcrossApplicationRestarts() {
        Path file = directory.resolve("nested").resolve("codex-settings.json");
        var first = new CodexRuntimeSettings(new ObjectMapper(), file, "", "medium");

        first.update("gpt-test", "HIGH");
        var restarted = new CodexRuntimeSettings(new ObjectMapper(), file, "", "low");
        restarted.initialize();

        assertThat(restarted.current()).isEqualTo(new CodexRuntimeSettings.Selection("gpt-test", "high"));
    }

    @Test
    void fallsBackToConfiguredDefaultsWhenTheSavedFileIsInvalid() throws Exception {
        Path file = directory.resolve("codex-settings.json");
        java.nio.file.Files.writeString(file, "not-json");
        var settings = new CodexRuntimeSettings(new ObjectMapper(), file, "configured", "HIGH");

        settings.initialize();

        assertThat(settings.current()).isEqualTo(new CodexRuntimeSettings.Selection("configured", "high"));
    }
}
