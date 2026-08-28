package com.projectsknowledge.general.scanner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.projectsknowledge.business.project.entity.Repository;
import com.projectsknowledge.business.project.enums.RepositoryType;
import com.projectsknowledge.general.config.ProjectsKnowledgeProperties;
import com.projectsknowledge.general.exception.KnowledgeException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class RepositoryScannerTest {

    @TempDir
    Path root;

    @Test
    void ignoresBuildDirectoriesAndNeverReturnsFilesOutsideRoot() throws Exception {
        Files.createDirectories(root.resolve("src"));
        Files.createDirectories(root.resolve("target"));
        Files.writeString(root.resolve("src/Visible.java"), "class Visible {}");
        Files.writeString(root.resolve("target/Ignored.java"), "class Ignored {}");
        RepositoryScanner scanner = scanner();
        Repository repository = repository("repo", root);

        assertThat(scanner.files(repository))
            .extracting(path -> path.getFileName().toString())
            .containsExactly("Visible.java");
        assertThatThrownBy(() -> scanner.resolveSource(repository, "../../outside.txt")).isInstanceOf(
            KnowledgeException.class
        );
    }

    private RepositoryScanner scanner() {
        return new RepositoryScanner(new ProjectsKnowledgeProperties());
    }

    @Test
    void refreshesStructuralHintsWhenNewFilesAreAdded() throws Exception {
        var properties = new ProjectsKnowledgeProperties();
        properties.getScan().setFileCacheSeconds(0);
        var scanner = new RepositoryScanner(properties);
        var repository = repository("runtime", root);
        assertThat(scanner.metadata(repository).domains()).isEmpty();
        Path file = root.resolve("app/features/new-module/main.ts");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "export {};");
        assertThat(scanner.metadata(repository).domains()).containsExactly("New Module");
    }

    @Test
    void discoversArbitraryModuleAndIntegrationNamesWithoutAProjectCatalog() throws Exception {
        for (String integration : new String[] { "nebula-pay", "aurora-bridge", "custom-partner" }) {
            Path file = root.resolve("code/any/namespace/integrations/" + integration + "/Client.java");
            Files.createDirectories(file.getParent());
            Files.writeString(file, "class Client {}");
        }
        Path module = root.resolve("unusual/location/modules/inventory/Handler.java");
        Files.createDirectories(module.getParent());
        Files.writeString(module, "class Handler {}");
        var metadata = scanner().metadata(repository("metadata", root));
        assertThat(metadata.integrations()).containsExactly("Aurora Bridge", "Custom Partner", "Nebula Pay");
        assertThat(metadata.domains()).containsExactly("Inventory");
        assertThat(metadata.languages()).containsExactly("Java");
    }

    @Test
    void doesNotInventBusinessMetadataWhenThereAreNoStructuralHints() throws Exception {
        Files.writeString(root.resolve("Main.java"), "class Main {}");
        var metadata = scanner().metadata(repository("plain", root));
        assertThat(metadata.domains()).isEmpty();
        assertThat(metadata.integrations()).isEmpty();
    }

    @Test
    void discoversClientFilesButSkipsSupportFilesAndIgnoredFolders() throws Exception {
        for (String name : new String[] {
            "src/clients/OrbitClient.py",
            "src/clients/common.py",
            "src/clients/Client.py",
            "node_modules/mock/integrations/unused/Client.js",
        }) {
            Path file = root.resolve(name);
            Files.createDirectories(file.getParent());
            Files.writeString(file, "# fixture");
        }
        var metadata = scanner().metadata(repository("python", root));
        assertThat(metadata.integrations()).containsExactly("Orbit");
        assertThat(metadata.languages()).containsExactly("Python");
    }

    public static Repository repository(String id, Path path) {
        Repository repository = new Repository();
        repository.setId(id);
        repository.setName(id);
        repository.setPath(path);
        repository.setType(RepositoryType.BACKEND);
        return repository;
    }
}
