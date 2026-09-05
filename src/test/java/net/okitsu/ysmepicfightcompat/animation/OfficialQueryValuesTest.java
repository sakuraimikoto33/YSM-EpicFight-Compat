package net.okitsu.ysmepicfightcompat.animation;

import org.junit.jupiter.api.Test;
import net.minecraft.core.BlockPos;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;

class OfficialQueryValuesTest {
    @Test
    void relativeBlockQueriesPreserveFractionalOffsetsBeforeFlooring() {
        assertEquals(new BlockPos(0, 63, 0), OfficialQueryValues.relativeBlockPosition(
                0.25, 64.0, 0.25, 0, -0.5, 0));
        assertEquals(new BlockPos(1, 64, -1), OfficialQueryValues.relativeBlockPosition(
                0.75, 64.0, 0.25, 0.5, 0, -0.5));
        assertNull(OfficialQueryValues.relativeBlockPosition(0, 0, 0, 8.1, 0, 0));
        assertNull(OfficialQueryValues.relativeBlockPosition(0, 0, 0, 0, Double.NaN, 0));
    }

    @Test
    void equipmentAndEffectQueriesSumEveryRequestedValidIdentifier() {
        Map<String, Double> levels = Map.of("minecraft:sharpness", 3.0D,
                "minecraft:unbreaking", 2.0D);
        assertEquals(5.0D, OfficialQueryValues.sumLevels(new String[]{"mainhand",
                "minecraft:sharpness", "minecraft:unbreaking", "missing", null},
                1, id -> levels.getOrDefault(id, 0.0D)));
        assertEquals(5.0D, OfficialQueryValues.sumLevels(new String[]{
                "minecraft:sharpness", "minecraft:unbreaking"},
                0, id -> levels.getOrDefault(id, 0.0D)));
        assertEquals(0.0D, OfficialQueryValues.sumLevels(new String[]{"unknown"},
                0, id -> Double.NaN));
        assertEquals(0.0D, OfficialQueryValues.sumLevels(null, 0, id -> 1));
    }

    @Test
    void clientInputNeverLeaksIntoRemoteEntitiesOrInactiveWindows() {
        assertTrue(OfficialQueryValues.permitsLocalInput(true, true, true, false));
        assertFalse(OfficialQueryValues.permitsLocalInput(false, true, true, false));
        assertFalse(OfficialQueryValues.permitsLocalInput(true, false, true, false));
        assertFalse(OfficialQueryValues.permitsLocalInput(true, true, false, false));
        assertFalse(OfficialQueryValues.permitsLocalInput(true, true, true, true));
    }

    @Test
    void inputValidationRejectsFractionalUnknownAndOutOfRangeNativeCodes() {
        assertTrue(OfficialQueryValues.mouseKey(0));
        assertTrue(OfficialQueryValues.mouseKey(7));
        assertFalse(OfficialQueryValues.mouseKey(8));
        assertFalse(OfficialQueryValues.mouseKey(-1));
        assertFalse(OfficialQueryValues.mouseKey(0.1));
        assertFalse(OfficialQueryValues.mouseKey(Double.NaN));
        for (int key : new int[]{32, 65, 87, 256, 262, 314, 336, 348}) {
            assertTrue(OfficialQueryValues.keyboardKey(key));
        }
        for (int key : new int[]{-1, 0, 33, 58, 64, 94, 255, 270, 285, 315, 337, 349}) {
            assertFalse(OfficialQueryValues.keyboardKey(key));
        }
        assertFalse(OfficialQueryValues.keyboardKey(65.5));
        assertFalse(OfficialQueryValues.keyboardKey(Double.POSITIVE_INFINITY));
    }
}
