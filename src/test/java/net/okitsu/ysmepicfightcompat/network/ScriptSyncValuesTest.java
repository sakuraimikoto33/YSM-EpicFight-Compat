package net.okitsu.ysmepicfightcompat.network;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScriptSyncValuesTest {
    @Test
    void validatesSelectedModelAndEveryPartOfTheReceivingIdentity() {
        UUID player = UUID.randomUUID();
        assertTrue(ScriptSyncValues.accepts(7, player, "maid", 10L,
                7, player, "maid", 9L));
        assertFalse(ScriptSyncValues.accepts(7, player, "maid", 10L,
                8, player, "maid", 9L));
        assertFalse(ScriptSyncValues.accepts(7, player, "maid", 10L,
                7, UUID.randomUUID(), "maid", 9L));
        assertFalse(ScriptSyncValues.accepts(7, player, "maid", 10L,
                7, player, "other", 9L));
        assertFalse(ScriptSyncValues.accepts(7, player, "maid", 10L,
                7, player, "maid", 10L));
        assertFalse(ScriptSyncValues.accepts(7, player, "maid", 9L,
                7, player, "maid", 10L));
        assertFalse(ScriptSyncValues.accepts(7, player, "maid", 0L,
                7, player, "maid", 0L));
        assertFalse(ScriptSyncValues.selectedModel("maid", null));
        assertFalse(ScriptSyncValues.selectedModel("", ""));
    }

    @Test
    void pendingEventsExpireAndNeverCrossAClockReset() {
        assertTrue(ScriptSyncValues.pendingAlive(100, 100));
        assertTrue(ScriptSyncValues.pendingAlive(100, 199));
        assertFalse(ScriptSyncValues.pendingAlive(100, 200));
        assertFalse(ScriptSyncValues.pendingAlive(100, 99));
        assertFalse(ScriptSyncValues.pendingAlive(Long.MIN_VALUE, Long.MAX_VALUE));
    }

    @Test
    void numericPayloadIsFiniteBoundedAndDefensivelyCopied() {
        double[] source = {1.0D};
        double[] copy = ScriptSyncValues.arguments(source);
        source[0] = 3.0D;
        assertEquals(1.0D, copy[0]);
        assertEquals(16, ScriptSyncValues.arguments(new double[16]).length);
        assertEquals(0, ScriptSyncValues.arguments(new double[0]).length);
        assertThrows(IllegalArgumentException.class,
                () -> ScriptSyncValues.arguments(new double[17]));
        assertThrows(IllegalArgumentException.class,
                () -> ScriptSyncValues.arguments(new double[]{Double.NaN}));
        assertThrows(IllegalArgumentException.class,
                () -> ScriptSyncValues.arguments(new double[]{Double.POSITIVE_INFINITY}));
        assertThrows(IllegalArgumentException.class,
                () -> ScriptSyncValues.modelId("x".repeat(4097)));
        assertThrows(IllegalArgumentException.class, () -> ScriptSyncValues.modelId(" "));
    }
}
