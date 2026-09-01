package net.okitsu.ysmepicfightcompat.mesh;

import net.okitsu.ysmepicfightcompat.geometry.GeometryDocument;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class AuxiliaryBoneLayoutTest {
    @Test
    void mapsCanonicalYsmControlsToAllEpicFightAttachmentJoints() {
        GeometryDocument geometry = canonicalGeometry();
        AuxiliaryBoneLayout layout = AuxiliaryBoneLayout.create(geometry);
        Map<Integer, String> expected = Map.ofEntries(
                Map.entry(HumanoidRig.ROOT, "Root"),
                Map.entry(HumanoidRig.RIGHT_THIGH, "RightLeg"),
                Map.entry(HumanoidRig.RIGHT_LEG, "RightLowerLeg"),
                Map.entry(HumanoidRig.RIGHT_KNEE, "RightLowerLeg"),
                Map.entry(HumanoidRig.LEFT_THIGH, "LeftLeg"),
                Map.entry(HumanoidRig.LEFT_LEG, "LeftLowerLeg"),
                Map.entry(HumanoidRig.LEFT_KNEE, "LeftLowerLeg"),
                Map.entry(HumanoidRig.TORSO, "AllBody"),
                Map.entry(HumanoidRig.CHEST, "UpperBody"),
                Map.entry(HumanoidRig.HEAD, "Head"),
                Map.entry(HumanoidRig.RIGHT_SHOULDER, "RightArm"),
                Map.entry(HumanoidRig.RIGHT_ARM, "RightArm"),
                Map.entry(HumanoidRig.RIGHT_HAND, "RightForeArm"),
                Map.entry(HumanoidRig.RIGHT_TOOL, "RightHand"),
                Map.entry(HumanoidRig.RIGHT_ELBOW, "RightForeArm"),
                Map.entry(HumanoidRig.LEFT_SHOULDER, "LeftArm"),
                Map.entry(HumanoidRig.LEFT_ARM, "LeftArm"),
                Map.entry(HumanoidRig.LEFT_HAND, "LeftForeArm"),
                Map.entry(HumanoidRig.LEFT_TOOL, "LeftHand"),
                Map.entry(HumanoidRig.LEFT_ELBOW, "LeftForeArm")
        );

        for (int joint = 0; joint < HumanoidRig.EPIC_JOINT_COUNT; joint++) {
            AuxiliaryBoneLayout.Entry source = layout.attachmentEntry(joint);
            assertNotNull(source, "missing source for Epic Fight joint " + joint);
            assertEquals(expected.get(joint), source.bone().name());
            assertNotNull(layout.attachmentPivot(joint),
                    "missing pivot for Epic Fight joint " + joint);
        }
        assertEquals(layout.attachmentPivot(HumanoidRig.RIGHT_LEG),
                layout.attachmentPivot(HumanoidRig.RIGHT_KNEE));
        assertEquals(layout.attachmentPivot(HumanoidRig.LEFT_LEG),
                layout.attachmentPivot(HumanoidRig.LEFT_KNEE));
        assertEquals(layout.jointPivot(HumanoidRig.RIGHT_TOOL),
                layout.attachmentPivot(HumanoidRig.RIGHT_TOOL));
        assertEquals(layout.jointPivot(HumanoidRig.LEFT_TOOL),
                layout.attachmentPivot(HumanoidRig.LEFT_TOOL));
    }

    @Test
    void fallsBackOnlyWhenThePreferredCanonicalControlIsMissing() {
        GeometryDocument geometry = new GeometryDocument();
        add(geometry, "Root", null, 0.0F, 0.0F, 0.0F);
        add(geometry, "UpBody", "Root", 0.0F, 8.0F, 0.0F);
        add(geometry, "RightLeg", "Root", 1.0F, 4.0F, 0.0F);
        add(geometry, "RightArm", "UpBody", 3.0F, 10.0F, 0.0F);
        add(geometry, "RightHand", "RightArm", 5.0F, 8.0F, 0.0F);
        geometry.linkHierarchy();

        AuxiliaryBoneLayout layout = AuxiliaryBoneLayout.create(geometry);

        assertEquals("UpBody", layout.attachmentEntry(HumanoidRig.CHEST).bone().name());
        assertEquals("RightHand",
                layout.attachmentEntry(HumanoidRig.RIGHT_HAND).bone().name());
        assertEquals("RightLeg",
                layout.attachmentEntry(HumanoidRig.RIGHT_LEG).bone().name());
        assertEquals("RightLeg",
                layout.attachmentEntry(HumanoidRig.RIGHT_KNEE).bone().name());
    }

    @Test
    void failsOpenInsteadOfSelectingALowerRankWhenControlsAreAmbiguous() {
        GeometryDocument geometry = new GeometryDocument();
        add(geometry, "Root", null, 0.0F, 0.0F, 0.0F);
        add(geometry, "UpperBody", "Root", 0.0F, 8.0F, 0.0F);
        add(geometry, "Upper_Body", "Root", 0.0F, 8.0F, 0.0F);
        add(geometry, "UpBody", "Root", 0.0F, 8.0F, 0.0F);
        add(geometry, "RightArm", "Root", 3.0F, 10.0F, 0.0F);
        add(geometry, "RightForeArm", "RightArm", 4.0F, 9.0F, 0.0F);
        add(geometry, "Right_ForeArm", "RightArm", 4.0F, 9.0F, 0.0F);
        add(geometry, "RightHand", "RightArm", 5.0F, 8.0F, 0.0F);
        add(geometry, "RightLeg", "Root", 1.0F, 4.0F, 0.0F);
        add(geometry, "RightLowerLeg", "RightLeg", 1.0F, 2.0F, 0.0F);
        add(geometry, "Right_LowerLeg", "RightLeg", 1.0F, 2.0F, 0.0F);
        geometry.linkHierarchy();

        AuxiliaryBoneLayout layout = AuxiliaryBoneLayout.create(geometry);

        assertNull(layout.attachmentEntry(HumanoidRig.CHEST));
        assertNull(layout.attachmentPivot(HumanoidRig.CHEST));
        assertNull(layout.attachmentEntry(HumanoidRig.RIGHT_HAND));
        assertNull(layout.attachmentEntry(HumanoidRig.RIGHT_ELBOW));
        assertNull(layout.attachmentEntry(HumanoidRig.RIGHT_LEG));
        assertNull(layout.attachmentEntry(HumanoidRig.RIGHT_KNEE));
    }

    private static GeometryDocument canonicalGeometry() {
        GeometryDocument geometry = new GeometryDocument();
        add(geometry, "Root", null, 0.0F, 0.0F, 0.0F);
        add(geometry, "AllBody", "Root", 0.0F, 8.0F, 0.0F);
        add(geometry, "DownBody", "AllBody", 0.0F, 8.0F, 0.0F);
        add(geometry, "UpperBody", "AllBody", 0.0F, 12.0F, 0.0F);
        add(geometry, "Head", "UpperBody", 0.0F, 16.0F, 0.0F);
        add(geometry, "RightLeg", "AllBody", 1.0F, 8.0F, 0.0F);
        add(geometry, "RightLowerLeg", "RightLeg", 1.0F, 4.0F, 0.0F);
        add(geometry, "LeftLeg", "AllBody", -1.0F, 8.0F, 0.0F);
        add(geometry, "LeftLowerLeg", "LeftLeg", -1.0F, 4.0F, 0.0F);
        add(geometry, "RightArm", "UpperBody", 4.0F, 14.0F, 0.0F);
        add(geometry, "RightForeArm", "RightArm", 6.0F, 12.0F, 0.0F);
        add(geometry, "RightHand", "RightForeArm", 8.0F, 10.0F, 0.0F);
        add(geometry, "RightHandLocator", "RightHand", 9.0F, 9.0F, 0.0F);
        add(geometry, "LeftArm", "UpperBody", -4.0F, 14.0F, 0.0F);
        add(geometry, "LeftForeArm", "LeftArm", -6.0F, 12.0F, 0.0F);
        add(geometry, "LeftHand", "LeftForeArm", -8.0F, 10.0F, 0.0F);
        add(geometry, "LeftHandLocator", "LeftHand", -9.0F, 9.0F, 0.0F);
        geometry.linkHierarchy();
        return geometry;
    }

    private static void add(GeometryDocument geometry, String name, String parent,
                            float x, float y, float z) {
        GeometryDocument.Bone bone = new GeometryDocument.Bone(name);
        bone.parentName(parent);
        bone.pivot(x, y, z);
        geometry.add(bone);
    }
}
