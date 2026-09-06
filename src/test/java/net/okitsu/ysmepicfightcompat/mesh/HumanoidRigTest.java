package net.okitsu.ysmepicfightcompat.mesh;

import net.okitsu.ysmepicfightcompat.geometry.GeometryDocument;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HumanoidRigTest {
    @Test
    void normalizesNumberedOfficialBoneNames() {
        assertEquals(HumanoidRig.RIGHT_ARM, HumanoidRig.jointFor(bone("RightArm", "")));
        GeometryDocument.Bone left = bone("Left_Arm2", "");
        assertEquals(HumanoidRig.LEFT_ARM, HumanoidRig.jointFor(left));
        assertTrue(HumanoidRig.hasDirectBinding(left));
        assertTrue(HumanoidRig.isMajorBone(left));
    }

    @Test
    void normalizesOfficialDefaultFormSuffixes() {
        GeometryDocument.Bone right = bone("RightArm_Default", "");

        assertEquals(HumanoidRig.RIGHT_ARM, HumanoidRig.jointFor(right));
        assertTrue(HumanoidRig.hasDirectBinding(right));
        assertTrue(HumanoidRig.isMajorBone(right));
    }

    @Test
    void customGeometryUsesTheNearestMappedAncestor() {
        GeometryDocument model = new GeometryDocument();
        GeometryDocument.Bone head = bone("head", "");
        GeometryDocument.Bone ear = bone("fox_ear", "head");
        model.add(head);
        model.add(ear);
        model.linkHierarchy();

        assertEquals(HumanoidRig.HEAD, HumanoidRig.jointFor(ear));
        assertFalse(HumanoidRig.hasDirectBinding(ear));
        assertFalse(HumanoidRig.isMajorBone(ear));
    }

    @Test
    void keepsTheNeckAtTheChestWhileMHeadOwnsTheRotatingHeadBranch() {
        for (String suffix : new String[]{"", "2", "_Default", "2_Default"}) {
            GeometryDocument geometry = new GeometryDocument();
            GeometryDocument.Bone chest = bone("UpperBody" + suffix, "");
            GeometryDocument.Bone allHead = bone("AllHead" + suffix, chest.name());
            GeometryDocument.Bone neck = bone("Neck" + suffix, allHead.name());
            GeometryDocument.Bone collar = bone("CustomCollar" + suffix, allHead.name());
            GeometryDocument.Bone movingHead = bone("MHead" + suffix, allHead.name());
            GeometryDocument.Bone head = bone("Head" + suffix, movingHead.name());
            GeometryDocument.Bone jaw = bone("CustomJaw" + suffix, movingHead.name());
            GeometryDocument.Bone hair = bone("Hair" + suffix, movingHead.name());
            for (GeometryDocument.Bone entry : new GeometryDocument.Bone[]{
                    chest, allHead, neck, collar, movingHead, head, jaw, hair}) {
                geometry.add(entry);
            }
            geometry.linkHierarchy();

            for (GeometryDocument.Bone entry : new GeometryDocument.Bone[]{
                    allHead, neck, collar}) {
                assertEquals(HumanoidRig.CHEST, HumanoidRig.jointFor(entry), entry.name());
            }
            for (GeometryDocument.Bone entry : new GeometryDocument.Bone[]{
                    movingHead, head, jaw, hair}) {
                assertEquals(HumanoidRig.HEAD, HumanoidRig.jointFor(entry), entry.name());
            }
            assertTrue(HumanoidRig.hasDirectBinding(movingHead), movingHead.name());
            assertFalse(HumanoidRig.hasDirectBinding(jaw), jaw.name());
            assertFalse(HumanoidRig.hasDirectBinding(collar), collar.name());
        }
    }

    @Test
    void accessoryAliasesRemainAuxiliaryEvenWhenTheyHaveAnAnchorBinding() {
        GeometryDocument.Bone cape = bone("cape", "");

        assertEquals(HumanoidRig.CHEST, HumanoidRig.jointFor(cape));
        assertTrue(HumanoidRig.hasDirectBinding(cape));
        assertFalse(HumanoidRig.isMajorBone(cape));
    }

    @Test
    void unknownRootsUseTheArmatureRoot() {
        assertEquals(HumanoidRig.ROOT, HumanoidRig.jointFor(bone("custom", "")));
    }

    private static GeometryDocument.Bone bone(String name, String parent) {
        GeometryDocument.Bone bone = new GeometryDocument.Bone(name);
        bone.parentName(parent);
        return bone;
    }
}
