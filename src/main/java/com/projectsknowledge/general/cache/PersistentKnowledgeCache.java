package com.projectsknowledge.general.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.projectsknowledge.general.config.ProjectsKnowledgeProperties;
import com.projectsknowledge.general.util.Sha256;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Local SQLite storage for successful, bounded Codex results. Failures degrade to memory-only caching. */
@Component
@Slf4j
public class PersistentKnowledgeCache implements CacheClearable {

    private final ObjectMapper mapper;
    private final Path databasePath;
    private final boolean configured;
    private volatile boolean available;

    @Autowired
    public PersistentKnowledgeCache(ObjectMapper mapper, ProjectsKnowledgeProperties properties) {
        this(mapper, properties.getStorage().getPersistentCachePath(), properties.getStorage().isPersistentCacheEnabled());
    }

    public PersistentKnowledgeCache(ObjectMapper mapper, Path databasePath, boolean configured) {
        this.mapper = mapper;
        this.databasePath = databasePath.toAbsolutePath().normalize();
        this.configured = configured;
    }

    public static PersistentKnowledgeCache disabled() {
        return new PersistentKnowledgeCache(null, Path.of("disabled-cache.db"), false);
    }

    @PostConstruct
    public synchronized void initialize() {
        if (!configured) return;
        try {
            Path parent = databasePath.getParent();
            if (parent != null) Files.createDirectories(parent);
            try (Connection connection = connection(); Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA journal_mode=WAL");
                statement.execute("PRAGMA synchronous=NORMAL");
                statement.execute(
                    """
                    CREATE TABLE IF NOT EXISTS knowledge_cache (
                      namespace TEXT NOT NULL,
                      cache_key TEXT NOT NULL,
                      response_json TEXT NOT NULL,
                      updated_at TEXT NOT NULL,
                      expires_at TEXT NOT NULL,
                      last_accessed_at TEXT NOT NULL,
                      PRIMARY KEY (namespace, cache_key)
                    )
                    """
                );
                statement.execute(
                    "CREATE INDEX IF NOT EXISTS idx_knowledge_cache_expiry ON knowledge_cache(expires_at)"
                );
            }
            available = true;
            log.info("Persistent cache ready: {}", databasePath);
        } catch (IOException | SQLException exception) {
            available = false;
            log.warn("Persistent cache is unavailable; continuing with memory-only caching: {}", exception.getMessage());
        }
    }

    public synchronized <T> Optional<T> find(String namespace, String key, Class<T> type, Instant now) {
        if (!available) return Optional.empty();
        String hash = Sha256.hash(key);
        String sql = "SELECT response_json, expires_at FROM knowledge_cache WHERE namespace = ? AND cache_key = ?";
        try (Connection connection = connection()) {
            String json;
            Instant expiresAt;
            try (PreparedStatement query = connection.prepareStatement(sql)) {
                query.setString(1, namespace);
                query.setString(2, hash);
                try (ResultSet result = query.executeQuery()) {
                    if (!result.next()) return Optional.empty();
                    json = result.getString("response_json");
                    expiresAt = Instant.parse(result.getString("expires_at"));
                }
            }
            if (!now.isBefore(expiresAt)) {
                delete(connection, namespace, hash);
                return Optional.empty();
            }
            T value = mapper.readValue(json, type);
            touch(connection, namespace, hash, now);
            return Optional.of(value);
        } catch (IOException | SQLException | RuntimeException exception) {
            log.warn("Could not read persistent cache entry: {}", exception.getMessage());
            return Optional.empty();
        }
    }

    public synchronized void put(
        String namespace,
        String key,
        Object value,
        Instant updatedAt,
        Instant expiresAt,
        int maxEntries
    ) {
        if (!available || maxEntries <= 0 || expiresAt == null) return;
        String sql =
            """
            INSERT INTO knowledge_cache(namespace, cache_key, response_json, updated_at, expires_at, last_accessed_at)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT(namespace, cache_key) DO UPDATE SET
              response_json = excluded.response_json,
              updated_at = excluded.updated_at,
              expires_at = excluded.expires_at,
              last_accessed_at = excluded.last_accessed_at
            """;
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, namespace);
                statement.setString(2, Sha256.hash(key));
                statement.setString(3, mapper.writeValueAsString(value));
                statement.setString(4, updatedAt.toString());
                statement.setString(5, expiresAt.toString());
                statement.setString(6, updatedAt.toString());
                statement.executeUpdate();
            }
            trim(connection, namespace, maxEntries);
            connection.commit();
        } catch (IOException | SQLException | RuntimeException exception) {
            log.warn("Could not write persistent cache entry: {}", exception.getMessage());
        }
    }

    @Override
    public synchronized void clearCache() {
        if (!available) return;
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM knowledge_cache");
        } catch (SQLException exception) {
            log.warn("Could not clear persistent cache: {}", exception.getMessage());
        }
    }

    private Connection connection() throws SQLException {
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath);
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA busy_timeout=5000");
        }
        return connection;
    }

    private void touch(Connection connection, String namespace, String key, Instant now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "UPDATE knowledge_cache SET last_accessed_at = ? WHERE namespace = ? AND cache_key = ?"
        )) {
            statement.setString(1, now.toString());
            statement.setString(2, namespace);
            statement.setString(3, key);
            statement.executeUpdate();
        }
    }

    private void delete(Connection connection, String namespace, String key) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "DELETE FROM knowledge_cache WHERE namespace = ? AND cache_key = ?"
        )) {
            statement.setString(1, namespace);
            statement.setString(2, key);
            statement.executeUpdate();
        }
    }

    private void trim(Connection connection, String namespace, int maxEntries) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            """
            DELETE FROM knowledge_cache
            WHERE namespace = ? AND cache_key NOT IN (
              SELECT cache_key FROM knowledge_cache WHERE namespace = ?
              ORDER BY last_accessed_at DESC LIMIT ?
            )
            """
        )) {
            statement.setString(1, namespace);
            statement.setString(2, namespace);
            statement.setInt(3, maxEntries);
            statement.executeUpdate();
        }
    }

}
