package com.lemonlightmc.minecicd.http;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.lemonlightmc.minecicd.MineCICDConfig.RateLimit;

/**
 * Bounded, expiring per-IP failure counter used to rate-limit unauthenticated
 * control
 * requests (M-07/S-08). Entries older than the window are removed lazily on
 * access and
 * eagerly when the map would exceed {@code maxEntries}, so the cache cannot
 * grow without
 * bound through a stream of distinct invalid clients.
 */
final class RateLimiter {

    private final ConcurrentHashMap<String, long[]> counts = new ConcurrentHashMap<>();
    private final int maxEntries;
    private final long windowMillis;
    private final int failureLimit;
    private final boolean failuresOnly;

    public RateLimiter(final RateLimit config) {
        this.maxEntries = config.maxEntries();
        this.windowMillis = config.windowSeconds() * 1000;
        this.failureLimit = config.failureLimit();
        this.failuresOnly = config.failuresOnly();
    }

    public int size() {
        return counts.size();
    }

    /**
     * @return true when the IP has exceeded the allowed failures within the
     *         current window
     */
    public boolean isRateLimited(final String ip, final long now) {
        final long[] entry = counts.get(ip);
        if (entry == null) {
            return false;
        }
        if (now - entry[1] < windowMillis) {
            return entry[0] >= failureLimit;
        }
        // Window expired: drop the entry so a legitimate client can recover.
        counts.remove(ip);
        return false;
    }

    /**
     * Records an attempt for the given IP, enforcing the hard entry cap.
     * When the map is full, expired entries are purged first; if still full, the
     * oldest entry is evicted so the cache size stays bounded.
     */
    public void recordRequest(final String ip, final long now) {
        if (failuresOnly) {
            return;
        }
        counts.compute(ip, (k, v) -> {
            if (v == null || now - v[1] >= windowMillis) {
                return new long[] { 1, now };
            }
            v[0]++;
            return v;
        });
        if (counts.size() > maxEntries) {
            purgeExpired(now);
        }
        if (counts.size() > maxEntries) {
            evictOldest();
        }
    }

    /**
     * Records a failed attempt for the given IP, enforcing the hard entry cap.
     * When the map is full, expired entries are purged first; if still full, the
     * oldest entry is evicted so the cache size stays bounded.
     */
    public void recordFailure(final String ip, final long now) {
        counts.compute(ip, (k, v) -> {
            if (v == null || now - v[1] >= windowMillis) {
                return new long[] { 1, now };
            }
            v[0]++;
            return v;
        });
        if (counts.size() > maxEntries) {
            purgeExpired(now);
        }
        if (counts.size() > maxEntries) {
            evictOldest();
        }
    }

    public void purgeExpired(final long now) {
        for (final Map.Entry<String, long[]> entry : counts.entrySet()) {
            if (now - entry.getValue()[1] >= windowMillis) {
                counts.remove(entry.getKey());
            }
        }
    }

    private void evictOldest() {
        final int amount = Math.max(0, counts.size() - this.maxEntries);
        for (int i = 0; i < amount; i++) {
            String oldestKey = null;
            long oldestStart = Long.MAX_VALUE;
            for (final Map.Entry<String, long[]> entry : counts.entrySet()) {
                final long start = entry.getValue()[1];
                if (start < oldestStart) {
                    oldestStart = start;
                    oldestKey = entry.getKey();
                }
            }
            if (oldestKey != null) {
                counts.remove(oldestKey);
            }
        }
    }
}