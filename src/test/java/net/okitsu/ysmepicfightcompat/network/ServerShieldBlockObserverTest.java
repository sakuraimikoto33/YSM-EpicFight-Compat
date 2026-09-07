package net.okitsu.ysmepicfightcompat.network;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerShieldBlockObserverTest {
    @Test
    void countsFullAndPartialSuccessfulBlocks() {
        assertTrue(ServerShieldBlockObserver.wasSuccessful(false, 8.0F));
        assertTrue(ServerShieldBlockObserver.wasSuccessful(false, 0.125F));
    }

    @Test
    void ignoresCancelledAndZeroDamageEvents() {
        assertFalse(ServerShieldBlockObserver.wasSuccessful(true, 8.0F));
        assertFalse(ServerShieldBlockObserver.wasSuccessful(false, 0.0F));
        assertFalse(ServerShieldBlockObserver.wasSuccessful(false, -1.0F));
    }

    @Test
    void ignoresNonfiniteResults() {
        assertFalse(ServerShieldBlockObserver.wasSuccessful(false, Float.NaN));
        assertFalse(ServerShieldBlockObserver.wasSuccessful(false, Float.POSITIVE_INFINITY));
        assertFalse(ServerShieldBlockObserver.wasSuccessful(false, Float.NEGATIVE_INFINITY));
    }
}
