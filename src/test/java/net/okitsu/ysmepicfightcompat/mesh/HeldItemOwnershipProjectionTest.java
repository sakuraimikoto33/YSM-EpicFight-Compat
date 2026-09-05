package net.okitsu.ysmepicfightcompat.mesh;

import net.okitsu.ysmepicfightcompat.geometry.GeometryDocument;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import yesman.epicfight.api.animation.Joint;
import yesman.epicfight.api.model.Armature;
import yesman.epicfight.api.utils.math.OpenMatrix4f;
import yesman.epicfight.api.utils.math.Vec3f;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Ownership checks observe a rendered grip, rather than reproducing its matrix formula. */
class HeldItemOwnershipProjectionTest {
    private static final int[] TOOLS = {HumanoidRig.RIGHT_TOOL, HumanoidRig.LEFT_TOOL};
    private static final float EPSILON = 0.00005F;

    @Test
    void fullyOwnedYsmHandsDoNotInheritTheUnseenEpicFightAnimation() {
        assertStableYsmGrip(1.0F, 1.0F, 0.0F);
    }

    @Test
    void fixedYsmHandsRemainStableWithUnequalModelScalesAndRotatedBinds() {
        assertStableYsmGrip(0.7F, 0.9F, 27.0F);
    }

    @Test
    void fullyYsmOwnedGripsIgnoreCollapsedBackgroundEpicFightTools() {
        AuxiliaryBoneLayout layout = layout(0.7F, 0.9F, 27.0F);
        Armature armature = armature();
        AuxiliaryPoseMatrices matrices = new AuxiliaryPoseMatrices(layout);
        OpenMatrix4f[] whole = deltas(layout,
                new Matrix4f().rotateZ((float) Math.toRadians(31.0F)));
        OpenMatrix4f[] poses = bindPoses(armature);
        OpenMatrix4f[] complete = compose(matrices, armature, poses,
                whole, true, null, null, 0.0F);
        OpenMatrix4f[] baseline = copy(matrices.displayedAttachmentPoses(
                armature, complete, poses, 1.0F, false, false));

        poses = livePoses(armature, 0.6F);
        for (int tool : TOOLS) {
            poses[tool].scale(0.0F, 0.0F, 0.0F);
        }
        complete = compose(matrices, armature, poses,
                whole, true, null, null, 0.0F);
        OpenMatrix4f[] actual = matrices.displayedAttachmentPoses(
                armature, complete, poses, 1.0F, false, false);
        assertNotNull(actual);
        for (int tool : TOOLS) {
            assertFrame(baseline[tool], actual[tool], EPSILON);
            assertTrue(new Vector3f(actual[tool].m20,
                    actual[tool].m21, actual[tool].m22).length() > 0.9F,
                    "An invisible background EF Tool must not hide a YSM-owned grip");
        }
    }

    @Test
    void epicFightChestAndArmAttachmentsKeepTheirRotationAtUnequalModelScales() {
        AuxiliaryBoneLayout layout = chestAndArmLayout();
        Armature armature = armature(true);
        AuxiliaryPoseMatrices matrices = new AuxiliaryPoseMatrices(layout);
        OpenMatrix4f[] bind = bindPoses(armature);
        OpenMatrix4f[] poses = copy(bind);
        OpenMatrix4f motion = new OpenMatrix4f()
                .translate(0.04F, 0.11F, -0.08F)
                .rotateDeg(38.0F, Vec3f.X_AXIS).rotateDeg(-24.0F, Vec3f.Z_AXIS);
        for (int joint = 0; joint < poses.length; joint++) {
            poses[joint].load(motion).mulBack(bind[joint]);
        }
        OpenMatrix4f[] complete = compose(matrices, armature, poses,
                null, false, null, null, 0.0F);
        OpenMatrix4f[] actual = matrices.displayedAttachmentPoses(
                armature, complete, poses, 1.0F, false, false);
        assertNotNull(actual);
        for (int joint : new int[]{HumanoidRig.CHEST, HumanoidRig.RIGHT_ARM}) {
            // Model proportions change attachment position, not the EF joint's axes.
            assertDirections(poses[joint], actual[joint], EPSILON);
            AuxiliaryBoneLayout.Entry source = layout.attachmentEntry(joint);
            assertNotNull(source);
            Vector3f pivot = layout.attachmentPivot(joint);
            assertNotNull(pivot);
            Vector3f bodyPoint = OpenMatrix4f.exportToMojangMatrix(
                    complete[source.poseIndex()]).transformPosition(pivot);
            assertVector(bodyPoint, origin(actual[joint]), EPSILON);
        }
    }

    @Test
    void fullyYsmChestAttachmentUsesTheRenderedRotationAndNeutralEpicBindAxes() {
        AuxiliaryBoneLayout layout = chestAndArmLayout();
        Armature armature = armature(true);
        AuxiliaryPoseMatrices matrices = new AuxiliaryPoseMatrices(layout);
        OpenMatrix4f[] bind = bindPoses(armature);
        OpenMatrix4f[] poses = copy(bind);
        poses[HumanoidRig.CHEST].rotateDeg(72.0F, Vec3f.Y_AXIS);
        Matrix4f authoredMotion = new Matrix4f()
                .translate(0.05F, -0.13F, 0.07F)
                .rotateX((float) Math.toRadians(-37.0F))
                .rotateZ((float) Math.toRadians(29.0F));
        OpenMatrix4f[] complete = compose(matrices, armature, poses,
                deltas(layout, authoredMotion), true, null, null, 0.0F);
        OpenMatrix4f[] actual = matrices.displayedAttachmentPoses(
                armature, complete, poses, 1.0F, false, false);
        assertNotNull(actual);
        OpenMatrix4f expectedAxes = OpenMatrix4f.importFromMojangMatrix(
                new Matrix4f(authoredMotion).mul(OpenMatrix4f.exportToMojangMatrix(
                        bind[HumanoidRig.CHEST])));
        assertDirections(expectedAxes, actual[HumanoidRig.CHEST], EPSILON);
        AuxiliaryBoneLayout.Entry chest = layout.attachmentEntry(HumanoidRig.CHEST);
        assertNotNull(chest);
        Vector3f pivot = layout.attachmentPivot(HumanoidRig.CHEST);
        assertNotNull(pivot);
        Vector3f bodyPoint = OpenMatrix4f.exportToMojangMatrix(
                complete[chest.poseIndex()]).transformPosition(pivot);
        assertVector(bodyPoint, origin(actual[HumanoidRig.CHEST]), EPSILON);
    }

    private static void assertStableYsmGrip(float horizontal, float vertical,
                                           float bindDegrees) {
        AuxiliaryBoneLayout layout = layout(horizontal, vertical, bindDegrees);
        Armature armature = armature();
        AuxiliaryPoseMatrices matrices = new AuxiliaryPoseMatrices(layout);
        OpenMatrix4f[] fixedYsm = deltas(layout, new Matrix4f()
                .translate(0.03F, -0.18F, 0.05F)
                .rotateX((float) Math.toRadians(-43.0F)));
        OpenMatrix4f[] baselineGrip = null;
        OpenMatrix4f[] baselineBody = null;

        // The invisible EF animator continues bobbing the body and independently
        // rotating/translating/scaling each Tool. A fully YSM-owned hand is fixed.
        for (float phase : new float[]{0.0F, 0.2F, 0.6F, 1.0F, -0.45F}) {
            OpenMatrix4f[] poses = livePoses(armature, phase);
            OpenMatrix4f[] inputSnapshot = copy(poses);
            OpenMatrix4f[] complete = compose(matrices, armature, poses,
                    fixedYsm, true, null, null, 0.0F);
            OpenMatrix4f[] grip = copy(matrices.displayedAttachmentPoses(
                    armature, complete, poses, 1.0F, false, false));
            if (baselineGrip == null) {
                baselineGrip = grip;
                baselineBody = copy(complete);
            }
            for (int tool : TOOLS) {
                assertFrame(baselineGrip[tool], grip[tool], EPSILON);
                Vector3f fist = matrices.displayedFist(complete, tool);
                assertNotNull(fist);
                assertVector(fist, origin(grip[tool]), EPSILON);
                int handIndex = layout.toolAnchorPoseIndex(tool);
                assertFrame(baselineBody[handIndex], complete[handIndex], EPSILON);
            }
            for (int joint = 0; joint < poses.length; joint++) {
                assertFrame(inputSnapshot[joint], poses[joint], EPSILON);
            }
        }
    }

    @Test
    void epicFightOwnedHandsRetainLiveToolMotionAndTheVersion030Grip() {
        AuxiliaryBoneLayout layout = layout(0.7F, 0.9F, 27.0F);
        Armature armature = armature();
        AuxiliaryPoseMatrices matrices = new AuxiliaryPoseMatrices(layout);
        OpenMatrix4f[] firstGrip = null;
        boolean observedDifferentGrip = false;

        for (float phase : new float[]{0.0F, 0.3F, 0.8F, -0.4F}) {
            OpenMatrix4f[] poses = livePoses(armature, phase);
            OpenMatrix4f[] complete = compose(matrices, armature, poses,
                    null, false, null, null, 0.0F);
            OpenMatrix4f[] actual = copy(matrices.displayedAttachmentPoses(
                    armature, complete, poses, 1.0F, false, false));
            for (int tool : TOOLS) {
                OpenMatrix4f previousGrip = matrices.heldItemPose(armature, poses,
                        tool, matrices.displayedFist(complete, tool));
                assertNotNull(previousGrip);
                assertFrame(previousGrip, actual[tool], EPSILON);
            }
            if (firstGrip == null) {
                firstGrip = actual;
            } else {
                observedDifferentGrip |= origin(firstGrip[HumanoidRig.RIGHT_TOOL])
                        .distance(origin(actual[HumanoidRig.RIGHT_TOOL])) > 0.01F;
            }
        }
        assertTrue(observedDifferentGrip, "The live EF animation must not be frozen");
    }

    @Test
    void aSelectiveHandOverrideDoesNotSuppressTheOtherHandsEpicFightGrip() {
        AuxiliaryBoneLayout layout = layout(1.0F, 1.0F, 0.0F);
        Armature armature = armature();
        AuxiliaryPoseMatrices matrices = new AuxiliaryPoseMatrices(layout);
        boolean[] replace = new boolean[layout.entries().size()];
        AuxiliaryBoneLayout.Entry rightHand = layout.attachmentEntry(HumanoidRig.RIGHT_TOOL);
        assertNotNull(rightHand);
        replace[rightHand.auxiliaryIndex()] = true;
        OpenMatrix4f firstRight = null;
        OpenMatrix4f firstLeft = null;
        boolean leftMoved = false;

        for (float phase : new float[]{0.0F, 0.5F, 1.0F}) {
            OpenMatrix4f[] poses = livePoses(armature, phase);
            OpenMatrix4f[] complete = compose(matrices, armature, poses,
                    null, false, replace, null, 0.0F);
            OpenMatrix4f[] actual = copy(matrices.displayedAttachmentPoses(
                    armature, complete, poses, 1.0F, false, false));
            if (firstRight == null) {
                firstRight = actual[HumanoidRig.RIGHT_TOOL];
                firstLeft = actual[HumanoidRig.LEFT_TOOL];
            } else {
                assertFrame(firstRight, actual[HumanoidRig.RIGHT_TOOL], EPSILON);
                leftMoved |= origin(firstLeft)
                        .distance(origin(actual[HumanoidRig.LEFT_TOOL])) > 0.01F;
            }
            OpenMatrix4f expectedLeft = matrices.heldItemPose(armature, poses,
                    HumanoidRig.LEFT_TOOL,
                    matrices.displayedFist(complete, HumanoidRig.LEFT_TOOL));
            assertNotNull(expectedLeft);
            assertFrame(expectedLeft, actual[HumanoidRig.LEFT_TOOL], EPSILON);
        }
        assertTrue(leftMoved, "The opposite EF-owned hand must remain live");
    }

    @Test
    void fullBodyExitReleasesTheGripAtTheSameWeightWithoutReblendingTheHand() {
        AuxiliaryBoneLayout layout = layout(0.7F, 0.9F, 27.0F);
        Armature armature = armature();
        AuxiliaryPoseMatrices matrices = new AuxiliaryPoseMatrices(layout);
        OpenMatrix4f[] poses = bindPoses(armature);
        // Only the EF Tool moves: both candidate body poses are exactly the same.
        // This isolates grip ownership from any body animation or transition delay.
        for (int tool : TOOLS) {
            poses[tool].translate(0.12F, -0.04F, 0.05F)
                    .rotateDeg(90.0F, Vec3f.Y_AXIS);
        }
        OpenMatrix4f[] sourceComplete = compose(matrices, armature, poses,
                null, true, null, null, 0.0F);
        OpenMatrix4f[] ysmGrip = copy(matrices.displayedAttachmentPoses(
                armature, sourceComplete, poses, 1.0F, false, false));
        OpenMatrix4f[] source = auxiliarySnapshot(layout, sourceComplete);
        OpenMatrix4f[] targetComplete = compose(matrices, armature, poses,
                null, false, null, null, 0.0F);
        OpenMatrix4f[] epicGrip = copy(matrices.displayedAttachmentPoses(
                armature, targetComplete, poses, 1.0F, false, false));
        OpenMatrix4f[] bodySnapshot = copy(targetComplete);

        for (float ysmWeight : new float[]{1.0F, 0.999999F, 0.5F, 0.000001F, 0.0F}) {
            OpenMatrix4f[] complete = compose(matrices, armature, poses,
                    null, false, null, source, ysmWeight);
            OpenMatrix4f[] actual = copy(matrices.displayedAttachmentPoses(
                    armature, complete, poses, 1.0F, false, false));
            for (int tool : TOOLS) {
                int handIndex = layout.toolAnchorPoseIndex(tool);
                assertFrame(bodySnapshot[handIndex], complete[handIndex], EPSILON);
                Vector3f expectedOrigin = origin(ysmGrip[tool])
                        .lerp(origin(epicGrip[tool]), 1.0F - ysmWeight);
                assertVector(expectedOrigin, origin(actual[tool]), EPSILON);
                if (ysmWeight >= 0.999999F) {
                    assertFrame(ysmGrip[tool], actual[tool], EPSILON);
                } else if (ysmWeight <= 0.000001F) {
                    assertFrame(epicGrip[tool], actual[tool], EPSILON);
                } else {
                    // A 90-degree single-axis grip change has a 45-degree midpoint.
                    Vector3f halfway = zDirection(ysmGrip[tool])
                            .add(zDirection(epicGrip[tool])).normalize();
                    assertVector(halfway, zDirection(actual[tool]), EPSILON);
                }
            }
        }
    }

    @Test
    void movementEntryAndExitUseTheExistingThreeTickBodyClockForBothGrips() {
        MovementFixture fixture = new MovementFixture();
        fixture.frame(0.0D, null, false, false, 2.0F, 2.0F, 1.0F);
        fixture.frame(1.0D, "run", true, false, 10.0F, 2.0F, 1.0F);
        fixture.frame(2.5D, "run", true, false, 14.0F, 8.0F, 0.5F);
        fixture.frame(4.0D, "run", true, false, 14.0F, 14.0F, 0.0F);
        fixture.frame(5.0D, null, false, false, 30.0F, 14.0F, 0.0F);
        fixture.frame(6.5D, null, false, false, 30.0F, 22.0F, 0.5F);
        fixture.frame(8.0D, null, false, false, 30.0F, 30.0F, 1.0F);
    }

    @Test
    void changingYsmMovementDuringEntryDoesNotRestartTheGripOwnershipClock() {
        MovementFixture fixture = new MovementFixture();
        fixture.frame(0.0D, null, false, false, 2.0F, 2.0F, 1.0F);
        fixture.frame(1.0D, "ladder_down", true, false, 10.0F, 2.0F, 1.0F);
        fixture.frame(2.0D, "ladder_idle", true, false,
                20.0F, 8.0F, 2.0F / 3.0F);
        fixture.frame(3.0D, "ladder_down", true, false,
                14.0F, 10.0F, 1.0F / 3.0F);
        fixture.frame(4.0D, "ladder_idle", true, false, 20.0F, 20.0F, 0.0F);
    }

    @Test
    void anEpicFightActionTakesBothGripsImmediatelyDuringAnOwnershipBlend() {
        MovementFixture fixture = new MovementFixture();
        fixture.frame(0.0D, null, false, false, 2.0F, 2.0F, 1.0F);
        fixture.frame(1.0D, "run", true, false, 10.0F, 2.0F, 1.0F);
        fixture.frame(2.5D, "run", true, false, 14.0F, 8.0F, 0.5F);
        fixture.frame(3.0D, null, false, true, 100.0F, 100.0F, 1.0F);
        fixture.frame(4.0D, "run", true, false, 20.0F, 100.0F, 1.0F);
        fixture.frame(5.5D, "run", true, false, 20.0F, 60.0F, 0.5F);
    }

    private static final class MovementFixture {
        private final AuxiliaryBoneLayout layout = layout(1.0F, 1.0F, 0.0F);
        private final Armature armature = armature();
        private final AuxiliaryPoseMatrices matrices = new AuxiliaryPoseMatrices(layout);
        private final MovementPoseTransition.Channel channel = new MovementPoseTransition.Channel();

        private void frame(double tick, String key, boolean ysmOwned, boolean action,
                           float targetX, float displayedX, float epicGrip) {
            // Every render starts with composition, just like CompatHumanoidMesh.
            // Channel must blend this frame's ownership, not its previous output again.
            OpenMatrix4f[] complete = compose(matrices, armature, bindPoses(armature),
                    deltas(layout, new Matrix4f().translate(targetX, 0.0F, 0.0F)),
                    ysmOwned, null, null, 0.0F);
            channel.apply(tick, key, action, matrices, complete);
            for (int tool : TOOLS) {
                assertEquals(epicGrip, matrices.epicGripWeight(tool), EPSILON);
                int handIndex = layout.toolAnchorPoseIndex(tool);
                assertEquals(displayedX, complete[handIndex].m30, EPSILON,
                        "Grip metadata must not change the existing body transition");
            }
        }
    }

    private static OpenMatrix4f[] compose(AuxiliaryPoseMatrices matrices, Armature armature,
                                          OpenMatrix4f[] poses, OpenMatrix4f[] whole,
                                          boolean replace, boolean[] selective,
                                          OpenMatrix4f[] source, float sourceWeight) {
        OpenMatrix4f[] complete = matrices.compose(armature, poses, null, whole,
                null, replace, selective, null, null, source, sourceWeight);
        assertNotNull(complete);
        return complete;
    }

    private static AuxiliaryBoneLayout layout(float horizontal, float vertical,
                                               float handBindDegrees) {
        GeometryDocument geometry = new GeometryDocument();
        for (String side : new String[]{"Right", "Left"}) {
            float x = side.equals("Right") ? -0.3F : 0.3F;
            GeometryDocument.Bone hand = new GeometryDocument.Bone(side + "Hand");
            hand.pivot(x, 1.0F, 0.0F);
            hand.rotation(0.0F, 0.0F, (float) Math.toRadians(handBindDegrees));
            hand.faces().add(new GeometryDocument.Face(new Vector3f[]{
                    new Vector3f(x - 0.06F, 0.65F, 0.0F),
                    new Vector3f(x + 0.06F, 0.65F, 0.0F),
                    new Vector3f(x + 0.06F, 1.0F, 0.0F),
                    new Vector3f(x - 0.06F, 1.0F, 0.0F)},
                    new float[][]{{0, 0}, {1, 0}, {1, 1}, {0, 1}},
                    new Vector3f(0, 0, 1)));
            GeometryDocument.Bone locator = new GeometryDocument.Bone(side + "HandLocator");
            locator.parentName(hand.name());
            locator.pivot(x, 0.73F, 0.015F);
            geometry.add(hand);
            geometry.add(locator);
        }
        geometry.linkHierarchy();
        return AuxiliaryBoneLayout.create(geometry, horizontal, vertical);
    }

    private static AuxiliaryBoneLayout chestAndArmLayout() {
        GeometryDocument geometry = new GeometryDocument();
        GeometryDocument.Bone chest = new GeometryDocument.Bone("UpperBody");
        chest.pivot(0.0F, 1.2F, 0.0F);
        GeometryDocument.Bone arm = new GeometryDocument.Bone("RightArm");
        arm.parentName(chest.name());
        arm.pivot(-0.3F, 1.4F, 0.0F);
        geometry.add(chest);
        geometry.add(arm);
        geometry.linkHierarchy();
        return AuxiliaryBoneLayout.create(geometry, 0.7F, 0.9F);
    }

    private static Armature armature() {
        return armature(false);
    }

    private static Armature armature(boolean attachmentBindRotations) {
        String[] names = {"Root", "Thigh_R", "Leg_R", "Knee_R", "Thigh_L", "Leg_L",
                "Knee_L", "Torso", "Chest", "Head", "Shoulder_R", "Arm_R", "Hand_R",
                "Tool_R", "Elbow_R", "Shoulder_L", "Arm_L", "Hand_L", "Tool_L", "Elbow_L"};
        Map<String, Joint> joints = new LinkedHashMap<>();
        Joint root = new Joint(names[0], 0, new OpenMatrix4f());
        joints.put(root.getName(), root);
        for (int id = 1; id < names.length; id++) {
            if (id == HumanoidRig.RIGHT_TOOL || id == HumanoidRig.LEFT_TOOL) {
                continue;
            }
            OpenMatrix4f local = new OpenMatrix4f();
            boolean hand = id == HumanoidRig.RIGHT_HAND || id == HumanoidRig.LEFT_HAND;
            if (hand) {
                local.translate(id == HumanoidRig.RIGHT_HAND ? -0.3F : 0.3F, 1.0F, 0.0F)
                        .rotateDeg(19.0F, Vec3f.Y_AXIS);
            } else if (attachmentBindRotations && id == HumanoidRig.CHEST) {
                local.translate(0.0F, 1.2F, 0.0F).rotateDeg(-11.0F, Vec3f.Z_AXIS);
            } else if (attachmentBindRotations && id == HumanoidRig.RIGHT_ARM) {
                local.translate(-0.3F, 1.4F, 0.0F).rotateDeg(17.0F, Vec3f.Y_AXIS);
            }
            Joint joint = new Joint(names[id], id, local);
            root.addSubJoints(joint);
            joints.put(joint.getName(), joint);
            if (hand) {
                int tool = id == HumanoidRig.RIGHT_HAND
                        ? HumanoidRig.RIGHT_TOOL : HumanoidRig.LEFT_TOOL;
                Joint toolJoint = new Joint(names[tool], tool, new OpenMatrix4f()
                        .translate(0.0F, -0.27F, 0.015F)
                        .rotateDeg(-179.0F, Vec3f.X_AXIS));
                joint.addSubJoints(toolJoint);
                joints.put(toolJoint.getName(), toolJoint);
            }
        }
        Armature armature = new Armature("ownership_test", names.length, root, joints);
        armature.bakeOriginMatrices();
        return armature;
    }

    private static OpenMatrix4f[] livePoses(Armature armature, float phase) {
        OpenMatrix4f[] bind = bindPoses(armature);
        OpenMatrix4f[] poses = copy(bind);
        OpenMatrix4f bob = new OpenMatrix4f().translate(0.0F, phase * 0.13F, 0.0F);
        for (int joint = 0; joint < poses.length; joint++) {
            poses[joint].load(bob).mulBack(bind[joint]);
        }
        for (int tool : TOOLS) {
            int hand = tool == HumanoidRig.RIGHT_TOOL
                    ? HumanoidRig.RIGHT_HAND : HumanoidRig.LEFT_HAND;
            float sign = tool == HumanoidRig.RIGHT_TOOL ? 1.0F : -1.0F;
            OpenMatrix4f handMotion = new OpenMatrix4f(bob)
                    .rotateDeg(phase * 37.0F, Vec3f.X_AXIS)
                    .rotateDeg(sign * phase * 23.0F, Vec3f.Z_AXIS);
            poses[hand].load(handMotion).mulBack(bind[hand]);
            poses[tool].load(handMotion).mulBack(bind[tool])
                    .translate(sign * phase * 0.08F, phase * -0.06F, phase * 0.04F)
                    .rotateDeg(sign * phase * 56.0F, Vec3f.Y_AXIS)
                    .scale(1.0F + phase * 0.2F, 1.0F - phase * 0.15F,
                            1.0F + phase * 0.12F);
        }
        return poses;
    }

    private static OpenMatrix4f[] bindPoses(Armature armature) {
        OpenMatrix4f[] poses = AuxiliaryPoseMatrices.allocate(armature.getJointNumber());
        for (int joint = 0; joint < poses.length; joint++) {
            OpenMatrix4f.invert(armature.searchJointById(joint).getToOrigin(), poses[joint]);
        }
        return poses;
    }

    private static OpenMatrix4f[] deltas(AuxiliaryBoneLayout layout, Matrix4f unscaled) {
        Matrix4f skin = new Matrix4f().scaling(layout.horizontalScale(),
                        layout.verticalScale(), layout.horizontalScale())
                .mul(unscaled).scale(1.0F / layout.horizontalScale(),
                        1.0F / layout.verticalScale(), 1.0F / layout.horizontalScale());
        OpenMatrix4f[] deltas = AuxiliaryPoseMatrices.allocate(layout.entries().size());
        for (OpenMatrix4f delta : deltas) {
            delta.load(OpenMatrix4f.importFromMojangMatrix(skin));
        }
        return deltas;
    }

    private static OpenMatrix4f[] auxiliarySnapshot(AuxiliaryBoneLayout layout,
                                                   OpenMatrix4f[] complete) {
        OpenMatrix4f[] source = new OpenMatrix4f[layout.entries().size()];
        for (AuxiliaryBoneLayout.Entry entry : layout.entries()) {
            source[entry.auxiliaryIndex()] = new OpenMatrix4f(complete[entry.poseIndex()]);
        }
        return source;
    }

    private static OpenMatrix4f[] copy(OpenMatrix4f[] source) {
        assertNotNull(source);
        OpenMatrix4f[] result = new OpenMatrix4f[source.length];
        for (int index = 0; index < source.length; index++) {
            result[index] = new OpenMatrix4f(source[index]);
        }
        return result;
    }

    private static Vector3f origin(OpenMatrix4f matrix) {
        return new Vector3f(matrix.m30, matrix.m31, matrix.m32);
    }

    private static Vector3f zDirection(OpenMatrix4f matrix) {
        return new Vector3f(matrix.m20, matrix.m21, matrix.m22).normalize();
    }

    private static void assertFrame(OpenMatrix4f expected, OpenMatrix4f actual,
                                     float epsilon) {
        Matrix4f wanted = OpenMatrix4f.exportToMojangMatrix(expected);
        Matrix4f rendered = OpenMatrix4f.exportToMojangMatrix(actual);
        for (Vector3f point : new Vector3f[]{new Vector3f(), new Vector3f(1, 0, 0),
                new Vector3f(0, 1, 0), new Vector3f(0, 0, 1)}) {
            assertVector(wanted.transformPosition(new Vector3f(point)),
                    rendered.transformPosition(new Vector3f(point)), epsilon);
        }
    }

    private static void assertDirections(OpenMatrix4f expected, OpenMatrix4f actual,
                                         float epsilon) {
        Matrix4f wanted = OpenMatrix4f.exportToMojangMatrix(expected);
        Matrix4f rendered = OpenMatrix4f.exportToMojangMatrix(actual);
        for (Vector3f axis : new Vector3f[]{new Vector3f(1, 0, 0),
                new Vector3f(0, 1, 0), new Vector3f(0, 0, 1)}) {
            assertVector(wanted.transformDirection(new Vector3f(axis)),
                    rendered.transformDirection(new Vector3f(axis)), epsilon);
        }
    }

    private static void assertVector(Vector3f expected, Vector3f actual, float epsilon) {
        assertEquals(expected.x(), actual.x(), epsilon, "x");
        assertEquals(expected.y(), actual.y(), epsilon, "y");
        assertEquals(expected.z(), actual.z(), epsilon, "z");
    }
}
