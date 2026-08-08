package io.jenkins.plugins.gitlabregistry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * In-memory TTL caches for registry repository ids and tag lists (FIFO trim on overflow).
 */
final class RegistryCaches {

    static final long TAGS_TTL_MS = 10_000L;
    static final long REPO_TTL_MS = 10 * 60 * 1000L;
    private static final int MAX_TAGS_CACHE_ENTRIES = 200;
    private static final int MAX_REPO_CACHE_ENTRIES = 200;

    private static final ConcurrentHashMap<String, CachedRepo> REPO_CACHE = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, CachedTags> TAGS_CACHE = new ConcurrentHashMap<>();
    private static final ConcurrentLinkedQueue<String> REPO_ORDER = new ConcurrentLinkedQueue<>();
    private static final ConcurrentLinkedQueue<String> TAGS_ORDER = new ConcurrentLinkedQueue<>();

    private RegistryCaches() {
    }

    static List<String> getTags(String key) {
        evictExpired(TAGS_CACHE, TAGS_ORDER);
        CachedTags hit = TAGS_CACHE.get(key);
        if (hit == null) {
            return null;
        }
        if (hit.isExpired()) {
            TAGS_CACHE.remove(key, hit);
            return null;
        }
        return new ArrayList<>(hit.tags);
    }

    static void putTags(String key, List<String> tags) {
        evictExpired(TAGS_CACHE, TAGS_ORDER);
        TAGS_CACHE.put(key, new CachedTags(Collections.unmodifiableList(new ArrayList<>(tags))));
        TAGS_ORDER.offer(key);
        trimFifo(TAGS_CACHE, TAGS_ORDER, MAX_TAGS_CACHE_ENTRIES);
    }

    static CachedRepo getRepo(String key) {
        evictExpired(REPO_CACHE, REPO_ORDER);
        CachedRepo hit = REPO_CACHE.get(key);
        if (hit == null) {
            return null;
        }
        if (hit.isExpired()) {
            REPO_CACHE.remove(key, hit);
            return null;
        }
        return hit;
    }

    static void putRepo(String key, String repositoryId, String projectId) {
        evictExpired(REPO_CACHE, REPO_ORDER);
        REPO_CACHE.put(key, new CachedRepo(repositoryId, projectId));
        REPO_ORDER.offer(key);
        trimFifo(REPO_CACHE, REPO_ORDER, MAX_REPO_CACHE_ENTRIES);
    }

    private static <V> void evictExpired(ConcurrentHashMap<String, V> map, ConcurrentLinkedQueue<String> order) {
        long now = System.currentTimeMillis();
        for (Map.Entry<String, V> entry : map.entrySet()) {
            long expiresAt = expiresAtOf(entry.getValue());
            if (expiresAt >= 0 && expiresAt <= now) {
                map.remove(entry.getKey(), entry.getValue());
            }
        }
        // Drop stale order keys (best-effort)
        int guard = order.size();
        while (guard-- > 0) {
            String k = order.peek();
            if (k == null) {
                break;
            }
            if (!map.containsKey(k)) {
                order.poll();
            } else {
                break;
            }
        }
    }

    private static <V> void trimFifo(
            ConcurrentHashMap<String, V> map, ConcurrentLinkedQueue<String> order, int maxEntries) {
        while (map.size() > maxEntries) {
            String oldest = order.poll();
            if (oldest == null) {
                // Order drained but map still oversized — drop arbitrary keys
                if (map.isEmpty()) {
                    return;
                }
                String any = map.keys().nextElement();
                map.remove(any);
                continue;
            }
            map.remove(oldest);
        }
    }

    private static long expiresAtOf(Object v) {
        if (v instanceof CachedRepo) {
            return ((CachedRepo) v).expiresAt;
        }
        if (v instanceof CachedTags) {
            return ((CachedTags) v).expiresAt;
        }
        return -1L;
    }

    static final class CachedRepo {
        final String repositoryId;
        final String projectId;
        final long createdAt;
        final long expiresAt;

        CachedRepo(String repositoryId, String projectId) {
            this.repositoryId = repositoryId;
            this.projectId = projectId;
            this.createdAt = System.currentTimeMillis();
            this.expiresAt = this.createdAt + REPO_TTL_MS;
        }

        boolean isExpired() {
            return System.currentTimeMillis() >= expiresAt;
        }
    }

    private static final class CachedTags {
        final List<String> tags;
        final long createdAt;
        final long expiresAt;

        CachedTags(List<String> tags) {
            this.tags = tags;
            this.createdAt = System.currentTimeMillis();
            this.expiresAt = this.createdAt + TAGS_TTL_MS;
        }

        boolean isExpired() {
            return System.currentTimeMillis() >= expiresAt;
        }
    }
}
