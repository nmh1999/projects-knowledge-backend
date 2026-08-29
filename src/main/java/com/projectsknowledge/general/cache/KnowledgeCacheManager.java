package com.projectsknowledge.general.cache;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Clears every registered memory and disk cache through one desktop-safe operation. */
@Service
@RequiredArgsConstructor
public class KnowledgeCacheManager {

    private final List<CacheClearable> caches;

    public void clearAll() {
        caches.forEach(CacheClearable::clearCache);
    }
}
