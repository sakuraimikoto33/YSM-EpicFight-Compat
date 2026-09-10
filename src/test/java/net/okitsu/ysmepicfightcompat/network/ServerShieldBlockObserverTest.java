package net.okitsu.ysmepicfightcompat.network;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerShieldBlockObserverTest {
    @Test
    void countsFullAndPartialSuccessfulBlocks() {
        assertTrue(ServerShieldBlockObserver.wasSuccessful(false, true, 8.0F));
        assertTrue(ServerShieldBlockObserver.wasSuccessful(false, true, 0.125F));
    }

    @Test
    void ignoresCancelledAndZeroDamageEvents() {
        assertFalse(ServerShieldBlockObserver.wasSuccessful(true, true, 8.0F));
        assertFalse(ServerShieldBlockObserver.wasSuccessful(false, true, 0.0F));
        assertFalse(ServerShieldBlockObserver.wasSuccessful(false, true, -1.0F));
    }

    @Test
    void ignoresUnblockedHitsEvenWhenDamageRemainsPositive() {
        assertFalse(ServerShieldBlockObserver.wasSuccessful(false, false, 8.0F));
    }

    @Test
    void ignoresNonfiniteResults() {
        assertFalse(ServerShieldBlockObserver.wasSuccessful(false, true, Float.NaN));
        assertFalse(ServerShieldBlockObserver.wasSuccessful(false, true, Float.POSITIVE_INFINITY));
        assertFalse(ServerShieldBlockObserver.wasSuccessful(false, true, Float.NEGATIVE_INFINITY));
    }
}
