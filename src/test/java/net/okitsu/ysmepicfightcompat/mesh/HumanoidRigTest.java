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
