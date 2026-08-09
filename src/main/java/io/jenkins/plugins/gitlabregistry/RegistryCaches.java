package io.jenkins.plugins.gitlabregistry;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * In-memory TTL caches for registry repository ids and tag lists ({@code caffeine-api}).
 */
final class RegistryCaches {

    static final long TAGS_TTL_MS = 10_000L;
    static final long REPO_TTL_MS = 10 * 60 * 1000L;
    private static final int MAX_TAGS_CACHE_ENTRIES = 200;
    private static final int MAX_REPO_CACHE_ENTRIES = 200;

    private static final Cache<String, CachedRepo> REPO_CACHE = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMillis(REPO_TTL_MS))
            .maximumSize(MAX_REPO_CACHE_ENTRIES)
            .build();

    private static final Cache<String, List<String>> TAGS_CACHE = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMillis(TAGS_TTL_MS))
            .maximumSize(MAX_TAGS_CACHE_ENTRIES)
            .build();

    private RegistryCaches() {
    }

    static List<String> getTags(String key) {
        List<String> hit = TAGS_CACHE.getIfPresent(key);
        return hit == null ? null : new ArrayList<>(hit);
    }

    static void putTags(String key, List<String> tags) {
        TAGS_CACHE.put(key, Collections.unmodifiableList(new ArrayList<>(tags)));
    }

    static CachedRepo getRepo(String key) {
        return REPO_CACHE.getIfPresent(key);
    }

    static void putRepo(String key, String repositoryId, String projectId) {
        REPO_CACHE.put(key, new CachedRepo(repositoryId, projectId));
    }

    static final class CachedRepo {
        final String repositoryId;
        final String projectId;

        CachedRepo(String repositoryId, String projectId) {
            this.repositoryId = repositoryId;
            this.projectId = projectId;
        }
    }
}
