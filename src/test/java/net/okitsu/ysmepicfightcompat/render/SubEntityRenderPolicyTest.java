package net.okitsu.ysmepicfightcompat.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SubEntityRenderPolicyTest {
    @Test
    void leavesOfficialYsmAloneOutsideBattleMode() {
        assertFalse(SubEntityRenderPolicy.shouldSuppress(false, false, false));
        assertFalse(SubEntityRenderPolicy.shouldSuppress(false, true, false));
        assertFalse(SubEntityRenderPolicy.shouldSuppress(false, true, true));
    }

    @Test
    void battleModeFallsBackUntilOwnerDecisionAllowsYsm() {
        assertTrue(SubEntityRenderPolicy.shouldSuppress(true, false, false));
        assertTrue(SubEntityRenderPolicy.shouldSuppress(true, true, false));
        assertFalse(SubEntityRenderPolicy.shouldSuppress(true, true, true));
    }

    @Test
    void untrackedLaunchOwnerOnlyFallsBackForAMatchingSnapshot() {
        assertFalse(SubEntityRenderPolicy.shouldSuppressWithoutTrackedOwner(
                false, true, false, false));
        assertFalse(SubEntityRenderPolicy.shouldSuppressWithoutTrackedOwner(
                true, false, false, false));
        assertTrue(SubEntityRenderPolicy.shouldSuppressWithoutTrackedOwner(
                true, true, false, false));
        assertTrue(SubEntityRenderPolicy.shouldSuppressWithoutTrackedOwner(
                true, true, true, false));
        assertFalse(SubEntityRenderPolicy.shouldSuppressWithoutTrackedOwner(
                true, true, true, true));
    }

    @Test
    void vehicleWithoutAPlayerUsesItsLastServerSnapshot() {
        assertFalse(SubEntityRenderPolicy.shouldSuppressWithoutTrackedOwner(
                false, true, false, false));
        assertFalse(SubEntityRenderPolicy.shouldSuppressWithoutTrackedOwner(
                true, false, true, false));
        assertTrue(SubEntityRenderPolicy.shouldSuppressWithoutTrackedOwner(
                true, true, false, false));
        assertTrue(SubEntityRenderPolicy.shouldSuppressWithoutTrackedOwner(
                true, true, true, false));
        assertFalse(SubEntityRenderPolicy.shouldSuppressWithoutTrackedOwner(
                true, true, true, true));
    }
}
