package net.okitsu.ysmepicfightcompat.mesh;

import net.okitsu.ysmepicfightcompat.geometry.GeometryDocument;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import yesman.epicfight.api.utils.math.OpenMatrix4f;
import yesman.epicfight.api.utils.math.Vec3f;
import yesman.epicfight.api.utils.math.Vec4f;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuxiliaryPoseMatricesTest {
    @Test
    void appliesWholeBodyRouletteDeltaOutsideTheEpicHeadPose() {
        GeometryDocument geometry = new GeometryDocument();
        GeometryDocument.Bone head = new GeometryDocument.Bone("head");
        geometry.add(head);
        geometry.linkHierarchy();
        AuxiliaryBoneLayout layout = AuxiliaryBoneLayout.create(geometry);
        OpenMatrix4f[] poses = AuxiliaryPoseMatrices.allocate(HumanoidRig.EPIC_JOINT_COUNT);
        OpenMatrix4f[] toOrigin = AuxiliaryPoseMatrices.allocate(HumanoidRig.EPIC_JOINT_COUNT);
        poses[HumanoidRig.HEAD].translate(0.5F, 1.5F, -0.25F)
                .rotateDeg(30.0F, Vec3f.Y_AXIS);
        OpenMatrix4f[] output = AuxiliaryPoseMatrices.allocate(layout.totalPoseCount());
        OpenMatrix4f[] modelDeltas = AuxiliaryPoseMatrices.allocate(1);
        modelDeltas[0].translate(0.0F, 0.75F, 0.0F)
                .rotateDeg(90.0F, Vec3f.X_AXIS)
                .translate(0.0F, -0.75F, 0.0F);

        AuxiliaryPoseMatrices.compose(poses, toOrigin, layout, output,
                null, modelDeltas);

        OpenMatrix4f epicHead = new OpenMatrix4f(poses[HumanoidRig.HEAD])
                .mulBack(toOrigin[HumanoidRig.HEAD]);
        assertMatrixEquals(epicHead, output[HumanoidRig.HEAD]);
        assertMatrixEquals(new OpenMatrix4f(epicHead).mulFront(modelDeltas[0]),
                output[layout.entries().get(0).poseIndex()]);
    }

    @Test
    void replacesTheEpicFightAnchorForAWholeModelMountedPose() {
        GeometryDocument geometry = new GeometryDocument();
        GeometryDocument.Bone head = new GeometryDocument.Bone("head");
        geometry.add(head);
        geometry.linkHierarchy();
        AuxiliaryBoneLayout layout = AuxiliaryBoneLayout.create(geometry);
        OpenMatrix4f[] poses = AuxiliaryPoseMatrices.allocate(HumanoidRig.EPIC_JOINT_COUNT);
        OpenMatrix4f[] toOrigin = AuxiliaryPoseMatrices.allocate(HumanoidRig.EPIC_JOINT_COUNT);
        poses[HumanoidRig.HEAD].translate(0.0F, -1.5F, 0.0F)
                .rotateDeg(35.0F, Vec3f.X_AXIS);
        OpenMatrix4f[] output = AuxiliaryPoseMatrices.allocate(layout.totalPoseCount());
        OpenMatrix4f[] mountedDeltas = AuxiliaryPoseMatrices.allocate(1);
        mountedDeltas[0].translate(0.0F, -0.25F, 0.0F)
                .rotateDeg(70.0F, Vec3f.X_AXIS);

        AuxiliaryPoseMatrices.compose(poses, toOrigin, layout, output,
                null, mountedDeltas, true);

        assertMatrixEquals(poses[HumanoidRig.HEAD], output[HumanoidRig.HEAD]);
        assertMatrixEquals(mountedDeltas[0],
                output[layout.entries().get(0).poseIndex()]);
    }

    @Test
    void appliesHairParallelDeltaInsideTheEpicHeadPose() {
        GeometryDocument geometry = new GeometryDocument();
        GeometryDocument.Bone head = new GeometryDocument.Bone("head");
        GeometryDocument.Bone hair = new GeometryDocument.Bone("hair");
        hair.parentName("head");
        geometry.add(head);
        geometry.add(hair);
        geometry.linkHierarchy();
        AuxiliaryBoneLayout layout = AuxiliaryBoneLayout.create(geometry);
        OpenMatrix4f[] poses = AuxiliaryPoseMatrices.allocate(HumanoidRig.EPIC_JOINT_COUNT);
        OpenMatrix4f[] toOrigin = AuxiliaryPoseMatrices.allocate(HumanoidRig.EPIC_JOINT_COUNT);
        poses[HumanoidRig.HEAD].rotateDeg(25.0F, Vec3f.X_AXIS);
        OpenMatrix4f[] output = AuxiliaryPoseMatrices.allocate(layout.totalPoseCount());
        OpenMatrix4f[] modelDeltas = AuxiliaryPoseMatrices.allocate(layout.entries().size());
        AuxiliaryBoneLayout.Entry hairEntry = layout.entries().get(1);
        modelDeltas[hairEntry.auxiliaryIndex()].translate(0.0F, 1.0F, 0.0F)
                .rotateDeg(-15.0F, Vec3f.Z_AXIS)
                .translate(0.0F, -1.0F, 0.0F);

        AuxiliaryPoseMatrices.compose(poses, toOrigin, layout, output,
                modelDeltas, null);

        OpenMatrix4f epicHead = new OpenMatrix4f(poses[HumanoidRig.HEAD]);
        assertMatrixEquals(new OpenMatrix4f(epicHead)
                        .mulBack(modelDeltas[hairEntry.auxiliaryIndex()]),
                output[hairEntry.poseIndex()]);
    }

    @Test
    void keepsParallelTailMotionInsideAWholeBodyRoulettePose() {
        GeometryDocument geometry = new GeometryDocument();
        GeometryDocument.Bone body = new GeometryDocument.Bone("body");
        GeometryDocument.Bone tail = new GeometryDocument.Bone("tail");
        tail.parentName("body");
        geometry.add(body);
        geometry.add(tail);
        geometry.linkHierarchy();
        AuxiliaryBoneLayout layout = AuxiliaryBoneLayout.create(geometry);
        OpenMatrix4f[] poses = AuxiliaryPoseMatrices.allocate(HumanoidRig.EPIC_JOINT_COUNT);
        OpenMatrix4f[] toOrigin = AuxiliaryPoseMatrices.allocate(HumanoidRig.EPIC_JOINT_COUNT);
        poses[HumanoidRig.TORSO].rotateDeg(20.0F, Vec3f.Y_AXIS);
        OpenMatrix4f[] output = AuxiliaryPoseMatrices.allocate(layout.totalPoseCount());
        OpenMatrix4f[] parallelDeltas = AuxiliaryPoseMatrices.allocate(layout.entries().size());
        OpenMatrix4f[] rouletteDeltas = AuxiliaryPoseMatrices.allocate(layout.entries().size());
        AuxiliaryBoneLayout.Entry tailEntry = layout.entries().get(1);
        parallelDeltas[tailEntry.auxiliaryIndex()].translate(0.0F, 0.5F, 0.0F)
                .rotateDeg(15.0F, Vec3f.Z_AXIS)
                .translate(0.0F, -0.5F, 0.0F);
        rouletteDeltas[tailEntry.auxiliaryIndex()].translate(0.0F, 1.0F, 0.0F)
                .rotateDeg(70.0F, Vec3f.X_AXIS)
                .translate(0.0F, -1.0F, 0.0F);

        AuxiliaryPoseMatrices.compose(poses, toOrigin, layout, output,
                parallelDeltas, rouletteDeltas);

        OpenMatrix4f expected = new OpenMatrix4f(poses[HumanoidRig.TORSO])
                .mulBack(parallelDeltas[tailEntry.auxiliaryIndex()])
                .mulFront(rouletteDeltas[tailEntry.auxiliaryIndex()]);
        assertMatrixEquals(expected, output[tailEntry.poseIndex()]);
    }

    @Test
    void preservesEpicSkinMatricesAndCopiesTheAnchorForAuxiliaryBones() {
        GeometryDocument geometry = new GeometryDocument();
        GeometryDocument.Bone head = new GeometryDocument.Bone("head");
        GeometryDocument.Bone ear = new GeometryDocument.Bone("ear");
        ear.parentName("head");
        geometry.add(head);
        geometry.add(ear);
        geometry.linkHierarchy();
        AuxiliaryBoneLayout layout = AuxiliaryBoneLayout.create(geometry);
        OpenMatrix4f[] poses = translatedMatrices(HumanoidRig.EPIC_JOINT_COUNT, 2.0F);
        OpenMatrix4f[] toOrigin = translatedMatrices(HumanoidRig.EPIC_JOINT_COUNT, 3.0F);
        OpenMatrix4f[] output = AuxiliaryPoseMatrices.allocate(layout.totalPoseCount());

        AuxiliaryPoseMatrices.compose(poses, toOrigin, layout, output, null, null);

        for (int index = 0; index < HumanoidRig.EPIC_JOINT_COUNT; index++) {
            assertMatrixEquals(new OpenMatrix4f(poses[index]).mulBack(toOrigin[index]),
                    output[index]);
        }
        AuxiliaryBoneLayout.Entry headEntry = layout.entries().get(0);
        assertEquals(HumanoidRig.HEAD, headEntry.anchorJoint());
        assertMatrixEquals(output[HumanoidRig.HEAD], output[headEntry.poseIndex()]);
        AuxiliaryBoneLayout.Entry earEntry = layout.entries().get(1);
        assertEquals(HumanoidRig.HEAD, earEntry.anchorJoint());
        assertMatrixEquals(output[HumanoidRig.HEAD], output[earEntry.poseIndex()]);
    }

    @Test
    void usesRetargetedAnchorsOnlyForPrivateYsmPoses() {
        GeometryDocument geometry = new GeometryDocument();
        GeometryDocument.Bone rightArm = new GeometryDocument.Bone("rightArm");
        geometry.add(rightArm);
        geometry.linkHierarchy();
        AuxiliaryBoneLayout layout = AuxiliaryBoneLayout.create(geometry);
        OpenMatrix4f[] poses = translatedMatrices(HumanoidRig.EPIC_JOINT_COUNT, 1.0F);
        OpenMatrix4f[] toOrigin = AuxiliaryPoseMatrices.allocate(HumanoidRig.EPIC_JOINT_COUNT);
        OpenMatrix4f[] output = AuxiliaryPoseMatrices.allocate(layout.totalPoseCount());
        OpenMatrix4f[] retargeted = AuxiliaryPoseMatrices.allocate(HumanoidRig.EPIC_JOINT_COUNT);
        retargeted[HumanoidRig.RIGHT_ARM].translate(40.0F, 50.0F, 60.0F);

        AuxiliaryPoseMatrices.compose(poses, toOrigin, layout, output,
                null, null, retargeted);

        AuxiliaryBoneLayout.Entry armEntry = layout.entries().get(0);
        assertMatrixEquals(poses[HumanoidRig.RIGHT_ARM], output[HumanoidRig.RIGHT_ARM]);
        assertMatrixEquals(retargeted[HumanoidRig.RIGHT_ARM], output[armEntry.poseIndex()]);
    }

    @Test
    void preservesToolBasisAndIndependentTranslationAtTheDisplayedFist() {
        OpenMatrix4f selected = new OpenMatrix4f()
                .translate(10.0F, 20.0F, 30.0F)
                .rotateDeg(37.0F, Vec3f.Y_AXIS);
        OpenMatrix4f original = new OpenMatrix4f(selected);
        OpenMatrix4f handSkin = new OpenMatrix4f().translate(4.0F, 5.0F, 6.0F);
        OpenMatrix4f toolSkin = new OpenMatrix4f().translate(6.0F, 2.0F, 10.0F);
        OpenMatrix4f output = new OpenMatrix4f();

        AuxiliaryPoseMatrices.placeAtDisplayedFist(
                selected, handSkin, toolSkin,
                new Vector3f(1.0F, 2.0F, 3.0F),
                new Vector3f(20.0F, 30.0F, 40.0F), output,
                new Vec4f(), new Vec4f(), new Vec4f());

        assertEquals(22.0F, output.m30, 0.00001F);
        assertEquals(27.0F, output.m31, 0.00001F);
        assertEquals(44.0F, output.m32, 0.00001F);
        assertEquals(selected.m00, output.m00, 0.00001F);
        assertEquals(selected.m02, output.m02, 0.00001F);
        assertEquals(selected.m20, output.m20, 0.00001F);
        assertEquals(selected.m22, output.m22, 0.00001F);
        assertMatrixEquals(original, selected);
    }

    @Test
    void doesNotTurnToolRotationAroundItsOwnBindOriginIntoAnOrbit() {
        Vector3f referenceOrigin = new Vector3f(7.0F, -3.0F, 2.0F);
        OpenMatrix4f toolSkin = new OpenMatrix4f()
                .translate(referenceOrigin.x(), referenceOrigin.y(), referenceOrigin.z())
                .rotateDeg(83.0F, Vec3f.Z_AXIS)
                .translate(-referenceOrigin.x(), -referenceOrigin.y(), -referenceOrigin.z());
        OpenMatrix4f output = new OpenMatrix4f();

        AuxiliaryPoseMatrices.placeAtDisplayedFist(
                new OpenMatrix4f().rotateDeg(83.0F, Vec3f.Z_AXIS),
                new OpenMatrix4f(), toolSkin, referenceOrigin,
                new Vector3f(100.0F, 200.0F, 300.0F), output,
                new Vec4f(), new Vec4f(), new Vec4f());

        assertEquals(100.0F, output.m30, 0.0001F);
        assertEquals(200.0F, output.m31, 0.0001F);
        assertEquals(300.0F, output.m32, 0.0001F);
    }

    @Test
    void rejectsANonFiniteCorrectedTranslation() {
        OpenMatrix4f invalidToolSkin = new OpenMatrix4f();
        invalidToolSkin.m30 = Float.NaN;

        assertNull(AuxiliaryPoseMatrices.placeAtDisplayedFist(
                new OpenMatrix4f(), new OpenMatrix4f(), invalidToolSkin,
                new Vector3f(), new Vector3f(), new OpenMatrix4f(),
                new Vec4f(), new Vec4f(), new Vec4f()));
    }

    @Test
    void derivesDisplayedFistFromTheFinalSourceBoneSkin() {
        GeometryDocument geometry = new GeometryDocument();
        GeometryDocument.Bone forearm = faceBone(
                "RightForeArm", -1.0F, 1.0F, -4.0F, 0.0F);
        GeometryDocument.Bone locator = new GeometryDocument.Bone("RightHandLocator");
        locator.parentName("RightForeArm");
        locator.pivot(0.0F, -4.0F, 0.0F);
        geometry.add(forearm);
        geometry.add(locator);
        geometry.linkHierarchy();
        AuxiliaryBoneLayout layout = AuxiliaryBoneLayout.create(geometry);
        OpenMatrix4f[] poses = AuxiliaryPoseMatrices.allocate(HumanoidRig.EPIC_JOINT_COUNT);
        OpenMatrix4f[] toOrigin = AuxiliaryPoseMatrices.allocate(HumanoidRig.EPIC_JOINT_COUNT);
        OpenMatrix4f[] output = AuxiliaryPoseMatrices.allocate(layout.totalPoseCount());
        OpenMatrix4f[] wholeModelDeltas = AuxiliaryPoseMatrices.allocate(
                layout.entries().size());
        int sourcePose = layout.toolAnchorPoseIndex(HumanoidRig.RIGHT_TOOL);
        AuxiliaryBoneLayout.Entry source = layout.entries().stream()
                .filter(entry -> entry.poseIndex() == sourcePose)
                .findFirst().orElseThrow();
        assertSame(forearm, source.bone());
        wholeModelDeltas[source.auxiliaryIndex()].translate(3.0F, 5.0F, 7.0F);

        AuxiliaryPoseMatrices.compose(poses, toOrigin, layout, output,
                null, wholeModelDeltas, true);
        Vector3f displayed = new AuxiliaryPoseMatrices(layout).displayedFist(
                output, HumanoidRig.RIGHT_TOOL);

        assertEquals(3.0F, displayed.x(), 0.00001F);
        assertEquals(1.0F, displayed.y(), 0.00001F);
        assertEquals(7.0F, displayed.z(), 0.00001F);
    }

    @Test
    void attachesTheAuthoredHeldItemBranchToEpicFightsToolFrame() {
        GeometryDocument geometry = new GeometryDocument();
        GeometryDocument.Bone arm = faceBone("RightHand", -1.0F, 1.0F, -2.0F, 0.0F);
        GeometryDocument.Bone prop = faceBone("custom_prop", -0.5F, 0.5F, -1.0F, 0.0F);
        prop.parentName("RightHand");
        geometry.add(arm);
        geometry.add(prop);
        geometry.linkHierarchy();
        AuxiliaryBoneLayout layout = AuxiliaryBoneLayout.create(geometry);
        OpenMatrix4f[] poses = AuxiliaryPoseMatrices.allocate(HumanoidRig.EPIC_JOINT_COUNT);
        OpenMatrix4f[] toOrigin = AuxiliaryPoseMatrices.allocate(HumanoidRig.EPIC_JOINT_COUNT);
        OpenMatrix4f[] output = AuxiliaryPoseMatrices.allocate(layout.totalPoseCount());
        OpenMatrix4f[] parallel = AuxiliaryPoseMatrices.allocate(layout.entries().size());
        OpenMatrix4f[] heldItem = AuxiliaryPoseMatrices.allocate(layout.entries().size());
        OpenMatrix4f[] retargeted = new OpenMatrix4f[HumanoidRig.EPIC_JOINT_COUNT];
        AuxiliaryBoneLayout.Entry armEntry = layout.entryForBoneName("RightHand");
        AuxiliaryBoneLayout.Entry propEntry = layout.entryForBoneName("custom_prop");
        poses[HumanoidRig.RIGHT_HAND].translate(4.0F, 0.0F, 0.0F);
        retargeted[HumanoidRig.RIGHT_TOOL] = new OpenMatrix4f()
                .translate(10.0F, 2.0F, -3.0F)
                .rotateDeg(90.0F, Vec3f.Z_AXIS);
        parallel[propEntry.auxiliaryIndex()].scale(0.0F, 0.0F, 0.0F);
        heldItem[propEntry.auxiliaryIndex()].translate(1.0F, 0.0F, 0.0F)
                .rotateDeg(35.0F, Vec3f.X_AXIS);
        boolean[] replacement = new boolean[layout.entries().size()];
        boolean[] suppressParallel = new boolean[layout.entries().size()];
        int[] attachmentJoints = new int[layout.entries().size()];
        java.util.Arrays.fill(attachmentJoints, -1);
        replacement[propEntry.auxiliaryIndex()] = true;
        suppressParallel[propEntry.auxiliaryIndex()] = true;
        attachmentJoints[propEntry.auxiliaryIndex()] = HumanoidRig.RIGHT_TOOL;

        AuxiliaryPoseMatrices.compose(poses, toOrigin, layout, output,
                parallel, null, heldItem, retargeted, false, replacement,
                suppressParallel, attachmentJoints);

        assertEquals(4.0F, output[armEntry.poseIndex()].m30, 0.00001F);
        assertMatrixEquals(new OpenMatrix4f(retargeted[HumanoidRig.RIGHT_TOOL])
                        .mulBack(heldItem[propEntry.auxiliaryIndex()]),
                output[propEntry.poseIndex()]);
        assertEquals(10.0F, output[propEntry.poseIndex()].m30, 0.00001F,
                "Tool translation must remain outside the non-commuting prop rotation");
    }

    @Test
    void appliesParallelPhysicsBelowTheConnectedBowUpperBodyPose() {
        GeometryDocument geometry = new GeometryDocument();
        GeometryDocument.Bone head = faceBone("Head", -1.0F, 1.0F, -2.0F, 0.0F);
        GeometryDocument.Bone hair = faceBone("hair", -0.5F, 0.5F, -1.0F, 0.0F);
        hair.parentName("Head");
        geometry.add(head);
        geometry.add(hair);
        geometry.linkHierarchy();
        AuxiliaryBoneLayout layout = AuxiliaryBoneLayout.create(geometry);
        OpenMatrix4f[] poses = AuxiliaryPoseMatrices.allocate(HumanoidRig.EPIC_JOINT_COUNT);
        OpenMatrix4f[] toOrigin = AuxiliaryPoseMatrices.allocate(HumanoidRig.EPIC_JOINT_COUNT);
        OpenMatrix4f[] output = AuxiliaryPoseMatrices.allocate(layout.totalPoseCount());
        OpenMatrix4f[] parallel = AuxiliaryPoseMatrices.allocate(layout.entries().size());
        OpenMatrix4f[] heldItem = AuxiliaryPoseMatrices.allocate(layout.entries().size());
        OpenMatrix4f[] retargeted = new OpenMatrix4f[HumanoidRig.EPIC_JOINT_COUNT];
        AuxiliaryBoneLayout.Entry hairEntry = layout.entryForBoneName("hair");
        retargeted[HumanoidRig.TORSO] = new OpenMatrix4f()
                .translate(3.0F, -2.0F, 5.0F)
                .rotateDeg(40.0F, Vec3f.Z_AXIS);
        heldItem[hairEntry.auxiliaryIndex()]
                .translate(0.0F, 1.0F, 0.0F)
                .rotateDeg(65.0F, Vec3f.X_AXIS)
                .translate(0.0F, -1.0F, 0.0F);
        parallel[hairEntry.auxiliaryIndex()]
                .translate(0.0F, 0.5F, 0.0F)
                .rotateDeg(-25.0F, Vec3f.Y_AXIS)
                .translate(0.0F, -0.5F, 0.0F);
        boolean[] replacement = new boolean[layout.entries().size()];
        int[] attachmentJoints = new int[layout.entries().size()];
        java.util.Arrays.fill(attachmentJoints, -1);
        replacement[hairEntry.auxiliaryIndex()] = true;
        attachmentJoints[hairEntry.auxiliaryIndex()] = HumanoidRig.TORSO;

        AuxiliaryPoseMatrices.compose(poses, toOrigin, layout, output,
                parallel, null, heldItem, retargeted, false, replacement,
                null, attachmentJoints);

        assertMatrixEquals(new OpenMatrix4f(retargeted[HumanoidRig.TORSO])
                        .mulBack(heldItem[hairEntry.auxiliaryIndex()])
                        .mulBack(parallel[hairEntry.auxiliaryIndex()]),
                output[hairEntry.poseIndex()]);
    }

    @Test
    void fullBodyBlendPreservesExactSourceAndCompletedNormalEndpoints() {
        GeometryDocument geometry = new GeometryDocument();
        GeometryDocument.Bone hand = faceBone("RightHand", -1.0F, 1.0F, -2.0F, 0.0F);
        geometry.add(hand);
        geometry.linkHierarchy();
        AuxiliaryBoneLayout layout = AuxiliaryBoneLayout.create(geometry);
        AuxiliaryBoneLayout.Entry entry = layout.entries().get(0);
        OpenMatrix4f[] poses = AuxiliaryPoseMatrices.allocate(HumanoidRig.EPIC_JOINT_COUNT);
        OpenMatrix4f[] toOrigin = AuxiliaryPoseMatrices.allocate(HumanoidRig.EPIC_JOINT_COUNT);
        OpenMatrix4f[] expected = AuxiliaryPoseMatrices.allocate(layout.totalPoseCount());
        OpenMatrix4f[] output = AuxiliaryPoseMatrices.allocate(layout.totalPoseCount());
        OpenMatrix4f[] parallel = AuxiliaryPoseMatrices.allocate(1);
        OpenMatrix4f[] whole = AuxiliaryPoseMatrices.allocate(1);
        OpenMatrix4f[] held = AuxiliaryPoseMatrices.allocate(1);
        OpenMatrix4f[] retargeted = new OpenMatrix4f[HumanoidRig.EPIC_JOINT_COUNT];
        boolean[] replacement = {true};
        int[] attachmentJoints = {HumanoidRig.RIGHT_TOOL};
        parallel[0].translate(0.0F, 0.5F, 0.0F)
                .rotateDeg(20.0F, Vec3f.X_AXIS)
                .translate(0.0F, -0.5F, 0.0F);
        held[0].rotateDeg(-35.0F, Vec3f.Y_AXIS);
        whole[0].translate(2.0F, 3.0F, -4.0F);
        retargeted[HumanoidRig.RIGHT_TOOL] = new OpenMatrix4f()
                .translate(7.0F, -1.0F, 5.0F)
                .rotateDeg(65.0F, Vec3f.Z_AXIS);
        OpenMatrix4f[] source = {new OpenMatrix4f()
                .translate(-8.0F, 6.0F, 3.0F)
                .rotateDeg(125.0F, Vec3f.X_AXIS)};

        AuxiliaryPoseMatrices.compose(poses, toOrigin, layout, expected,
                parallel, whole, held, retargeted, false, replacement,
                null, attachmentJoints);
        AuxiliaryPoseMatrices.compose(poses, toOrigin, layout, output,
                parallel, whole, held, retargeted, false, replacement,
                null, attachmentJoints, source, 0.0F);

        assertMatrixEquals(expected[entry.poseIndex()], output[entry.poseIndex()]);
        AuxiliaryPoseMatrices.compose(poses, toOrigin, layout, output,
                parallel, whole, held, retargeted, false, replacement,
                null, attachmentJoints, source, 1.0F);
        assertMatrixEquals(source[0], output[entry.poseIndex()]);
    }

    @Test
    void fullBodyBlendUsesShortestRotationAndKeepsChildAttached() {
        GeometryDocument geometry = new GeometryDocument();
        GeometryDocument.Bone root = faceBone("Root", -1.0F, 1.0F, -1.0F, 1.0F);
        GeometryDocument.Bone arm = faceBone("RightArm", -0.5F, 0.5F, -0.5F, 0.5F);
        arm.parentName("Root");
        arm.pivot(0.0F, 2.0F, 0.0F);
        geometry.add(root);
        geometry.add(arm);
        geometry.linkHierarchy();
        AuxiliaryBoneLayout layout = AuxiliaryBoneLayout.create(geometry);
        AuxiliaryBoneLayout.Entry rootEntry = layout.entryForBoneName("Root");
        AuxiliaryBoneLayout.Entry armEntry = layout.entryForBoneName("RightArm");
        OpenMatrix4f[] poses = AuxiliaryPoseMatrices.allocate(HumanoidRig.EPIC_JOINT_COUNT);
        OpenMatrix4f[] toOrigin = AuxiliaryPoseMatrices.allocate(HumanoidRig.EPIC_JOINT_COUNT);
        OpenMatrix4f[] output = AuxiliaryPoseMatrices.allocate(layout.totalPoseCount());
        OpenMatrix4f[] retargeted = new OpenMatrix4f[HumanoidRig.EPIC_JOINT_COUNT];
        OpenMatrix4f sourceRoot = new OpenMatrix4f().rotateDeg(170.0F, Vec3f.Z_AXIS);
        OpenMatrix4f sourceArm = new OpenMatrix4f(sourceRoot)
                .translate(0.0F, 2.0F, 0.0F)
                .rotateDeg(40.0F, Vec3f.Z_AXIS)
                .translate(0.0F, -2.0F, 0.0F);
        OpenMatrix4f targetRoot = new OpenMatrix4f().rotateDeg(-170.0F, Vec3f.Z_AXIS);
        OpenMatrix4f targetArm = new OpenMatrix4f(targetRoot)
                .translate(0.0F, 2.0F, 0.0F)
                .rotateDeg(-40.0F, Vec3f.Z_AXIS)
                .translate(0.0F, -2.0F, 0.0F);
        OpenMatrix4f[] source = {sourceRoot, sourceArm};
        retargeted[HumanoidRig.ROOT] = targetRoot;
        retargeted[HumanoidRig.RIGHT_ARM] = targetArm;

        AuxiliaryPoseMatrices.compose(poses, toOrigin, layout, output,
                null, null, null, retargeted, false, null,
                null, null, source, 0.5F);

        Vec4f pivot = new Vec4f(0.0F, 2.0F, 0.0F, 1.0F);
        Vec4f rootPivot = new Vec4f();
        Vec4f armPivot = new Vec4f();
        OpenMatrix4f.transform(output[rootEntry.poseIndex()], pivot, rootPivot);
        OpenMatrix4f.transform(output[armEntry.poseIndex()], pivot, armPivot);
        assertEquals(rootPivot.x, armPivot.x, 0.0001F);
        assertEquals(rootPivot.y, armPivot.y, 0.0001F);
        assertEquals(rootPivot.z, armPivot.z, 0.0001F);

        Vec4f unitX = new Vec4f(1.0F, 0.0F, 0.0F, 1.0F);
        Vec4f rotated = new Vec4f();
        OpenMatrix4f.transform(output[rootEntry.poseIndex()], unitX, rotated);
        assertTrue(rotated.x < -0.99F,
                "170 to -170 degrees must pass through 180, not zero");
        assertEquals(0.0F, rotated.y, 0.02F);
    }

    @Test
    void fullBodyBlendKeepsZeroScaleHierarchyFinite() {
        GeometryDocument geometry = new GeometryDocument();
        GeometryDocument.Bone root = faceBone("Root", -1.0F, 1.0F, -1.0F, 1.0F);
        GeometryDocument.Bone arm = faceBone("RightArm", -0.5F, 0.5F, -0.5F, 0.5F);
        arm.parentName("Root");
        arm.pivot(0.0F, 2.0F, 0.0F);
        geometry.add(root);
        geometry.add(arm);
        geometry.linkHierarchy();
        AuxiliaryBoneLayout layout = AuxiliaryBoneLayout.create(geometry);
        OpenMatrix4f[] poses = AuxiliaryPoseMatrices.allocate(HumanoidRig.EPIC_JOINT_COUNT);
        OpenMatrix4f[] toOrigin = AuxiliaryPoseMatrices.allocate(HumanoidRig.EPIC_JOINT_COUNT);
        OpenMatrix4f[] output = AuxiliaryPoseMatrices.allocate(layout.totalPoseCount());
        OpenMatrix4f[] retargeted = new OpenMatrix4f[HumanoidRig.EPIC_JOINT_COUNT];
        OpenMatrix4f targetRoot = new OpenMatrix4f()
                .translate(3.0F, -2.0F, 1.0F)
                .rotateDeg(75.0F, Vec3f.Z_AXIS);
        OpenMatrix4f targetArm = new OpenMatrix4f(targetRoot)
                .translate(0.0F, 2.0F, 0.0F)
                .rotateDeg(-30.0F, Vec3f.X_AXIS)
                .translate(0.0F, -2.0F, 0.0F);
        OpenMatrix4f[] source = {
                new OpenMatrix4f().scale(0.0F, 0.0F, 0.0F),
                new OpenMatrix4f().scale(0.0F, 0.0F, 0.0F)
        };
        retargeted[HumanoidRig.ROOT] = targetRoot;
        retargeted[HumanoidRig.RIGHT_ARM] = targetArm;

        AuxiliaryPoseMatrices.compose(poses, toOrigin, layout, output,
                null, null, null, retargeted, false, null,
                null, null, source, 0.5F);

        for (AuxiliaryBoneLayout.Entry entry : layout.entries()) {
            assertFinite(output[entry.poseIndex()]);
        }
    }

    @Test
    void fullBodyBlendKeepsNegativeScaleContinuousImmediatelyAfterSourceEndpoint() {
        GeometryDocument geometry = new GeometryDocument();
        GeometryDocument.Bone root = faceBone("Root", -1.0F, 1.0F, -1.0F, 1.0F);
        root.pivot(2.0F, 3.0F, -1.0F);
        geometry.add(root);
        geometry.linkHierarchy();
        float horizontalScale = 1.4F;
        float verticalScale = 0.8F;
        AuxiliaryBoneLayout layout = AuxiliaryBoneLayout.create(
                geometry, horizontalScale, verticalScale);
        AuxiliaryBoneLayout.Entry entry = layout.entryForBoneName("Root");
        OpenMatrix4f[] poses = AuxiliaryPoseMatrices.allocate(HumanoidRig.EPIC_JOINT_COUNT);
        OpenMatrix4f[] toOrigin = AuxiliaryPoseMatrices.allocate(HumanoidRig.EPIC_JOINT_COUNT);
        OpenMatrix4f[] output = AuxiliaryPoseMatrices.allocate(layout.totalPoseCount());
        OpenMatrix4f[] retargeted = new OpenMatrix4f[HumanoidRig.EPIC_JOINT_COUNT];
        Matrix4f sourceRaw = new Matrix4f()
                .translate(2.0F, 3.0F, -1.0F)
                .rotateZ((float) Math.toRadians(35.0D))
                .scale(-1.0F, 1.5F, 0.75F)
                .translate(-2.0F, -3.0F, 1.0F);
        Matrix4f targetRaw = new Matrix4f()
                .translate(2.0F, 3.0F, -1.0F)
                .rotateX((float) Math.toRadians(-20.0D))
                .scale(0.8F, 1.1F, 1.2F)
                .translate(-2.0F, -3.0F, 1.0F);
        OpenMatrix4f source = scaledSkin(sourceRaw, horizontalScale, verticalScale);
        retargeted[entry.anchorJoint()] = scaledSkin(
                targetRaw, horizontalScale, verticalScale);

        AuxiliaryPoseMatrices.compose(poses, toOrigin, layout, output,
                null, null, null, retargeted, false, null,
                null, null, new OpenMatrix4f[]{source}, 0.999F);

        OpenMatrix4f almostSource = output[entry.poseIndex()];
        assertTrue(linearDeterminant(almostSource) < 0.0F,
                "the reflected source must retain its handedness just below weight 1");
        assertMatrixNear(source, almostSource, 0.02F);
    }

    @Test
    void fullBodyBlendUsesTheScaledBedrockPivotInsteadOfMovingAroundTheOrigin() {
        GeometryDocument geometry = new GeometryDocument();
        GeometryDocument.Bone root = faceBone("Root", -1.0F, 1.0F, -1.0F, 1.0F);
        root.pivot(3.0F, 5.0F, -2.0F);
        geometry.add(root);
        geometry.linkHierarchy();
        float horizontalScale = 1.6F;
        float verticalScale = 0.7F;
        AuxiliaryBoneLayout layout = AuxiliaryBoneLayout.create(
                geometry, horizontalScale, verticalScale);
        AuxiliaryBoneLayout.Entry entry = layout.entryForBoneName("Root");
        OpenMatrix4f[] poses = AuxiliaryPoseMatrices.allocate(HumanoidRig.EPIC_JOINT_COUNT);
        OpenMatrix4f[] toOrigin = AuxiliaryPoseMatrices.allocate(HumanoidRig.EPIC_JOINT_COUNT);
        OpenMatrix4f[] output = AuxiliaryPoseMatrices.allocate(layout.totalPoseCount());
        Matrix4f sourceRaw = new Matrix4f()
                .translate(3.0F, 5.0F, -2.0F)
                .rotateZ((float) Math.toRadians(110.0D))
                .translate(-3.0F, -5.0F, 2.0F);
        OpenMatrix4f source = scaledSkin(sourceRaw, horizontalScale, verticalScale);

        AuxiliaryPoseMatrices.compose(poses, toOrigin, layout, output,
                null, null, null, null, false, null,
                null, null, new OpenMatrix4f[]{source}, 0.5F);

        Vec4f scaledPivot = new Vec4f(3.0F * horizontalScale,
                5.0F * verticalScale, -2.0F * horizontalScale, 1.0F);
        Vec4f transformed = new Vec4f();
        OpenMatrix4f.transform(output[entry.poseIndex()], scaledPivot, transformed);
        assertEquals(scaledPivot.x, transformed.x, 0.0001F);
        assertEquals(scaledPivot.y, transformed.y, 0.0001F);
        assertEquals(scaledPivot.z, transformed.z, 0.0001F);
    }

    @Test
    void fullBodyBlendKeepsANonUniformlyScaledParentAndRotatedChildContinuous() {
        GeometryDocument geometry = new GeometryDocument();
        GeometryDocument.Bone root = faceBone("Root", -1.0F, 1.0F, -1.0F, 1.0F);
        GeometryDocument.Bone arm = faceBone("RightArm", -0.5F, 0.5F, -0.5F, 0.5F);
        arm.parentName("Root");
        arm.pivot(0.0F, 2.0F, 0.0F);
        geometry.add(root);
        geometry.add(arm);
        geometry.linkHierarchy();
        float horizontalScale = 1.3F;
        float verticalScale = 0.85F;
        AuxiliaryBoneLayout layout = AuxiliaryBoneLayout.create(
                geometry, horizontalScale, verticalScale);
        AuxiliaryBoneLayout.Entry rootEntry = layout.entryForBoneName("Root");
        AuxiliaryBoneLayout.Entry armEntry = layout.entryForBoneName("RightArm");
        OpenMatrix4f[] poses = AuxiliaryPoseMatrices.allocate(HumanoidRig.EPIC_JOINT_COUNT);
        OpenMatrix4f[] toOrigin = AuxiliaryPoseMatrices.allocate(HumanoidRig.EPIC_JOINT_COUNT);
        OpenMatrix4f[] output = AuxiliaryPoseMatrices.allocate(layout.totalPoseCount());
        OpenMatrix4f[] retargeted = new OpenMatrix4f[HumanoidRig.EPIC_JOINT_COUNT];

        Matrix4f sourceRootWorld = new Matrix4f()
                .rotateZ((float) Math.toRadians(25.0D))
                .scale(2.0F, 0.5F, 1.0F);
        Matrix4f sourceArmWorld = new Matrix4f(sourceRootWorld)
                .translate(0.0F, 2.0F, 0.0F)
                .rotateX((float) Math.toRadians(55.0D))
                .translate(0.0F, -2.0F, 0.0F);
        Matrix4f targetRootWorld = new Matrix4f()
                .rotateZ((float) Math.toRadians(-35.0D))
                .scale(0.75F, 1.4F, 1.0F);
        Matrix4f targetArmWorld = new Matrix4f(targetRootWorld)
                .translate(0.0F, 2.0F, 0.0F)
                .rotateX((float) Math.toRadians(-30.0D))
                .translate(0.0F, -2.0F, 0.0F);
        OpenMatrix4f[] source = {
                scaledSkin(sourceRootWorld, horizontalScale, verticalScale),
                scaledSkin(sourceArmWorld, horizontalScale, verticalScale)
        };
        retargeted[rootEntry.anchorJoint()] = scaledSkin(
                targetRootWorld, horizontalScale, verticalScale);
        retargeted[armEntry.anchorJoint()] = scaledSkin(
                targetArmWorld, horizontalScale, verticalScale);

        AuxiliaryPoseMatrices.compose(poses, toOrigin, layout, output,
                null, null, null, retargeted, false, null,
                null, null, source, 0.999F);
        assertMatrixNear(source[0], output[rootEntry.poseIndex()], 0.02F);
        assertMatrixNear(source[1], output[armEntry.poseIndex()], 0.04F);

        AuxiliaryPoseMatrices.compose(poses, toOrigin, layout, output,
                null, null, null, retargeted, false, null,
                null, null, source, 0.5F);
        Vec4f pivot = new Vec4f(0.0F, 2.0F * verticalScale, 0.0F, 1.0F);
        Vec4f rootPivot = new Vec4f();
        Vec4f armPivot = new Vec4f();
        OpenMatrix4f.transform(output[rootEntry.poseIndex()], pivot, rootPivot);
        OpenMatrix4f.transform(output[armEntry.poseIndex()], pivot, armPivot);
        assertEquals(rootPivot.x, armPivot.x, 0.0002F);
        assertEquals(rootPivot.y, armPivot.y, 0.0002F);
        assertEquals(rootPivot.z, armPivot.z, 0.0002F);
    }

    @Test
    void invalidFullBodySnapshotFallsBackToTheAlreadyComposedTarget() {
        GeometryDocument geometry = new GeometryDocument();
        geometry.add(faceBone("Root", -1.0F, 1.0F, -1.0F, 1.0F));
        geometry.linkHierarchy();
        AuxiliaryBoneLayout layout = AuxiliaryBoneLayout.create(geometry);
        AuxiliaryBoneLayout.Entry entry = layout.entries().get(0);
        OpenMatrix4f[] poses = AuxiliaryPoseMatrices.allocate(HumanoidRig.EPIC_JOINT_COUNT);
        OpenMatrix4f[] toOrigin = AuxiliaryPoseMatrices.allocate(HumanoidRig.EPIC_JOINT_COUNT);
        OpenMatrix4f[] expected = AuxiliaryPoseMatrices.allocate(layout.totalPoseCount());
        OpenMatrix4f[] output = AuxiliaryPoseMatrices.allocate(layout.totalPoseCount());
        OpenMatrix4f[] retargeted = new OpenMatrix4f[HumanoidRig.EPIC_JOINT_COUNT];
        retargeted[entry.anchorJoint()] = new OpenMatrix4f()
                .translate(2.0F, -3.0F, 4.0F)
                .rotateDeg(35.0F, Vec3f.Y_AXIS);
        OpenMatrix4f invalid = new OpenMatrix4f();
        invalid.m00 = Float.NaN;

        AuxiliaryPoseMatrices.compose(poses, toOrigin, layout, expected,
                null, null, null, retargeted, false, null, null, null);
        AuxiliaryPoseMatrices.compose(poses, toOrigin, layout, output,
                null, null, null, retargeted, false, null, null, null,
                new OpenMatrix4f[]{invalid}, 0.5F);

        assertMatrixEquals(expected[entry.poseIndex()], output[entry.poseIndex()]);
    }

    private static OpenMatrix4f[] translatedMatrices(int count, float multiplier) {
        OpenMatrix4f[] result = AuxiliaryPoseMatrices.allocate(count);
        for (int index = 0; index < count; index++) {
            result[index].translate(index * multiplier, multiplier, -multiplier);
        }
        return result;
    }

    private static GeometryDocument.Bone faceBone(String name, float minX, float maxX,
                                                   float minY, float maxY) {
        GeometryDocument.Bone bone = new GeometryDocument.Bone(name);
        bone.faces().add(new GeometryDocument.Face(new Vector3f[]{
                new Vector3f(minX, minY, 0.0F), new Vector3f(maxX, minY, 0.0F),
                new Vector3f(maxX, maxY, 0.0F), new Vector3f(minX, maxY, 0.0F)},
                new float[][]{{0, 0}, {1, 0}, {1, 1}, {0, 1}},
                new Vector3f(0, 0, 1)));
        return bone;
    }

    private static OpenMatrix4f scaledSkin(Matrix4f rawSkin, float horizontalScale,
                                           float verticalScale) {
        Matrix4f scale = new Matrix4f().scaling(
                horizontalScale, verticalScale, horizontalScale);
        Matrix4f scaled = new Matrix4f(scale).mul(rawSkin)
                .mul(new Matrix4f().scaling(1.0F / horizontalScale,
                        1.0F / verticalScale, 1.0F / horizontalScale));
        OpenMatrix4f result = new OpenMatrix4f();
        result.m00 = scaled.m00();
        result.m01 = scaled.m01();
        result.m02 = scaled.m02();
        result.m03 = scaled.m03();
        result.m10 = scaled.m10();
        result.m11 = scaled.m11();
        result.m12 = scaled.m12();
        result.m13 = scaled.m13();
        result.m20 = scaled.m20();
        result.m21 = scaled.m21();
        result.m22 = scaled.m22();
        result.m23 = scaled.m23();
        result.m30 = scaled.m30();
        result.m31 = scaled.m31();
        result.m32 = scaled.m32();
        result.m33 = scaled.m33();
        return result;
    }

    private static float linearDeterminant(OpenMatrix4f matrix) {
        return matrix.m00 * (matrix.m11 * matrix.m22 - matrix.m12 * matrix.m21)
                - matrix.m10 * (matrix.m01 * matrix.m22 - matrix.m02 * matrix.m21)
                + matrix.m20 * (matrix.m01 * matrix.m12 - matrix.m02 * matrix.m11);
    }

    private static void assertMatrixNear(OpenMatrix4f expected, OpenMatrix4f actual,
                                         float epsilon) {
        assertEquals(expected.m00, actual.m00, epsilon);
        assertEquals(expected.m01, actual.m01, epsilon);
        assertEquals(expected.m02, actual.m02, epsilon);
        assertEquals(expected.m10, actual.m10, epsilon);
        assertEquals(expected.m11, actual.m11, epsilon);
        assertEquals(expected.m12, actual.m12, epsilon);
        assertEquals(expected.m20, actual.m20, epsilon);
        assertEquals(expected.m21, actual.m21, epsilon);
        assertEquals(expected.m22, actual.m22, epsilon);
        assertEquals(expected.m30, actual.m30, epsilon);
        assertEquals(expected.m31, actual.m31, epsilon);
        assertEquals(expected.m32, actual.m32, epsilon);
    }

    private static void assertMatrixEquals(OpenMatrix4f expected, OpenMatrix4f actual) {
        assertEquals(expected.m00, actual.m00, 0.00001F);
        assertEquals(expected.m01, actual.m01, 0.00001F);
        assertEquals(expected.m02, actual.m02, 0.00001F);
        assertEquals(expected.m03, actual.m03, 0.00001F);
        assertEquals(expected.m10, actual.m10, 0.00001F);
        assertEquals(expected.m11, actual.m11, 0.00001F);
        assertEquals(expected.m12, actual.m12, 0.00001F);
        assertEquals(expected.m13, actual.m13, 0.00001F);
        assertEquals(expected.m20, actual.m20, 0.00001F);
        assertEquals(expected.m21, actual.m21, 0.00001F);
        assertEquals(expected.m22, actual.m22, 0.00001F);
        assertEquals(expected.m23, actual.m23, 0.00001F);
        assertEquals(expected.m30, actual.m30, 0.00001F);
        assertEquals(expected.m31, actual.m31, 0.00001F);
        assertEquals(expected.m32, actual.m32, 0.00001F);
        assertEquals(expected.m33, actual.m33, 0.00001F);
    }

    private static void assertFinite(OpenMatrix4f matrix) {
        assertTrue(Float.isFinite(matrix.m00));
        assertTrue(Float.isFinite(matrix.m01));
        assertTrue(Float.isFinite(matrix.m02));
        assertTrue(Float.isFinite(matrix.m03));
        assertTrue(Float.isFinite(matrix.m10));
        assertTrue(Float.isFinite(matrix.m11));
        assertTrue(Float.isFinite(matrix.m12));
        assertTrue(Float.isFinite(matrix.m13));
        assertTrue(Float.isFinite(matrix.m20));
        assertTrue(Float.isFinite(matrix.m21));
        assertTrue(Float.isFinite(matrix.m22));
        assertTrue(Float.isFinite(matrix.m23));
        assertTrue(Float.isFinite(matrix.m30));
        assertTrue(Float.isFinite(matrix.m31));
        assertTrue(Float.isFinite(matrix.m32));
        assertTrue(Float.isFinite(matrix.m33));
    }
}
