package net.okitsu.ysmepicfightcompat.mesh;

import net.okitsu.ysmepicfightcompat.geometry.GeometryDocument;
import org.junit.jupiter.api.Test;
import yesman.epicfight.api.utils.math.OpenMatrix4f;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class NonHumanoidAttachmentTest {
    @Test
    void formSwitchSelectsTheSameFrameMouthWhileTheHumanKeepsItsGrip() {
        AuxiliaryBoneLayout layout = layout();
        AuxiliaryPoseMatrices composer = new AuxiliaryPoseMatrices(layout);
        OpenMatrix4f[] complete = AuxiliaryPoseMatrices.allocate(layout.totalPoseCount());
        int mouth = layout.entryForBoneName("RightHandLocator2").poseIndex();
        complete[mouth].translate(0.25F, 0.5F, -0.75F);

        HandLocatorSelection human = composer.selectFormHandLocator(complete,
                Set.of("Animal"), HumanoidRig.RIGHT_TOOL);
        assertEquals(HandLocatorSelection.Status.VISIBLE, human.status());
        assertEquals("RightHandLocator", human.locator().bone().name());
        assertNull(composer.formHeldItemPose(complete, human));

        HandLocatorSelection animal = composer.selectFormHandLocator(complete,
                Set.of("RightArm"), HumanoidRig.RIGHT_TOOL);
        OpenMatrix4f pose = composer.formHeldItemPose(complete, animal);
        assertNotNull(pose);
        assertEquals("RightHandLocator2", animal.locator().bone().name());
        assertEquals(0.45F, pose.m30, 0.0001F);
        assertEquals(1.7F, pose.m31, 0.0001F);
        assertEquals(-1.05F, pose.m32, 0.0001F);
        assertEquals(2, pose.m00, 0.0001F);
        assertEquals(3, pose.m11, 0.0001F);
        pose.m30 = 99;
        assertEquals(0.45F, composer.formHeldItemPose(complete, animal).m30, 0.0001F);
    }

    @Test
    void distinguishesIntentionalHidingFromMissingAndAmbiguousLocators() {
        AuxiliaryBoneLayout layout = layout();
        AuxiliaryPoseMatrices composer = new AuxiliaryPoseMatrices(layout);
        OpenMatrix4f[] complete = AuxiliaryPoseMatrices.allocate(layout.totalPoseCount());

        assertEquals(HandLocatorSelection.Status.HIDDEN, composer.selectFormHandLocator(
                complete, Set.of("Root"), HumanoidRig.RIGHT_TOOL).status());
        assertEquals(HandLocatorSelection.Status.AMBIGUOUS, composer.selectFormHandLocator(
                complete, Set.of(), HumanoidRig.RIGHT_TOOL).status());
        assertEquals(HandLocatorSelection.Status.MISSING, composer.selectFormHandLocator(
                complete, Set.of(), HumanoidRig.LEFT_TOOL).status());
    }

    @Test
    void collectsOnlySupportedNumberedLocatorsWithoutTreatingAnArmAsRequired() {
        GeometryDocument geometry = new GeometryDocument();
        add(geometry, "Root", "");
        add(geometry, "Shape", "Root");
        for (int number = 1; number <= 9; number++) {
            add(geometry, "LeftHandLocator" + number, "Shape");
        }
        add(geometry, "LeftHandLocator", "Shape");
        geometry.linkHierarchy();
        AuxiliaryBoneLayout layout = AuxiliaryBoneLayout.create(geometry);
        assertEquals(8, layout.handLocatorCandidates(HumanoidRig.LEFT_TOOL).size());
        assertTrue(layout.hasAuthoredHandLocators(HumanoidRig.LEFT_TOOL));
        assertTrue(layout.handLocatorCandidates(HumanoidRig.LEFT_TOOL).stream()
                .noneMatch(entry -> entry.bone().name().equals("LeftHandLocator1")
                        || entry.bone().name().equals("LeftHandLocator9")));
    }

    @Test
    void aNormalHumanoidWithoutAuthoredBranchesRetainsItsExistingSelectionPath() {
        GeometryDocument geometry = new GeometryDocument();
        add(geometry, "RightArm", "");
        add(geometry, "RightHand", "RightArm");
        add(geometry, "RightHandLocator", "RightHand");
        geometry.linkHierarchy();
        AuxiliaryBoneLayout layout = AuxiliaryBoneLayout.create(geometry);
        assertFalse(layout.hasAuthoredHandLocators(HumanoidRig.RIGHT_TOOL));
        AuxiliaryPoseMatrices composer = new AuxiliaryPoseMatrices(layout);
        assertEquals(HandLocatorSelection.Status.MISSING, composer.selectFormHandLocator(
                AuxiliaryPoseMatrices.allocate(layout.totalPoseCount()), Set.of(),
                HumanoidRig.RIGHT_TOOL).status());
    }

    private static AuxiliaryBoneLayout layout() {
        GeometryDocument geometry = new GeometryDocument();
        add(geometry, "Root", "");
        add(geometry, "RightArm", "Root");
        add(geometry, "RightHand", "RightArm");
        add(geometry, "RightHandLocator", "RightHand");
        GeometryDocument.Bone animal = add(geometry, "Animal", "Root");
        add(geometry, "Muzzle", "Animal");
        GeometryDocument.Bone locator = add(geometry, "RightHandLocator2", "Muzzle");
        locator.pivot(0.1F, 0.4F, -0.15F);
        geometry.linkHierarchy();
        return AuxiliaryBoneLayout.create(geometry, 2, 3, RigBindingPlan.create(geometry,
                Map.of(animal, RigBindingPlan.PoseDomain.AUTHORED_SUBTREE)));
    }

    private static GeometryDocument.Bone add(GeometryDocument geometry, String name, String parent) {
        GeometryDocument.Bone bone = new GeometryDocument.Bone(name);
        bone.parentName(parent);
        geometry.add(bone);
        return bone;
    }
}
