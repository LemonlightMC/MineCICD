package com.lemonlightmc.minecicd.http;

import com.lemonlightmc.minecicd.MineCICDConfig.RateLimit;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the bounded, expiring per-IP failure limiter used by ControlServer (S-08).
 */
class RateLimiterTest {

    private static final long WINDOW_MILLIS = 10_000L;

    private static RateLimiter limiter(int maxEntries, int failureLimit) {
        return new RateLimiter(new RateLimit(true, false, failureLimit, maxEntries, WINDOW_MILLIS / 1000));
    }

    @Test
    void rateLimitsAfterFiveFailuresWithinWindow() {
        RateLimiter limiter = limiter(100, 5);
        long now = 1_000_000L;
        for (int i = 0; i < 5; i++) {
            assertFalse(limiter.isRateLimited("1.2.3.4", now));
            limiter.recordFailure("1.2.3.4", now);
        }
        assertTrue(limiter.isRateLimited("1.2.3.4", now));
    }

    @Test
    void windowExpiryResetsTheCounter() {
        RateLimiter limiter = limiter(100, 5);
        long now = 1_000_000L;
        for (int i = 0; i < 5; i++) {
            limiter.recordFailure("1.2.3.4", now);
        }
        assertTrue(limiter.isRateLimited("1.2.3.4", now));
        // after the window, the IP is no longer rate limited
        assertFalse(limiter.isRateLimited("1.2.3.4", now + WINDOW_MILLIS + 1));
    }

    @Test
    void expiredEntriesArePurged() {
        RateLimiter limiter = limiter(100, 5);
        long now = 1_000_000L;
        limiter.recordFailure("a", now);
        limiter.recordFailure("b", now);
        assertEquals(2, limiter.size());
        limiter.purgeExpired(now + WINDOW_MILLIS + 1);
        assertEquals(0, limiter.size());
    }

    @Test
    void distinctClientsCannotGrowCacheBeyondCap() {
        RateLimiter limiter = limiter(10, 5);
        long now = 1_000_000L;
        for (int i = 0; i < 100; i++) {
            limiter.recordFailure("client-" + i, now);
        }
        assertTrue(limiter.size() <= 10, "cache must stay bounded: " + limiter.size());
    }

    @Test
    void unknownIpIsHandledLikeAnyOther() {
        RateLimiter limiter = limiter(100, 5);
        long now = 1_000_000L;
        for (int i = 0; i < 5; i++) {
            limiter.recordFailure("unknown", now);
        }
        assertTrue(limiter.isRateLimited("unknown", now));
    }

    @Test
    void evictsOldestWhenFullOfFreshEntries() {
        RateLimiter limiter = limiter(2, 5);
        long now = 1_000_000L;
        limiter.recordFailure("old", now);
        limiter.recordFailure("new", now + 1);
        limiter.recordFailure("third", now + 2);
        assertEquals(2, limiter.size());
        // The oldest entry (windowStart = now) must have been evicted.
        assertFalse(limiter.isRateLimited("old", now + 2),
                "oldest entry should be evicted under the cap");
    }
}