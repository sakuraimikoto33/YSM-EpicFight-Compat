package net.okitsu.ysmepicfightcompat.network;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScriptSyncRateLimiterTest {
    @Test
    void enforcesEightEventsAcrossEveryRollingTwentyTickBoundary() {
        ScriptSyncRateLimiter limiter = new ScriptSyncRateLimiter();
        assertTrue(limiter.allow(0));
        for (int index = 0; index < 7; index++) {
            assertTrue(limiter.allow(19));
        }
        assertFalse(limiter.allow(19));
        assertTrue(limiter.allow(20));
        assertFalse(limiter.allow(20));
        assertFalse(limiter.allow(38));
        for (int index = 0; index < 7; index++) {
            assertTrue(limiter.allow(39));
        }
        assertFalse(limiter.allow(39));
    }

    @Test
    void distinctPlayersHaveIndependentWindowsAndClockResetsRecover() {
        ScriptSyncRateLimiter first = new ScriptSyncRateLimiter();
        ScriptSyncRateLimiter second = new ScriptSyncRateLimiter();
        for (int index = 0; index < 8; index++) {
            assertTrue(first.allow(100));
            assertTrue(second.allow(100));
        }
        assertFalse(first.allow(101));
        assertFalse(second.allow(101));
        assertTrue(first.allow(10));
        assertFalse(second.allow(102));
    }
}
