package net.okitsu.ysmepicfightcompat.render;

import net.okitsu.ysmepicfightcompat.mesh.HumanoidRig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import yesman.epicfight.api.animation.Joint;
import yesman.epicfight.api.model.Armature;
import yesman.epicfight.api.utils.math.OpenMatrix4f;
import yesman.epicfight.api.utils.math.Vec3f;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AttachmentArmatureScopeTest {
    private static final String[] NAMES = {
            "Root", "Thigh_R", "Leg_R", "Knee_R", "Thigh_L", "Leg_L", "Knee_L",
            "Torso", "Chest", "Head", "Shoulder_R", "Arm_R", "Hand_R", "Tool_R",
            "Elbow_R", "Shoulder_L", "Arm_L", "Hand_L", "Tool_L", "Elbow_L"
    };

    @BeforeEach
    void beginFrame() {
        RenderFrameContext.pushThirdPerson(null);
    }

    @AfterEach
    void endFrame() {
        RenderFrameContext.clear();
    }

    @Test
    void preservesOriginalArmatureAndReadsTheFinalBodyWithinTheLayer() {
        Armature armature = armature();
        OpenMatrix4f[] original = matrices(22, 1.0F);
        OpenMatrix4f[] displayed = matrices(22, 5.0F);
        OpenMatrix4f[] stored = armature.getPoseMatrices();
        OpenMatrix4f before = new OpenMatrix4f(stored[HumanoidRig.RIGHT_TOOL]);

        assertSame(original, AttachmentArmatureScope.resolvePoseMatrices(armature, original, false));
        assertFalse(AttachmentArmatureScope.suppressPoseWrite(armature));
        try (var ignored = AttachmentArmatureScope.open(armature, original, displayed)) {
            assertTrue(AttachmentArmatureScope.suppressPoseWrite(armature));
            OpenMatrix4f[] actual = AttachmentArmatureScope.resolvePoseMatrices(armature, original, false);
            assertMatrixEquals(displayed[HumanoidRig.RIGHT_TOOL], actual[HumanoidRig.RIGHT_TOOL]);
            assertMatrixEquals(displayed[HumanoidRig.LEFT_TOOL], actual[HumanoidRig.LEFT_TOOL]);
            assertMatrixEquals(original[20], actual[20]);
            assertMatrixEquals(original[21], actual[21]);
            assertNotSame(original, actual);
            assertTrue(AttachmentArmatureScope.isDisplayedPoseArray(armature, actual));
            assertTrue(AttachmentArmatureScope.isDisplayedPoseArray(actual));
            assertFalse(AttachmentArmatureScope.isDisplayedPoseArray(armature, original));
            assertFalse(AttachmentArmatureScope.isDisplayedPoseArray(original));
            assertSame(stored, armature.getPoseMatrices());
            assertMatrixEquals(before, stored[HumanoidRig.RIGHT_TOOL]);
        }
        assertSame(original, AttachmentArmatureScope.resolvePoseMatrices(armature, original, false));
        assertFalse(AttachmentArmatureScope.suppressPoseWrite(armature));
        assertMatrixEquals(before, stored[HumanoidRig.RIGHT_TOOL]);
    }

    @Test
    void keepsAddonSnapshotJointsAndDistinguishesWorldFromSkinMatrices() {
        Armature armature = armature();
        OpenMatrix4f[] original = matrices(22, 1.0F);
        OpenMatrix4f[] displayed = matrices(22, 5.0F);
        OpenMatrix4f[] recomputedSnapshot = matrices(24, 9.0F);
        recomputedSnapshot[23] = null;
        try (var ignored = AttachmentArmatureScope.open(armature, original, displayed)) {
            OpenMatrix4f[] world = AttachmentArmatureScope.resolvePoseMatrices(
                    armature, recomputedSnapshot, false);
            OpenMatrix4f[] skin = AttachmentArmatureScope.resolvePoseMatrices(
                    armature, recomputedSnapshot, true);
            assertTrue(AttachmentArmatureScope.isDisplayedPoseArray(armature, world));
            assertFalse(AttachmentArmatureScope.isDisplayedPoseArray(armature, skin));
            assertFalse(AttachmentArmatureScope.isDisplayedPoseArray(skin));
            assertEquals(24, skin.length);
            for (int joint : new int[]{HumanoidRig.ROOT, HumanoidRig.CHEST,
                    HumanoidRig.RIGHT_TOOL, HumanoidRig.LEFT_TOOL}) {
                assertMatrixEquals(displayed[joint], world[joint]);
                assertMatrixEquals(new OpenMatrix4f(displayed[joint])
                        .mulBack(armature.searchJointById(joint).getToOrigin()), skin[joint]);
            }
            for (int joint : new int[]{20, 21, 22}) {
                assertMatrixEquals(recomputedSnapshot[joint], world[joint]);
                assertMatrixEquals(recomputedSnapshot[joint], skin[joint]);
                assertNotSame(recomputedSnapshot[joint], skin[joint]);
            }
            assertNull(skin[23]);
        }
    }

    @Test
    void readsSingleKnownJointsButDoesNotReplaceOtherArmaturesOrAddonJoints() {
        Armature armature = armature();
        Armature other = armature();
        OpenMatrix4f[] original = matrices(22, 1.0F);
        OpenMatrix4f[] displayed = matrices(22, 5.0F);
        OpenMatrix4f requested = new OpenMatrix4f().translate(17.0F, 2.0F, -3.0F);
        try (var ignored = AttachmentArmatureScope.open(armature, original, displayed)) {
            assertMatrixEquals(displayed[HumanoidRig.RIGHT_TOOL],
                    AttachmentArmatureScope.resolveJointPose(armature,
                            armature.searchJointById(HumanoidRig.RIGHT_TOOL), requested));
            assertSame(requested, AttachmentArmatureScope.resolveJointPose(
                    armature, armature.searchJointById(20), requested));
            assertMatrixEquals(displayed[HumanoidRig.RIGHT_TOOL],
                    AttachmentArmatureScope.resolveJointPose(
                            armature, other.searchJointById(HumanoidRig.RIGHT_TOOL), requested));
            assertSame(requested, AttachmentArmatureScope.resolveJointPose(armature, null, requested));
            assertSame(original, AttachmentArmatureScope.resolvePoseMatrices(other, original, false));
            assertFalse(AttachmentArmatureScope.suppressPoseWrite(other));
        }
    }

    @Test
    void snapshotsEachFrameWithoutLettingALayerMutateTheNextRead() {
        Armature armature = armature();
        OpenMatrix4f[] original = matrices(22, 1.0F);
        OpenMatrix4f[] displayed = matrices(22, 5.0F);
        for (float bob : new float[]{0.0F, 0.15F, 0.3F, 0.05F, -0.2F}) {
            displayed[HumanoidRig.RIGHT_TOOL].m31 = bob;
            try (var ignored = AttachmentArmatureScope.open(armature, original, displayed)) {
                displayed[HumanoidRig.RIGHT_TOOL].m31 = 99.0F;
                OpenMatrix4f[] read = AttachmentArmatureScope.resolvePoseMatrices(armature, original, false);
                assertEquals(bob, read[HumanoidRig.RIGHT_TOOL].m31, 0.00001F);
                read[HumanoidRig.RIGHT_TOOL].m31 = 88.0F;
                assertEquals(bob, AttachmentArmatureScope.resolvePoseMatrices(
                        armature, original, false)[HumanoidRig.RIGHT_TOOL].m31, 0.00001F);
            }
        }
    }

    @Test
    void nestedScopesAndNestedEntityFramesCannotLeakAnOuterPose() {
        Armature armature = armature();
        OpenMatrix4f[] original = matrices(22, 1.0F);
        OpenMatrix4f[] outer = matrices(22, 5.0F);
        OpenMatrix4f[] inner = matrices(22, 9.0F);
        try (var ignored = AttachmentArmatureScope.open(armature, original, outer)) {
            RenderFrameContext.Frame nested = RenderFrameContext.pushFirstPerson(null, Map.of(), false);
            try {
                assertFalse(AttachmentArmatureScope.suppressPoseWrite(armature));
                assertSame(original, AttachmentArmatureScope.resolvePoseMatrices(armature, original, false));
                try (var nestedScope = AttachmentArmatureScope.open(armature, original, inner)) {
                    assertMatrixEquals(inner[13], AttachmentArmatureScope.resolvePoseMatrices(
                            armature, original, false)[13]);
                }
                assertFalse(AttachmentArmatureScope.suppressPoseWrite(armature));
            } finally {
                RenderFrameContext.pop(nested);
            }
            assertMatrixEquals(outer[13], AttachmentArmatureScope.resolvePoseMatrices(
                    armature, original, false)[13]);
            try (var barrier = AttachmentArmatureScope.open(armature, original, original)) {
                assertFalse(AttachmentArmatureScope.suppressPoseWrite(armature));
                assertSame(original, AttachmentArmatureScope.resolvePoseMatrices(armature, original, false));
            }
            assertTrue(AttachmentArmatureScope.suppressPoseWrite(armature));
        }
    }

    @Test
    void closesOnExceptionsAndToleratesOutOfOrderAndDuplicateClose() {
        Armature armature = armature();
        OpenMatrix4f[] original = matrices(22, 1.0F);
        OpenMatrix4f[] displayed = matrices(22, 5.0F);
        assertThrows(IllegalStateException.class, () -> {
            try (var ignored = AttachmentArmatureScope.open(armature, original, displayed)) {
                assertTrue(AttachmentArmatureScope.suppressPoseWrite(armature));
                throw new IllegalStateException("Synthetic failing renderer");
            }
        });
        assertFalse(AttachmentArmatureScope.suppressPoseWrite(armature));
        var outer = AttachmentArmatureScope.open(armature, original, displayed);
        var inner = AttachmentArmatureScope.open(armature, original, displayed);
        try {
            outer.close();
            outer.close();
            assertTrue(AttachmentArmatureScope.suppressPoseWrite(armature));
        } finally {
            inner.close();
            outer.close();
        }
        assertFalse(AttachmentArmatureScope.suppressPoseWrite(armature));
    }

    @Test
    void invalidOrUnboundInputIsAPassThrough() {
        Armature armature = armature();
        OpenMatrix4f[] original = matrices(22, 1.0F);
        OpenMatrix4f[] displayed = matrices(22, 5.0F);
        displayed[13].m31 = Float.NaN;
        try (var ignored = AttachmentArmatureScope.open(armature, original, displayed)) {
            assertFalse(AttachmentArmatureScope.suppressPoseWrite(armature));
        }
        try (var ignored = AttachmentArmatureScope.open(armature, original, matrices(21, 5.0F))) {
            assertFalse(AttachmentArmatureScope.suppressPoseWrite(armature));
        }
        try (var ignored = AttachmentArmatureScope.open(null, original, matrices(22, 5.0F))) {
            assertFalse(AttachmentArmatureScope.suppressPoseWrite(armature));
        }
        RenderFrameContext.clear();
        try (var ignored = AttachmentArmatureScope.open(armature, original, matrices(22, 5.0F))) {
            assertFalse(AttachmentArmatureScope.suppressPoseWrite(armature));
        }
    }

    private static Armature armature() {
        Map<String, Joint> joints = new LinkedHashMap<>();
        Joint root = new Joint(NAMES[0], 0, new OpenMatrix4f());
        joints.put(root.getName(), root);
        for (int id = 1; id < 22; id++) {
            Joint joint = new Joint(id < NAMES.length ? NAMES[id] : "Addon_" + id,
                    id, new OpenMatrix4f().translate(0.0F, id * 0.1F, 0.0F));
            root.addSubJoints(joint);
            joints.put(joint.getName(), joint);
        }
        Armature result = new Armature("attachment-scope-test", 22, root, joints);
        result.bakeOriginMatrices();
        return result;
    }

    private static OpenMatrix4f[] matrices(int count, float offset) {
        OpenMatrix4f[] result = new OpenMatrix4f[count];
        for (int id = 0; id < count; id++) {
            result[id] = new OpenMatrix4f().translate(offset, offset + id, -offset)
                    .rotateDeg(offset * id, Vec3f.Z_AXIS).scale(0.8F, 1.2F, 1.1F);
        }
        return result;
    }

    private static void assertMatrixEquals(OpenMatrix4f expected, OpenMatrix4f actual) {
        assertNotNull(actual);
        assertArrayEquals(elements(expected), elements(actual), 0.00001F);
    }

    private static float[] elements(OpenMatrix4f matrix) {
        return new float[]{matrix.m00, matrix.m01, matrix.m02, matrix.m03,
                matrix.m10, matrix.m11, matrix.m12, matrix.m13,
                matrix.m20, matrix.m21, matrix.m22, matrix.m23,
                matrix.m30, matrix.m31, matrix.m32, matrix.m33};
    }
}
