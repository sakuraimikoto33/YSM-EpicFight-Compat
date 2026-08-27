package net.okitsu.ysmepicfightcompat.network.geometry;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelTransferRateLimiterTest {
    @Test
    void boundsRequestsPerRecipientAndResetsAtWindowBoundary() {
        ModelTransferRateLimiter limiter = new ModelTransferRateLimiter(100L, 2L, 10L);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();

        assertTrue(limiter.allowRequest(first, 10L));
        assertTrue(limiter.allowRequest(first, 11L));
        assertFalse(limiter.allowRequest(first, 12L));
        assertTrue(limiter.allowRequest(second, 12L));
        assertTrue(limiter.allowRequest(first, 110L));
    }

    @Test
    void boundsTransferredBytesAndDisconnectRemovalDropsBothWindows() {
        ModelTransferRateLimiter limiter = new ModelTransferRateLimiter(100L, 2L, 10L);
        UUID recipient = UUID.randomUUID();

        assertTrue(limiter.allowData(recipient, 5L, 6L));
        assertTrue(limiter.allowData(recipient, 6L, 4L));
        assertFalse(limiter.allowData(recipient, 7L, 1L));
        assertFalse(limiter.allowData(recipient, 7L, 11L));
        assertTrue(limiter.allowRequest(recipient, 7L));

        limiter.remove(recipient);
        assertTrue(limiter.allowData(recipient, 8L, 10L));
        assertTrue(limiter.allowRequest(recipient, 8L));
        limiter.clear();
        assertTrue(limiter.allowRequest(recipient, 9L));
    }
}
