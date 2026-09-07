package net.okitsu.ysmepicfightcompat.animation;

import org.junit.jupiter.api.Test;

import java.lang.ref.ReferenceQueue;
import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShieldBlockCooldownTest {
    @Test
    void noSuccessfulNotificationMeansNoCooldown() {
        ShieldBlockCooldown window = new ShieldBlockCooldown();

        assertFalse(window.inCooldown(0));
        assertFalse(window.inCooldown(40));
        assertFalse(window.inCooldown(Integer.MAX_VALUE));
    }

    @Test
    void isActiveForAgesZeroThroughFourAndExpiresAtFive() {
        ShieldBlockCooldown window = new ShieldBlockCooldown();
        window.refresh(40);

        for (int tick = 40; tick < 45; tick++) {
            assertTrue(window.inCooldown(tick));
        }
        assertFalse(window.inCooldown(45));
        assertFalse(window.inCooldown(46));
    }

    @Test
    void repeatedRenderReadsDoNotConsumeOrExtendTheWindow() {
        ShieldBlockCooldown window = new ShieldBlockCooldown();
        window.refresh(0);

        for (int read = 0; read < 100; read++) {
            assertTrue(window.inCooldown(0));
        }
        for (int read = 0; read < 100; read++) {
            assertTrue(window.inCooldown(4));
        }
        assertFalse(window.inCooldown(5));
    }

    @Test
    void anotherSuccessfulBlockRefreshesTheFiveTickWindow() {
        ShieldBlockCooldown window = new ShieldBlockCooldown();
        window.refresh(10);
        assertTrue(window.inCooldown(13));
        window.refresh(13);

        assertTrue(window.inCooldown(14));
        assertTrue(window.inCooldown(17));
        assertFalse(window.inCooldown(18));
        window.refresh(18);
        assertTrue(window.inCooldown(18));
    }

    @Test
    void aRewoundClockInvalidatesTheOldNotificationUntilAnotherSuccess() {
        ShieldBlockCooldown window = new ShieldBlockCooldown();
        window.refresh(20);

        assertFalse(window.inCooldown(19));
        assertFalse(window.inCooldown(20));
        window.refresh(19);
        assertTrue(window.inCooldown(19));
        assertTrue(window.inCooldown(23));
        assertFalse(window.inCooldown(24));
    }

    @Test
    void aRewindWithinTheFiveTickRangeAlsoInvalidatesTheNotification() {
        ShieldBlockCooldown window = new ShieldBlockCooldown();
        window.refresh(20);
        assertTrue(window.inCooldown(23));

        assertFalse(window.inCooldown(22));
        assertFalse(window.inCooldown(23));
        window.refresh(22);
        assertTrue(window.inCooldown(22));
    }

    @Test
    void anExpiredNotificationCannotReappearWhenTheClockMovesBackward() {
        ShieldBlockCooldown window = new ShieldBlockCooldown();
        window.refresh(30);

        assertFalse(window.inCooldown(35));
        assertFalse(window.inCooldown(34));
        assertFalse(window.inCooldown(30));
    }

    @Test
    void tickSubtractionCannotOverflowIntoAnActiveWindow() {
        ShieldBlockCooldown window = new ShieldBlockCooldown();
        window.refresh(Integer.MAX_VALUE);
        assertTrue(window.inCooldown(Integer.MAX_VALUE));
        assertFalse(window.inCooldown(Integer.MIN_VALUE));
        window.refresh(Integer.MIN_VALUE);
        assertTrue(window.inCooldown(Integer.MIN_VALUE + 4));
        assertFalse(window.inCooldown(Integer.MAX_VALUE));
    }

    @Test
    void distinctEntityInstancesWithEqualIdsKeepIndependentWindows() {
        record EntityIdentity(int id) { }
        EntityIdentity original = new EntityIdentity(9);
        EntityIdentity replacement = new EntityIdentity(9);
        assertEquals(original, replacement);
        var windows = new HashMap<WeakIdentityKey<EntityIdentity>, ShieldBlockCooldown>();
        ShieldBlockCooldown active = new ShieldBlockCooldown();
        active.refresh(10);
        windows.put(new WeakIdentityKey<>(original, null), active);
        windows.put(new WeakIdentityKey<>(replacement, null), new ShieldBlockCooldown());

        assertEquals(2, windows.size());
        assertTrue(windows.get(new WeakIdentityKey<>(original, null)).inCooldown(10));
        assertFalse(windows.get(new WeakIdentityKey<>(replacement, null)).inCooldown(10));
    }

    @Test
    void collectedEntityKeyRemovesOnlyItsOwnWindow() {
        ReferenceQueue<Object> collected = new ReferenceQueue<>();
        Object original = new Object();
        Object replacement = new Object();
        WeakIdentityKey<Object> originalKey = new WeakIdentityKey<>(original, collected);
        WeakIdentityKey<Object> replacementKey = new WeakIdentityKey<>(replacement, collected);
        var windows = new HashMap<WeakIdentityKey<Object>, ShieldBlockCooldown>();
        windows.put(originalKey, new ShieldBlockCooldown());
        ShieldBlockCooldown retained = new ShieldBlockCooldown();
        windows.put(replacementKey, retained);
        originalKey.clear();
        assertTrue(originalKey.enqueue());

        windows.remove(collected.poll());

        assertEquals(1, windows.size());
        assertSame(retained, windows.get(new WeakIdentityKey<>(replacement, null)));
    }
}
