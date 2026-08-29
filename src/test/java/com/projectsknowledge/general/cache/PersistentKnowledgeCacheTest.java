package com.projectsknowledge.general.cache;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PersistentKnowledgeCacheTest {

    @TempDir
    Path root;

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final Instant now = Instant.parse("2026-08-29T06:00:00Z");
    private Path database;
    private PersistentKnowledgeCache cache;

    @BeforeEach
    void setUp() {
        database = root.resolve("nested/cache/knowledge.db");
        cache = new PersistentKnowledgeCache(mapper, database, true);
        cache.initialize();
    }

    @Test
    void survivesRestartAndKeepsJsonDates() {
        var value = new CacheValue("answer", now);
        cache.put("answer-v1", "secret project key", value, now, now.plusSeconds(300), 10);
        assertThat(Files.isRegularFile(database)).isTrue();

        var restarted = new PersistentKnowledgeCache(mapper, database, true);
        restarted.initialize();
        assertThat(restarted.find("answer-v1", "secret project key", CacheValue.class, now.plusSeconds(1)))
            .contains(value);
    }

    @Test
    void expiresEntriesWithoutSlidingLifetime() {
        cache.put("answer-v1", "key", new CacheValue("answer", now), now, now.plusSeconds(10), 10);
        assertThat(cache.find("answer-v1", "key", CacheValue.class, now.plusSeconds(9))).isPresent();
        assertThat(cache.find("answer-v1", "key", CacheValue.class, now.plusSeconds(10))).isEmpty();
    }

    @Test
    void boundsEachNamespaceIndependentlyByRecentAccess() {
        cache.put("answer-v1", "old", new CacheValue("old", now), now, now.plusSeconds(300), 2);
        cache.put("answer-v1", "kept", new CacheValue("kept", now), now, now.plusSeconds(300), 2);
        assertThat(cache.find("answer-v1", "old", CacheValue.class, now.plusSeconds(1))).isPresent();
        cache.put(
            "answer-v1",
            "new",
            new CacheValue("new", now.plusSeconds(2)),
            now.plusSeconds(2),
            now.plusSeconds(300),
            2
        );
        cache.put("overview-v1", "overview", new CacheValue("overview", now), now, now.plusSeconds(300), 1);

        assertThat(cache.find("answer-v1", "kept", CacheValue.class, now.plusSeconds(3))).isEmpty();
        assertThat(cache.find("answer-v1", "old", CacheValue.class, now.plusSeconds(3))).isPresent();
        assertThat(cache.find("answer-v1", "new", CacheValue.class, now.plusSeconds(3))).isPresent();
        assertThat(cache.find("overview-v1", "overview", CacheValue.class, now.plusSeconds(3))).isPresent();
    }

    @Test
    void clearsEveryPersistentNamespace() {
        cache.put("answer-v1", "answer", new CacheValue("answer", now), now, now.plusSeconds(300), 10);
        cache.put("overview-v1", "overview", new CacheValue("overview", now), now, now.plusSeconds(300), 10);

        cache.clearCache();

        assertThat(cache.find("answer-v1", "answer", CacheValue.class, now.plusSeconds(1))).isEmpty();
        assertThat(cache.find("overview-v1", "overview", CacheValue.class, now.plusSeconds(1))).isEmpty();
    }

    record CacheValue(String text, Instant createdAt) {}
}
