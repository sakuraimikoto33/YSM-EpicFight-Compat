package net.okitsu.ysmepicfightcompat.render;

import net.okitsu.ysmepicfightcompat.animation.MovementAnimationType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class CombatPlayerRendererTest {
    @Test
    void officialCreativeFlightYawUsesWrappedBodyInterpolation() {
        assertEquals(180.0F,
                CombatPlayerRenderer.officialBodyYaw(170.0F, -170.0F, 0.5F),
                0.0001F);
    }

    @Test
    void outerCorrectionReplacesEpicYawWithOfficialBodyYaw() {
        assertEquals(-30.0F,
                CombatPlayerRenderer.outerYawCorrection(20.0F, 50.0F),
                0.0001F);
        assertEquals(20.0F,
                CombatPlayerRenderer.outerYawCorrection(-170.0F, 170.0F),
                0.0001F);
        assertEquals(-2.0F,
                CombatPlayerRenderer.outerYawCorrection(179.0F, -179.0F),
                0.0001F);
        assertEquals(2.0F,
                CombatPlayerRenderer.outerYawCorrection(-179.0F, 179.0F),
                0.0001F);
    }

    @Test
    void outerCorrectionProducesOfficialFinalModelYaw() {
        assertFinalModelYaw(20.0F, 50.0F);
        assertFinalModelYaw(-170.0F, 170.0F);
        assertFinalModelYaw(179.0F, -179.0F);
        assertFinalModelYaw(-179.0F, 179.0F);
    }

    @Test
    void itemSwitchOwnershipUsesOfficialCreativeFlightYawIndependently() {
        assertTrue(CombatPlayerRenderer.shouldUseOfficialCreativeFlightYaw(
                MovementAnimationType.CREATIVE_FLIGHT,
                false, true, false, true));
        assertTrue(CombatPlayerRenderer.shouldUseOfficialCreativeFlightYaw(
                MovementAnimationType.CREATIVE_FLIGHT,
                true, false, false, true));
        assertFalse(CombatPlayerRenderer.shouldUseOfficialCreativeFlightYaw(
                MovementAnimationType.CREATIVE_FLIGHT,
                false, false, false, true));
        assertFalse(CombatPlayerRenderer.shouldUseOfficialCreativeFlightYaw(
                MovementAnimationType.CREATIVE_FLIGHT,
                false, true, true, true));
        assertFalse(CombatPlayerRenderer.shouldUseOfficialCreativeFlightYaw(
                MovementAnimationType.ELYTRA_FLIGHT,
                false, true, false, true));
        assertFalse(CombatPlayerRenderer.shouldUseOfficialCreativeFlightYaw(
                MovementAnimationType.CREATIVE_FLIGHT,
                false, true, false, false));
    }

    private static void assertFinalModelYaw(float epicYaw, float officialYaw) {
        float correction = CombatPlayerRenderer.outerYawCorrection(
                epicYaw, officialYaw);
        float actual = wrapDegrees(180.0F - epicYaw + correction);
        float expected = wrapDegrees(180.0F - officialYaw);
        assertEquals(expected, actual, 0.0001F);
    }

    private static float wrapDegrees(float degrees) {
        float wrapped = degrees % 360.0F;
        if (wrapped >= 180.0F) {
            wrapped -= 360.0F;
        }
        if (wrapped < -180.0F) {
            wrapped += 360.0F;
        }
        return wrapped;
    }
}
