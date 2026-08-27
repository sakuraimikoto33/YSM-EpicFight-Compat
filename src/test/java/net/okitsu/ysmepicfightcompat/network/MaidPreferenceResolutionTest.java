package net.okitsu.ysmepicfightcompat.network;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MaidPreferenceResolutionTest {
    @Test
    void movementChangesDoNotInvalidateHeldItemDecisions() {
        MaidPreferenceResolution resolution = resolved();
        HeldItemModelDisplayState held = resolution.heldItems();

        resolution.invalidateMovement();

        assertTrue(resolution.heldResolved());
        assertSame(held, resolution.heldItems());
        assertFalse(resolution.movementResolved());
        assertFalse(resolution.ysmMovement());
    }

    @Test
    void heldItemChangesDoNotInvalidateMovementDecisions() {
        MaidPreferenceResolution resolution = resolved();

        resolution.invalidateHeld();

        assertFalse(resolution.heldResolved());
        assertSame(HeldItemModelDisplayState.UNKNOWN, resolution.heldItems());
        assertTrue(resolution.movementResolved());
        assertTrue(resolution.ysmMovement());
    }

    private static MaidPreferenceResolution resolved() {
        MaidPreferenceResolution resolution = new MaidPreferenceResolution();
        resolution.resolveHeld(
                new HeldItemModelDisplayState(true, false, true, false));
        resolution.resolveMovement(true);
        assertTrue(resolution.fullyResolved());
        return resolution;
    }
}
