package net.okitsu.ysmepicfightcompat.mesh;

import net.okitsu.ysmepicfightcompat.geometry.GeometryDocument;
import org.junit.jupiter.api.Test;
import yesman.epicfight.api.utils.math.OpenMatrix4f;
import yesman.epicfight.api.utils.math.Vec3f;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NonHumanoidPoseMatricesTest {
    @Test
    void staticFreeStandingBodyStaysInBindSpaceWithoutAnAnimationProgram() {
        AuxiliaryBoneLayout layout = layout();
        OpenMatrix4f[] epic = matrices(20);
        epic[HumanoidRig.HEAD].translate(100, 200, 300);
        epic[HumanoidRig.RIGHT_ARM].rotateDeg(120, Vec3f.Z_AXIS);
        OpenMatrix4f[] output = compose(layout, epic, null, null);
        assertMatrix(new OpenMatrix4f(), bone(output, layout, "Head2"));
        assertMatrix(new OpenMatrix4f(), bone(output, layout, "RightArm2"));
        assertMatrix(epic[HumanoidRig.HEAD], bone(output, layout, "Head"));
    }

    @Test
    void staticAttachedBodyInheritsItsDisplayedParentExactlyOnce() {
        AuxiliaryBoneLayout layout = layout();
        OpenMatrix4f[] epic = matrices(20);
        epic[HumanoidRig.HEAD].translate(1, 2, 3).rotateDeg(30, Vec3f.Y_AXIS);
        OpenMatrix4f[] output = compose(layout, epic, null, null);
        assertMatrix(epic[HumanoidRig.HEAD], bone(output, layout, "Companion"));
        assertMatrix(epic[HumanoidRig.HEAD], bone(output, layout, "CompanionJaw"));
    }

    @Test
    void staticAuthoredFirstPersonUsesTheViewRebaseWithoutAnAnimationFrame() {
        assertTrue(CompatHumanoidMesh.usesFirstPersonPoseTransform(true, null, true));
        org.junit.jupiter.api.Assertions.assertFalse(
                CompatHumanoidMesh.usesFirstPersonPoseTransform(false, null, true));
        org.junit.jupiter.api.Assertions.assertFalse(
                CompatHumanoidMesh.usesFirstPersonPoseTransform(true, null, false));
        AuxiliaryBoneLayout layout = layout();
        OpenMatrix4f view = new OpenMatrix4f().translate(1, 2, 3).rotateDeg(20, Vec3f.X_AXIS);
        OpenMatrix4f[] output = compose(layout, matrices(20), null, view);
        assertMatrix(view, bone(output, layout, "Head2"));
        assertMatrix(new OpenMatrix4f(), bone(output, layout, "Companion"));
    }

    @Test
    void freeStandingBodyKeepsItsNativeHierarchyUnderExtremeEpicLimbPoses() {
        AuxiliaryBoneLayout layout = layout();
        OpenMatrix4f[] epic = matrices(HumanoidRig.EPIC_JOINT_COUNT);
        OpenMatrix4f[] nativePose = matrices(layout.entries().size());
        OpenMatrix4f nativeRoot = new OpenMatrix4f().translate(0.3F, 0.2F, -0.6F)
                .rotateDeg(35, Vec3f.Y_AXIS);
        for (String name : new String[]{"AlternateBody", "Head2", "RightArm2"}) {
            nativePose[index(layout, name)].load(nativeRoot);
        }
        nativePose[index(layout, "RightArm2")].rotateDeg(24, Vec3f.X_AXIS);
        OpenMatrix4f[] expected = copy(nativePose);
        epic[HumanoidRig.HEAD].translate(100, 200, 300).rotateDeg(75, Vec3f.Y_AXIS);
        epic[HumanoidRig.RIGHT_ARM].translate(-90, -80, -70).rotateDeg(-120, Vec3f.Z_AXIS);
        OpenMatrix4f[] epicBefore = copy(epic);
        OpenMatrix4f[] output = compose(layout, epic, nativePose, null);

        assertMatrix(expected[index(layout, "Head2")], bone(output, layout, "Head2"));
        assertMatrix(expected[index(layout, "RightArm2")], bone(output, layout, "RightArm2"));
        assertMatrix(epic[HumanoidRig.HEAD], bone(output, layout, "Head"));
        for (int joint = 0; joint < epic.length; joint++) {
            assertMatrix(epicBefore[joint], epic[joint]);
            assertMatrix(epicBefore[joint], output[joint]);
        }
        for (int index = 0; index < nativePose.length; index++) {
            assertMatrix(expected[index], nativePose[index]);
        }
    }

    @Test
    void attachedAnimalInheritsTheDisplayedParentExactlyOnce() {
        AuxiliaryBoneLayout layout = layout();
        OpenMatrix4f[] epic = matrices(HumanoidRig.EPIC_JOINT_COUNT);
        epic[HumanoidRig.HEAD].translate(2, 3, 4).rotateDeg(41, Vec3f.Y_AXIS);
        OpenMatrix4f[] nativePose = matrices(layout.entries().size());
        OpenMatrix4f nativeHead = new OpenMatrix4f().translate(7, 8, 9)
                .rotateDeg(-29, Vec3f.Z_AXIS);
        OpenMatrix4f localAnimal = new OpenMatrix4f().translate(0.2F, 0.4F, -0.3F)
                .rotateDeg(18, Vec3f.X_AXIS);
        nativePose[index(layout, "Head")].load(nativeHead);
        nativePose[index(layout, "Companion")].load(nativeHead).mulBack(localAnimal);
        nativePose[index(layout, "CompanionJaw")]
                .load(nativePose[index(layout, "Companion")]).translate(0, 0, 0.1F);

        OpenMatrix4f[] output = compose(layout, epic, nativePose, null);

        assertMatrix(new OpenMatrix4f(epic[HumanoidRig.HEAD]).mulBack(localAnimal),
                bone(output, layout, "Companion"));
        assertMatrix(new OpenMatrix4f(epic[HumanoidRig.HEAD]).mulBack(localAnimal)
                .translate(0, 0, 0.1F), bone(output, layout, "CompanionJaw"));
    }

    @Test
    void sharedNativeRootMotionDoesNotLeakIntoTheHumanoid() {
        AuxiliaryBoneLayout layout = layout();
        OpenMatrix4f[] nativePose = matrices(layout.entries().size());
        OpenMatrix4f shift = new OpenMatrix4f().translate(1, 2, 3);
        nativePose[index(layout, "Root")].load(shift);
        nativePose[index(layout, "AlternateBody")].load(shift);
        nativePose[index(layout, "Head2")].load(shift);
        OpenMatrix4f[] output = compose(layout, matrices(20), nativePose, null);

        assertMatrix(new OpenMatrix4f(), bone(output, layout, "Root"));
        assertMatrix(new OpenMatrix4f(), bone(output, layout, "Head"));
        assertMatrix(shift, bone(output, layout, "Head2"));
    }

    @Test
    void viewTransformAppliesOnceToFreeBodyAndNeverAgainToAnAttachedBody() {
        AuxiliaryBoneLayout layout = layout();
        OpenMatrix4f[] epic = matrices(20);
        epic[HumanoidRig.HEAD].translate(0, 1.5F, -0.4F);
        OpenMatrix4f[] nativePose = matrices(layout.entries().size());
        OpenMatrix4f view = new OpenMatrix4f().translate(0.5F, -1, 0.7F)
                .rotateDeg(17, Vec3f.X_AXIS);
        OpenMatrix4f[] output = compose(layout, epic, nativePose, view);

        assertMatrix(view, bone(output, layout, "Head2"));
        assertMatrix(epic[HumanoidRig.HEAD], bone(output, layout, "Companion"));
        assertMatrix(epic[HumanoidRig.HEAD], output[HumanoidRig.HEAD]);
    }

    @Test
    void completeYsmPoseDoesNotReceiveTheNativeLaneTwice() {
        AuxiliaryBoneLayout layout = layout();
        OpenMatrix4f[] whole = matrices(layout.entries().size());
        OpenMatrix4f[] nativePose = matrices(layout.entries().size());
        whole[index(layout, "Head2")].translate(1, 2, 3);
        nativePose[index(layout, "Head2")].translate(99, 99, 99);
        OpenMatrix4f[] output = matrices(layout.totalPoseCount());
        AuxiliaryPoseMatrices.composeAuthored(matrices(20), matrices(20), layout, output,
                null, whole, true, nativePose, null);

        assertMatrix(whole[index(layout, "Head2")], bone(output, layout, "Head2"));
    }

    @Test
    void singularNativeParentCannotProduceNonFiniteAttachedMatrices() {
        AuxiliaryBoneLayout layout = layout();
        OpenMatrix4f[] nativePose = matrices(layout.entries().size());
        OpenMatrix4f[] epic = matrices(20);
        nativePose[index(layout, "Head")].scale(0, 0, 0);
        nativePose[index(layout, "Companion")].scale(0, 0, 0);
        epic[HumanoidRig.HEAD].scale(0, 0, 0);
        OpenMatrix4f[] output = compose(layout, epic, nativePose, null);

        assertMatrix(epic[HumanoidRig.HEAD], bone(output, layout, "Companion"));
    }

    @Test
    void malformedNativeLaneDoesNotMutateOrPoisonTheEpicPose() {
        AuxiliaryBoneLayout layout = layout();
        OpenMatrix4f[] nativePose = matrices(layout.entries().size());
        nativePose[index(layout, "Head2")].m30 = Float.NaN;
        OpenMatrix4f[] epic = matrices(20);
        epic[HumanoidRig.HEAD].translate(1, 2, 3);
        OpenMatrix4f[] output = compose(layout, epic, nativePose, null);
        assertMatrix(epic[HumanoidRig.HEAD], bone(output, layout, "Head2"));
        assertTrue(Float.isNaN(nativePose[index(layout, "Head2")].m30));
    }

    private static AuxiliaryBoneLayout layout() {
        GeometryDocument geometry = new GeometryDocument();
        add(geometry, "Root", "");
        add(geometry, "AllBody", "Root");
        add(geometry, "Head", "AllBody");
        GeometryDocument.Bone companion = add(geometry, "Companion", "Head");
        add(geometry, "CompanionJaw", "Companion");
        GeometryDocument.Bone alternate = add(geometry, "AlternateBody", "Root");
        add(geometry, "Head2", "AlternateBody");
        add(geometry, "RightArm2", "AlternateBody");
        geometry.linkHierarchy();
        RigBindingPlan plan = RigBindingPlan.create(geometry, Map.of(
                companion, RigBindingPlan.PoseDomain.AUTHORED_SUBTREE,
                alternate, RigBindingPlan.PoseDomain.AUTHORED_SUBTREE));
        return AuxiliaryBoneLayout.create(geometry, 1, 1, plan);
    }

    private static GeometryDocument.Bone add(GeometryDocument geometry, String name, String parent) {
        GeometryDocument.Bone bone = new GeometryDocument.Bone(name);
        bone.parentName(parent);
        geometry.add(bone);
        return bone;
    }

    private static int index(AuxiliaryBoneLayout layout, String name) {
        return layout.entryForBoneName(name).auxiliaryIndex();
    }

    private static OpenMatrix4f bone(OpenMatrix4f[] output, AuxiliaryBoneLayout layout, String name) {
        return output[layout.entryForBoneName(name).poseIndex()];
    }

    private static OpenMatrix4f[] compose(AuxiliaryBoneLayout layout, OpenMatrix4f[] epic,
                                         OpenMatrix4f[] nativePose, OpenMatrix4f view) {
        OpenMatrix4f[] result = matrices(layout.totalPoseCount());
        return AuxiliaryPoseMatrices.composeAuthored(epic, matrices(20), layout, result,
                null, null, false, nativePose, view);
    }

    private static OpenMatrix4f[] matrices(int size) {
        return AuxiliaryPoseMatrices.allocate(size);
    }

    private static OpenMatrix4f[] copy(OpenMatrix4f[] source) {
        return java.util.Arrays.stream(source).map(OpenMatrix4f::new).toArray(OpenMatrix4f[]::new);
    }

    private static void assertMatrix(OpenMatrix4f expected, OpenMatrix4f actual) {
        for (java.lang.reflect.Field field : OpenMatrix4f.class.getFields()) {
            if (field.getName().matches("m[0-3][0-3]")) {
                try {
                    assertEquals(field.getFloat(expected), field.getFloat(actual), 0.0001F,
                            field.getName());
                } catch (IllegalAccessException error) {
                    throw new AssertionError(error);
                }
            }
        }
    }
}
