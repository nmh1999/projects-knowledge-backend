package com.projectsknowledge.general.scanner;

import com.projectsknowledge.business.project.entity.Repository;
import com.projectsknowledge.general.config.ProjectsKnowledgeProperties;
import com.projectsknowledge.general.exception.KnowledgeException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Discovers safe, readable repository files and derives the lightweight metadata shown on project cards.
 * Source paths are normalized and checked again against the repository root before any file is returned.
 */
@Component
@RequiredArgsConstructor
public class RepositoryScanner {

    private static final Set<String> IGNORED_DIRECTORIES = Set.of(
        ".git",
        ".idea",
        ".vscode",
        ".angular",
        ".gradle",
        ".next",
        "node_modules",
        "target",
        "dist",
        "build",
        "coverage",
        "out",
        "vendor",
        "certificate"
    );
    private static final Set<String> IGNORED_FILES = Set.of(
        "package-lock.json",
        "yarn.lock",
        "pnpm-lock.yaml",
        "desktop.ini"
    );
    private static final Set<String> TEXT_EXTENSIONS = Set.of(
        "java",
        "kt",
        "ts",
        "tsx",
        "js",
        "jsx",
        "html",
        "scss",
        "css",
        "json",
        "xml",
        "yml",
        "yaml",
        "properties",
        "sql",
        "md",
        "gradle",
        "kts",
        "conf",
        "txt",
        "py",
        "go",
        "rs",
        "php",
        "rb",
        "cs",
        "toml",
        "mod",
        "sum",
        "csproj"
    );

    private final ProjectsKnowledgeProperties properties;
    private final Map<Path, FileSnapshot> fileCache = new ConcurrentHashMap<>();

    public List<Path> files(Repository repository) {
        int ttl = properties.getScan().getFileCacheSeconds();
        if (ttl <= 0) return discoverFiles(repository);
        Instant validAfter = Instant.now().minusSeconds(ttl);
        fileCache.entrySet().removeIf(entry -> !entry.getValue().loadedAt().isAfter(validAfter));
        Path root = repository.getPath().toAbsolutePath().normalize();
        return fileCache
            .computeIfAbsent(root, ignored -> new FileSnapshot(Instant.now(), discoverFiles(repository)))
            .files();
    }

    public void invalidateFiles(Repository repository) {
        fileCache.remove(repository.getPath().toAbsolutePath().normalize());
    }

    public List<String> readLines(Repository repository, Path file) {
        validateWithinRepository(repository, file);
        try {
            if (Files.size(file) > properties.getScan().getMaxFileBytes()) return List.of();
            return Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException exception) {
            return List.of();
        }
    }

    public Path resolveSource(Repository repository, String requestedPath) {
        if (requestedPath == null || requestedPath.isBlank()) {
            throw new KnowledgeException(HttpStatus.BAD_REQUEST, "A source file path is required.");
        }
        Path root = repository.getPath().toAbsolutePath().normalize();
        Path resolved = root.resolve(requestedPath).normalize();
        if (!resolved.startsWith(root)) {
            throw new KnowledgeException(
                HttpStatus.BAD_REQUEST,
                "The requested source path is outside the repository."
            );
        }
        if (!Files.isRegularFile(resolved) || isIgnored(resolved, root)) {
            throw new KnowledgeException(HttpStatus.NOT_FOUND, "Source file not found.");
        }
        try {
            Path realRoot = root.toRealPath();
            Path realFile = resolved.toRealPath();
            if (!realFile.startsWith(realRoot)) {
                throw new KnowledgeException(
                    HttpStatus.BAD_REQUEST,
                    "The requested source path is outside the repository."
                );
            }
            return realFile;
        } catch (IOException exception) {
            throw new KnowledgeException(HttpStatus.NOT_FOUND, "Source file not found.");
        }
    }

    private void validateWithinRepository(Repository repository, Path file) {
        Path root = repository.getPath().toAbsolutePath().normalize();
        Path resolved = file.toAbsolutePath().normalize();
        if (!resolved.startsWith(root)) {
            throw new KnowledgeException(
                HttpStatus.BAD_REQUEST,
                "The requested source path is outside the repository."
            );
        }
    }

    public RepositoryMetadata metadata(Repository repository) {
        Path root = repository.getPath().toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) return RepositoryMetadata.unavailable();
        List<Path> files = files(repository);
        Set<String> languages = new TreeSet<>();
        Set<String> frameworks = new TreeSet<>();
        Set<String> buildTools = new TreeSet<>();
        Set<String> databases = new TreeSet<>();
        Set<String> messaging = new TreeSet<>();
        Set<String> jobs = new TreeSet<>();

        for (Path file : files) {
            String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
            languageFor(name).ifPresent(languages::add);
            if (name.equals("pom.xml")) buildTools.add("Maven");
            if (name.equals("build.gradle") || name.equals("build.gradle.kts")) buildTools.add("Gradle");
            if (name.equals("angular.json") || name.equals("package.json")) buildTools.add("npm");
            if (name.endsWith("scheduler.java") || name.endsWith("job.java")) jobs.add(displayName(file));

            if (isManifest(name)) {
                String content = String.join("\n", readLines(repository, file)).toLowerCase(Locale.ROOT);
                detect(
                    content,
                    frameworks,
                    Map.ofEntries(
                        Map.entry("spring-boot", "Spring Boot"),
                        Map.entry("@angular/core", "Angular"),
                        Map.entry("\"next\"", "Next.js"),
                        Map.entry("\"react\"", "React"),
                        Map.entry("primeng", "PrimeNG"),
                        Map.entry("hibernate", "Hibernate/JPA"),
                        Map.entry("flyway", "Flyway"),
                        Map.entry("liquibase", "Liquibase"),
                        Map.entry("openfeign", "OpenFeign"),
                        Map.entry("shedlock", "ShedLock"),
                        Map.entry("redis", "Redis")
                    )
                );
                detect(content, databases, Map.of("oracle", "Oracle", "mssql", "SQL Server", "h2database", "H2"));
                detect(
                    content,
                    messaging,
                    Map.of("starter-amqp", "RabbitMQ/AMQP", "qpid-jms", "JMS/Qpid", "kafka", "Kafka")
                );
            }
        }
        return new RepositoryMetadata(
            true,
            List.copyOf(languages),
            List.copyOf(frameworks),
            List.copyOf(buildTools),
            RepositoryStructure.modules(root, files),
            List.copyOf(databases),
            RepositoryStructure.integrations(root, files),
            List.copyOf(messaging),
            jobs.stream().limit(20).toList()
        );
    }

    private List<Path> discoverFiles(Repository repository) {
        Path root = repository.getPath().toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) return List.of();
        try (Stream<Path> stream = Files.walk(root)) {
            return stream
                .filter(Files::isRegularFile)
                .filter(file -> !Files.isSymbolicLink(file))
                .filter(file -> !isIgnored(file, root))
                .filter(this::isTextFile)
                .filter(this::sizeAllowed)
                .toList();
        } catch (IOException exception) {
            throw new KnowledgeException(HttpStatus.SERVICE_UNAVAILABLE, "Repository is not available.");
        }
    }

    private boolean isIgnored(Path file, Path root) {
        Path relative = root.relativize(file);
        for (Path part : relative)
            if (IGNORED_DIRECTORIES.contains(part.toString().toLowerCase(Locale.ROOT))) return true;
        return IGNORED_FILES.contains(file.getFileName().toString().toLowerCase(Locale.ROOT));
    }

    private boolean isTextFile(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (Set.of("dockerfile", "mvnw", "gradlew").contains(name)) return true;
        int dot = name.lastIndexOf('.');
        return dot >= 0 && TEXT_EXTENSIONS.contains(name.substring(dot + 1));
    }

    private boolean sizeAllowed(Path file) {
        try {
            return Files.size(file) <= properties.getScan().getMaxFileBytes();
        } catch (IOException exception) {
            return false;
        }
    }

    private Optional<String> languageFor(String name) {
        if (name.endsWith(".java")) return Optional.of("Java");
        if (name.endsWith(".ts") || name.endsWith(".tsx")) return Optional.of("TypeScript");
        if (name.endsWith(".js") || name.endsWith(".jsx")) return Optional.of("JavaScript");
        if (name.endsWith(".sql")) return Optional.of("SQL");
        if (name.endsWith(".kt") || name.endsWith(".kts")) return Optional.of("Kotlin");
        if (name.endsWith(".py")) return Optional.of("Python");
        if (name.endsWith(".go")) return Optional.of("Go");
        if (name.endsWith(".rs")) return Optional.of("Rust");
        if (name.endsWith(".php")) return Optional.of("PHP");
        if (name.endsWith(".rb")) return Optional.of("Ruby");
        if (name.endsWith(".cs")) return Optional.of("C#");
        return Optional.empty();
    }

    private boolean isManifest(String name) {
        return (
            name.equals("pom.xml") ||
            name.equals("build.gradle") ||
            name.equals("build.gradle.kts") ||
            name.equals("package.json")
        );
    }

    private void detect(String content, Set<String> target, Map<String, String> keywords) {
        keywords.forEach((keyword, label) -> {
            if (content.contains(keyword)) target.add(label);
        });
    }

    private String displayName(Path file) {
        String name = file.getFileName().toString();
        return name.substring(0, name.lastIndexOf('.'));
    }

    public record RepositoryMetadata(
        boolean available,
        List<String> languages,
        List<String> frameworks,
        List<String> buildTools,
        List<String> domains,
        List<String> databases,
        List<String> integrations,
        List<String> messaging,
        List<String> scheduledJobs
    ) {
        static RepositoryMetadata unavailable() {
            return new RepositoryMetadata(
                false,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()
            );
        }
    }

    private record FileSnapshot(Instant loadedAt, List<Path> files) {}
}
