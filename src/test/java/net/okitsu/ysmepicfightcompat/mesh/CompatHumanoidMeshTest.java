package net.okitsu.ysmepicfightcompat.mesh;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompatHumanoidMeshTest {
    @Test
    void computeSkinningRequiresBothTheLiveSettingAndPreparedGpuState() {
        assertFalse(CompatHumanoidMesh.usesComputeSkinning(false, false, true));
        assertFalse(CompatHumanoidMesh.usesComputeSkinning(false, true, true));
        assertFalse(CompatHumanoidMesh.usesComputeSkinning(true, false, true));
        assertTrue(CompatHumanoidMesh.usesComputeSkinning(true, true, true));
    }

    @Test
    void aPreparedMeshFollowsOnOffOnWithoutRecreatingItsGpuState() {
        boolean prepared = true;
        assertTrue(CompatHumanoidMesh.usesComputeSkinning(true, prepared, true));
        assertFalse(CompatHumanoidMesh.usesComputeSkinning(false, prepared, true));
        assertTrue(CompatHumanoidMesh.usesComputeSkinning(true, prepared, true));
    }

    @Test
    void computeCapacityUsesActualPosesAndAllowsMoreThan256Parts() {
        assertTrue(CompatHumanoidMesh.withinComputePoseCapacity(556, 443, 0));
        assertTrue(CompatHumanoidMesh.withinComputePoseCapacity(557, 443, 0));
        assertFalse(CompatHumanoidMesh.withinComputePoseCapacity(558, 443, 0));
        assertFalse(CompatHumanoidMesh.withinComputePoseCapacity(900, 101, 0));
    }

    @Test
    void baseAndGlowEachHaveAPaletteButShareTheFallbackDecision() {
        assertTrue(CompatHumanoidMesh.withinComputePoseCapacity(500, 300, 300));
        assertTrue(CompatHumanoidMesh.withinComputePoseCapacity(999, 0, 1));
        for (int[] parts : new int[][]{{399, 401}, {401, 399}}) {
            boolean capacity = CompatHumanoidMesh.withinComputePoseCapacity(
                    600, parts[0], parts[1]);
            assertFalse(capacity);
            assertFalse(CompatHumanoidMesh.usesComputeSkinning(true, true, capacity));
            assertFalse(CompatHumanoidMesh.usesComputeSkinning(true, false, capacity));
        }
    }

    @Test
    void computeCapacityRejectsInvalidCountsWithoutOverflow() {
        assertTrue(CompatHumanoidMesh.withinComputePoseCapacity(1000, 0, 0));
        assertFalse(CompatHumanoidMesh.withinComputePoseCapacity(1001, 0, 0));
        assertFalse(CompatHumanoidMesh.withinComputePoseCapacity(-1, 0, 0));
        assertFalse(CompatHumanoidMesh.withinComputePoseCapacity(0, -1, 0));
        assertFalse(CompatHumanoidMesh.withinComputePoseCapacity(0, 0, -1));
        assertFalse(CompatHumanoidMesh.withinComputePoseCapacity(Integer.MAX_VALUE, 1, 0));
        assertFalse(CompatHumanoidMesh.withinComputePoseCapacity(1, Integer.MAX_VALUE, 0));
        assertFalse(CompatHumanoidMesh.withinComputePoseCapacity(1, 0, Integer.MAX_VALUE));
    }

    @Test
    void nonpersistentComputeCapsEachSubmeshAt256VisibilityBits() {
        for (boolean irisCompute : new boolean[]{false, true}) {
            assertTrue(CompatHumanoidMesh.withinComputeCapacity(
                    500, 256, 256, false, irisCompute));
            for (int[] parts : new int[][]{{257, 0}, {0, 257}, {256, 257}, {257, 256}}) {
                boolean capacity = CompatHumanoidMesh.withinComputeCapacity(
                        500, parts[0], parts[1], false, irisCompute);
                assertFalse(capacity);
                assertFalse(CompatHumanoidMesh.usesComputeSkinning(true, true, capacity));
            }
        }
    }

    @Test
    void largeVanillaMeshesFollowLivePersistentMappingChangesWithoutLosingGpuState() {
        for (boolean persistentMapping : new boolean[]{true, false, true}) {
            boolean capacity = CompatHumanoidMesh.withinComputeCapacity(
                    557, 443, 300, persistentMapping, false);
            if (persistentMapping) {
                assertTrue(CompatHumanoidMesh.usesComputeSkinning(true, true, capacity));
            } else {
                assertFalse(CompatHumanoidMesh.usesComputeSkinning(true, true, capacity));
            }
        }
    }

    @Test
    void irisPersistentComputeStillBindsOnly256VisibilityBits() {
        assertTrue(CompatHumanoidMesh.withinComputeCapacity(500, 256, 256, true, true));
        for (int[] parts : new int[][]{{257, 0}, {0, 257}, {256, 257}, {257, 256}}) {
            boolean capacity = CompatHumanoidMesh.withinComputeCapacity(
                    500, parts[0], parts[1], true, true);
            assertFalse(capacity);
            assertFalse(CompatHumanoidMesh.usesComputeSkinning(true, true, capacity));
        }
    }

    @Test
    void persistentMappingStillRespectsTheSharedPosePalette() {
        assertTrue(CompatHumanoidMesh.withinComputeCapacity(557, 443, 0, true, false));
        assertFalse(CompatHumanoidMesh.withinComputeCapacity(558, 443, 0, true, false));
        assertFalse(CompatHumanoidMesh.withinComputeCapacity(558, 0, 443, true, false));
        for (boolean persistentMapping : new boolean[]{true, false}) {
            assertFalse(CompatHumanoidMesh.withinComputeCapacity(
                    Integer.MAX_VALUE, 1, 0, persistentMapping, false));
            assertFalse(CompatHumanoidMesh.withinComputeCapacity(
                    1, Integer.MAX_VALUE, 0, persistentMapping, false));
            assertFalse(CompatHumanoidMesh.withinComputeCapacity(
                    1, 0, Integer.MAX_VALUE, persistentMapping, false));
            assertFalse(CompatHumanoidMesh.withinComputeCapacity(
                    -1, 0, 0, persistentMapping, false));
            assertFalse(CompatHumanoidMesh.withinComputeCapacity(
                    0, -1, 0, persistentMapping, false));
            assertFalse(CompatHumanoidMesh.withinComputeCapacity(
                    0, 0, -1, persistentMapping, false));
        }
    }

    @Test
    void firstPersonRebasesOnlyACustomFullBodyBowOrItsEndingSource() {
        assertTrue(CompatHumanoidMesh.usesFirstPersonPoseTransform(true, true, false));
        assertTrue(CompatHumanoidMesh.usesFirstPersonPoseTransform(true, false, true));
        assertTrue(CompatHumanoidMesh.usesFirstPersonPoseTransform(true, true, true));
        assertFalse(CompatHumanoidMesh.usesFirstPersonPoseTransform(true, false, false));
        for (boolean fullBody : new boolean[]{false, true}) {
            for (boolean ending : new boolean[]{false, true}) {
                assertFalse(CompatHumanoidMesh.usesFirstPersonPoseTransform(false, fullBody, ending));
            }
        }
    }

    @Test
    void ordinaryBowMainhandSwitchUsesEpicFightsOffArmTool() {
        Set<InteractionHand> main = Set.of(InteractionHand.MAIN_HAND);

        assertFalse(CompatHumanoidMesh.ownsItemSwitchTool(
                main, HumanoidRig.RIGHT_TOOL, HumanoidArm.RIGHT, true));
        assertTrue(CompatHumanoidMesh.ownsItemSwitchTool(
                main, HumanoidRig.LEFT_TOOL, HumanoidArm.RIGHT, true));
        assertTrue(CompatHumanoidMesh.ownsItemSwitchTool(
                main, HumanoidRig.RIGHT_TOOL, HumanoidArm.LEFT, true));
        assertFalse(CompatHumanoidMesh.ownsItemSwitchTool(
                main, HumanoidRig.LEFT_TOOL, HumanoidArm.LEFT, true));
    }

    @Test
    void ordinaryMainhandSwitchUsesTheMainArmTool() {
        Set<InteractionHand> main = Set.of(InteractionHand.MAIN_HAND);

        assertTrue(CompatHumanoidMesh.ownsItemSwitchTool(
                main, HumanoidRig.RIGHT_TOOL, HumanoidArm.RIGHT, false));
        assertFalse(CompatHumanoidMesh.ownsItemSwitchTool(
                main, HumanoidRig.LEFT_TOOL, HumanoidArm.RIGHT, false));
        assertFalse(CompatHumanoidMesh.ownsItemSwitchTool(
                main, HumanoidRig.RIGHT_TOOL, HumanoidArm.LEFT, false));
        assertTrue(CompatHumanoidMesh.ownsItemSwitchTool(
                main, HumanoidRig.LEFT_TOOL, HumanoidArm.LEFT, false));
    }

    @Test
    void projectsDisplayedAttachmentsForYsmOwnedFullBodyActions() {
        assertTrue(CompatHumanoidMesh.projectsDisplayedAttachments(false, false));
        assertFalse(CompatHumanoidMesh.projectsDisplayedAttachments(true, false));
        assertTrue(CompatHumanoidMesh.projectsDisplayedAttachments(true, true));
    }
}
