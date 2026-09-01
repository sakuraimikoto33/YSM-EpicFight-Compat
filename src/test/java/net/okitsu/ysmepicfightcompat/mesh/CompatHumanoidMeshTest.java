package net.okitsu.ysmepicfightcompat.mesh;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompatHumanoidMeshTest {
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
