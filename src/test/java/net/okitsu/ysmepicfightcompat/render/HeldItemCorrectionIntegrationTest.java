package net.okitsu.ysmepicfightcompat.render;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.okitsu.ysmepicfightcompat.animation.DefaultPoseProgram;
import net.okitsu.ysmepicfightcompat.geometry.GeometryDocument;
import net.okitsu.ysmepicfightcompat.mesh.AuxiliaryBoneLayout;
import net.okitsu.ysmepicfightcompat.mesh.CompatHumanoidMesh;
import net.okitsu.ysmepicfightcompat.mesh.HumanoidRig;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import yesman.epicfight.api.animation.Joint;
import yesman.epicfight.api.animation.types.StaticAnimation;
import yesman.epicfight.api.asset.AssetAccessor;
import yesman.epicfight.api.client.model.Mesh;
import yesman.epicfight.api.client.model.MeshPartDefinition;
import yesman.epicfight.api.client.model.VertexBuilder;
import yesman.epicfight.api.client.model.transformer.VanillaModelTransformer.VanillaMeshPartDefinition;
import yesman.epicfight.api.model.Armature;
import yesman.epicfight.api.utils.math.OpenMatrix4f;
import yesman.epicfight.api.utils.math.Vec3f;
import yesman.epicfight.world.capabilities.entitypatch.Faction;
import yesman.epicfight.world.capabilities.entitypatch.LivingEntityPatch;
import yesman.epicfight.world.damagesource.StunType;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exercises the actual correction entry point without a Minecraft entity or renderer. */
class HeldItemCorrectionIntegrationTest {
    private static final String[] JOINT_NAMES = {
            "Root", "Thigh_R", "Leg_R", "Knee_R", "Thigh_L", "Leg_L", "Knee_L",
            "Torso", "Chest", "Head", "Shoulder_R", "Arm_R", "Hand_R", "Tool_R",
            "Elbow_R", "Shoulder_L", "Arm_L", "Hand_L", "Tool_L", "Elbow_L"
    };

    @AfterEach
    void clearFrame() {
        RenderFrameContext.clear();
    }

    @Test
    void preservesEpicFightCorrectionWhenNoConvertedMeshOwnsTheFrame() {
        TestPatch patch = new TestPatch(armature());
        OpenMatrix4f[] poses = poses();
        RenderFrameContext.pushThirdPerson(null);

        assertCorrection(patch, poses, HumanoidRig.RIGHT_TOOL,
                poses[HumanoidRig.RIGHT_TOOL], false);
        assertCorrection(null, poses, HumanoidRig.LEFT_TOOL,
                poses[HumanoidRig.LEFT_TOOL], false);
    }

    @Test
    void preservesEpicFightCorrectionForAnUnrelatedPoseSource() {
        TestPatch patch = new TestPatch(armature());
        CompatHumanoidMesh mesh = mesh();
        bind(mesh);
        OpenMatrix4f[] body = poses();
        publish(mesh, body, new Vector3f(2, 3, 4), new Vector3f(-2, 3, 4),
                null, null, projectedPoses());
        OpenMatrix4f[] unrelated = poses();

        assertCorrection(patch, unrelated, HumanoidRig.RIGHT_TOOL,
                unrelated[HumanoidRig.RIGHT_TOOL], false);

        OpenMatrix4f receiver = itemCorrection();
        OpenMatrix4f copiedSelection = new OpenMatrix4f(body[HumanoidRig.RIGHT_TOOL]);
        OpenMatrix4f expected = expected(copiedSelection, receiver, false);
        assertSame(receiver, HeldItemPoseResolver.resolveCorrection(
                patch, body, receiver, copiedSelection));
        assertMatrixEquals(expected, receiver);
    }

    @Test
    void appliesTheLocatorItemOriginToPublishedTools() {
        TestPatch patch = new TestPatch(armature());
        CompatHumanoidMesh mesh = mesh();
        bind(mesh);
        OpenMatrix4f[] body = poses();
        OpenMatrix4f[] displayed = projectedPoses();
        publish(mesh, body, new Vector3f(2, 3, 4), new Vector3f(-2, 3, 4),
                null, null, displayed);

        for (int tool : new int[]{HumanoidRig.RIGHT_TOOL, HumanoidRig.LEFT_TOOL}) {
            assertCorrection(patch, body, tool, displayed[tool], true);
            assertCorrection(patch, RenderFrameContext.resolvePatchedLayerPoses(body),
                    tool, displayed[tool], true);
        }
    }

    @Test
    void appliesTheLocatorItemOriginOnceToScopedAndRereadTools() {
        Armature armature = armature();
        TestPatch patch = new TestPatch(armature);
        CompatHumanoidMesh mesh = mesh();
        bind(mesh);
        OpenMatrix4f[] body = poses();
        publish(mesh, body, new Vector3f(2, 3, 4), new Vector3f(-2, 3, 4),
                null, null, projectedPoses());
        OpenMatrix4f[] displayed = RenderFrameContext.resolvePatchedLayerPoses(body);

        try (var ignored = AttachmentArmatureScope.open(armature, body, displayed)) {
            OpenMatrix4f[] reread = AttachmentArmatureScope.resolvePoseMatrices(
                    armature, body, false);
            for (OpenMatrix4f[] requested : new OpenMatrix4f[][]{
                    displayed, displayed.clone(), reread, reread.clone()}) {
                assertTrue(AttachmentArmatureScope.isDisplayedPoseArray(armature, requested));
                for (int tool : new int[]{HumanoidRig.RIGHT_TOOL, HumanoidRig.LEFT_TOOL}) {
                    assertCorrection(patch, requested, tool, displayed[tool], true);
                }
            }
        }
    }

    @Test
    void appliesTheLocatorItemOriginToTheAuthoredItemSwitchFrame() {
        TestPatch patch = new TestPatch(armature());
        CompatHumanoidMesh mesh = mesh();
        bind(mesh);
        OpenMatrix4f[] body = poses();
        OpenMatrix4f right = new OpenMatrix4f().translate(1, 2, 3)
                .rotateDeg(32, Vec3f.Z_AXIS).scale(0.8F, 1.2F, 0.9F);
        OpenMatrix4f left = new OpenMatrix4f().translate(-1, 2, 3)
                .rotateDeg(-41, Vec3f.Y_AXIS).scale(0.7F, 1.3F, 1.1F);
        publish(mesh, body, null, null, right, left, null);

        assertCorrection(patch, body, HumanoidRig.RIGHT_TOOL, right, true);
        assertCorrection(patch, body, HumanoidRig.LEFT_TOOL, left, true);
    }

    @Test
    void appliesTheLocatorItemOriginAfterTheCombatFistRetarget() {
        TestPatch patch = new TestPatch(armature());
        CompatHumanoidMesh mesh = mesh();
        bind(mesh);
        OpenMatrix4f[] body = poses();
        Vector3f right = new Vector3f(2, 3, 4);
        Vector3f left = new Vector3f(-2, 3, 4);
        publish(mesh, body, right, left, null, null, null);

        for (int tool : new int[]{HumanoidRig.RIGHT_TOOL, HumanoidRig.LEFT_TOOL}) {
            int hand = tool == HumanoidRig.RIGHT_TOOL
                    ? HumanoidRig.RIGHT_HAND : HumanoidRig.LEFT_HAND;
            Vector3f fist = tool == HumanoidRig.RIGHT_TOOL ? right : left;
            // All fixture joints have an identity bind. Preserve the Tool's
            // independent live displacement from the physical hand.
            OpenMatrix4f retargeted = new OpenMatrix4f(body[tool]);
            retargeted.m30 = fist.x + body[tool].m30 - body[hand].m30;
            retargeted.m31 = fist.y + body[tool].m31 - body[hand].m31;
            retargeted.m32 = fist.z + body[tool].m32 - body[hand].m32;
            assertCorrection(patch, body, tool, retargeted, true);
        }
    }

    @Test
    void preservesNonToolSheathCorrectionsInPublishedAndScopedFrames() {
        Armature armature = armature();
        TestPatch patch = new TestPatch(armature);
        CompatHumanoidMesh mesh = mesh();
        bind(mesh);
        OpenMatrix4f[] body = poses();
        publish(mesh, body, new Vector3f(2, 3, 4), new Vector3f(-2, 3, 4),
                null, null, projectedPoses());
        OpenMatrix4f[] displayed = RenderFrameContext.resolvePatchedLayerPoses(body);
        assertCorrection(patch, body, HumanoidRig.CHEST,
                displayed[HumanoidRig.CHEST], false);

        try (var ignored = AttachmentArmatureScope.open(armature, body, displayed)) {
            assertCorrection(patch, displayed, HumanoidRig.CHEST,
                    displayed[HumanoidRig.CHEST], false);
        }
    }

    @Test
    void preservesUnretargetedToolsWhenNoModelAnchorWasPublished() {
        Armature armature = armature();
        TestPatch patch = new TestPatch(armature);
        CompatHumanoidMesh mesh = mesh();
        bind(mesh);
        OpenMatrix4f[] body = poses();
        // Projection fails open per joint. An array may therefore contain
        // original Tool matrices even while other attachments were projected.
        OpenMatrix4f[] displayed = body.clone();
        displayed[HumanoidRig.CHEST] = new OpenMatrix4f().translate(4, 5, 6);
        publish(mesh, body, null, null, null, null, displayed);
        assertCorrection(patch, body, HumanoidRig.RIGHT_TOOL,
                body[HumanoidRig.RIGHT_TOOL], false);

        OpenMatrix4f[] layer = RenderFrameContext.resolvePatchedLayerPoses(body);
        try (var ignored = AttachmentArmatureScope.open(armature, body, layer)) {
            assertCorrection(patch, layer, HumanoidRig.LEFT_TOOL,
                    body[HumanoidRig.LEFT_TOOL], false);
        }
    }

    @Test
    void firstAndThirdPersonUseTheSamePublishedAndAuthoredItemOrigins() {
        TestPatch patch = new TestPatch(armature());
        CompatHumanoidMesh mesh = mesh();
        OpenMatrix4f[] body = poses();
        OpenMatrix4f[] displayed = projectedPoses();
        OpenMatrix4f rightAuthored = new OpenMatrix4f().translate(2, 3, 4)
                .rotateDeg(39, Vec3f.Y_AXIS).scale(0.8F, 1.2F, 0.9F);
        OpenMatrix4f leftAuthored = new OpenMatrix4f().translate(-2, 3, 4)
                .rotateDeg(-28, Vec3f.Z_AXIS).scale(1.1F, 0.7F, 1.3F);

        for (boolean firstPerson : new boolean[]{false, true}) {
            RenderFrameContext.clear();
            if (firstPerson) {
                RenderFrameContext.pushFirstPerson(null, Map.of(), true);
            } else {
                RenderFrameContext.pushThirdPerson(null);
            }
            assertTrue(RenderFrameContext.bindMesh(null, firstPerson, mesh));
            publish(mesh, body, new Vector3f(2, 3, 4), new Vector3f(-2, 3, 4),
                    null, null, displayed);
            for (int tool : new int[]{HumanoidRig.RIGHT_TOOL, HumanoidRig.LEFT_TOOL}) {
                assertCorrection(patch, body, tool, displayed[tool], true);
            }

            publish(mesh, body, null, null, rightAuthored, leftAuthored, null);
            assertCorrection(patch, body, HumanoidRig.RIGHT_TOOL, rightAuthored, true);
            assertCorrection(patch, body, HumanoidRig.LEFT_TOOL, leftAuthored, true);
        }
    }

    @Test
    void formBowProxyAppliesTheItemOriginOnceAfterScalingTheLocatorTranslation() {
        Armature armature = armature();
        TestPatch patch = new TestPatch(armature);
        CompatHumanoidMesh mesh = mesh();
        bind(mesh);
        OpenMatrix4f[] body = poses();
        publish(mesh, body, null, null, null, null, null);
        OpenMatrix4f mainLocator = new OpenMatrix4f().translate(2, 3, 4)
                .rotateDeg(37, Vec3f.Z_AXIS).rotateDeg(-19, Vec3f.Y_AXIS)
                .scale(0.8F, 1.2F, 1.1F);
        OpenMatrix4f offLocator = new OpenMatrix4f().translate(-4, 5, 6)
                .rotateDeg(-51, Vec3f.X_AXIS);
        OpenMatrix4f locatorBefore = new OpenMatrix4f(mainLocator);
        float translationScale = 1.25F;
        RenderFrameContext.publishFormHeldItemPoints(null, mesh, HumanoidArm.RIGHT,
                mainLocator, offLocator, false, false, translationScale);
        OpenMatrix4f scaledAnchor = new OpenMatrix4f(mainLocator);
        scaledAnchor.m30 *= translationScale;
        scaledAnchor.m31 *= translationScale;
        scaledAnchor.m32 *= translationScale;

        try (var main = RenderFrameContext.openFormHeldItem(
                null, InteractionHand.MAIN_HAND, armature, body)) {
            assertNotNull(main);
            assertTrue(RenderFrameContext.formHeldItemActive(null));
            OpenMatrix4f[] reread = AttachmentArmatureScope.resolvePoseMatrices(
                    armature, body, false);
            for (OpenMatrix4f[] requested : new OpenMatrix4f[][]{main.poses(), reread}) {
                for (int tool : new int[]{HumanoidRig.RIGHT_TOOL, HumanoidRig.LEFT_TOOL}) {
                    // The bow's OFF_HAND proxy resolves to this MAIN_HAND form
                    // locator too. The 1.25 factor affects its position only;
                    // the subsequent Tool-local item-origin difference is unscaled.
                    assertMatrixEquals(scaledAnchor, requested[tool]);
                    assertCorrection(patch, requested, tool, scaledAnchor, true);
                }
            }
        }
        assertMatrixEquals(locatorBefore, mainLocator);
        assertFalse(RenderFrameContext.formHeldItemActive(null));
        assertCorrection(patch, body, HumanoidRig.RIGHT_TOOL,
                body[HumanoidRig.RIGHT_TOOL], false);
    }

    private static void bind(CompatHumanoidMesh mesh) {
        RenderFrameContext.pushThirdPerson(null);
        assertTrue(RenderFrameContext.bindMesh(null, false, mesh));
    }

    private static void publish(CompatHumanoidMesh mesh, OpenMatrix4f[] body,
                                Vector3f rightFist, Vector3f leftFist,
                                OpenMatrix4f rightAuthored, OpenMatrix4f leftAuthored,
                                OpenMatrix4f[] displayed) {
        RenderFrameContext.publishHeldItemPoints(null, mesh, body,
                rightFist, leftFist, rightAuthored, leftAuthored,
                null, displayed, false, false, false, false, Set.of());
    }

    private static CompatHumanoidMesh mesh() {
        GeometryDocument geometry = new GeometryDocument();
        for (String side : new String[]{"Right", "Left"}) {
            GeometryDocument.Bone hand = new GeometryDocument.Bone(side + "Hand");
            GeometryDocument.Bone locator = new GeometryDocument.Bone(side + "HandLocator");
            locator.parentName(hand.name());
            locator.pivot(side.equals("Right") ? 0.25F : -0.25F, 0.75F, 0);
            geometry.add(hand);
            geometry.add(locator);
        }
        geometry.linkHierarchy();
        Map<String, Number[]> arrays = new LinkedHashMap<>();
        for (String key : new String[]{"positions", "normals", "uvs",
                "weights", "vcounts", "vindices"}) {
            arrays.put(key, new Number[0]);
        }
        Map<MeshPartDefinition, List<VertexBuilder>> parts = new LinkedHashMap<>();
        for (String part : new String[]{"head", "torso", "leftArm", "rightArm",
                "leftLeg", "rightLeg", "hat", "jacket", "leftSleeve",
                "rightSleeve", "leftPants", "rightPants"}) {
            parts.put(VanillaMeshPartDefinition.of(part), List.of());
        }
        // These tests never initialize or draw the mesh. Its ordinary constructor
        // gives the resolver a real CPU pose owner without a renderer or GL context.
        return new CompatHumanoidMesh("correction-fixture",
                new DefaultPoseProgram(geometry, Map.of()), null,
                AuxiliaryBoneLayout.create(geometry), arrays, parts, Map.of(),
                null, new Mesh.RenderProperties(null, null, false), true, false);
    }

    private static Armature armature() {
        Map<String, Joint> joints = new LinkedHashMap<>();
        Joint root = new Joint(JOINT_NAMES[0], 0, new OpenMatrix4f());
        joints.put(root.getName(), root);
        for (int id = 1; id < JOINT_NAMES.length; id++) {
            Joint joint = new Joint(JOINT_NAMES[id], id, new OpenMatrix4f());
            root.addSubJoints(joint);
            joints.put(joint.getName(), joint);
        }
        Armature result = new Armature("correction-fixture", JOINT_NAMES.length, root, joints);
        result.bakeOriginMatrices();
        return result;
    }

    private static OpenMatrix4f[] poses() {
        OpenMatrix4f[] result = new OpenMatrix4f[JOINT_NAMES.length];
        for (int id = 0; id < result.length; id++) {
            result[id] = new OpenMatrix4f().translate(id * 0.01F, id * 0.02F, -id * 0.01F)
                    .rotateDeg(id * 3.0F, Vec3f.Z_AXIS);
        }
        return result;
    }

    private static OpenMatrix4f[] projectedPoses() {
        OpenMatrix4f[] result = poses();
        for (OpenMatrix4f pose : result) {
            pose.translate(1, 2, 3).rotateDeg(27, Vec3f.Y_AXIS).scale(0.8F, 1.2F, 1.1F);
        }
        return result;
    }

    private static OpenMatrix4f itemCorrection() {
        return new OpenMatrix4f().translate(0, -0.01F, -0.1F)
                .rotateDeg(-90, Vec3f.X_AXIS);
    }

    private static void assertCorrection(TestPatch patch, OpenMatrix4f[] requested,
                                          int joint, OpenMatrix4f anchor,
                                          boolean modelItemOrigin) {
        OpenMatrix4f receiver = itemCorrection();
        OpenMatrix4f expected = expected(anchor, receiver, modelItemOrigin);
        OpenMatrix4f sourceBefore = new OpenMatrix4f(requested[joint]);
        OpenMatrix4f actual = HeldItemPoseResolver.resolveCorrection(
                patch, requested, receiver, requested[joint]);
        assertSame(receiver, actual, "Epic Fight returns its reusable correction receiver");
        assertMatrixEquals(expected, actual);
        assertMatrixEquals(sourceBefore, requested[joint]);
    }

    private static OpenMatrix4f expected(OpenMatrix4f anchor, OpenMatrix4f itemCorrection,
                                          boolean modelItemOrigin) {
        Matrix4f result = new Matrix4f(OpenMatrix4f.exportToMojangMatrix(anchor));
        if (modelItemOrigin) {
            result.translate(0, -1.0F / 16.0F, 0.03F);
        }
        result.mul(OpenMatrix4f.exportToMojangMatrix(itemCorrection));
        return OpenMatrix4f.importFromMojangMatrix(result);
    }

    private static void assertMatrixEquals(OpenMatrix4f expected, OpenMatrix4f actual) {
        assertNotNull(actual);
        assertArrayEquals(elements(expected), elements(actual), 0.00001F);
    }

    private static float[] elements(OpenMatrix4f value) {
        return new float[]{value.m00, value.m01, value.m02, value.m03,
                value.m10, value.m11, value.m12, value.m13,
                value.m20, value.m21, value.m22, value.m23,
                value.m30, value.m31, value.m32, value.m33};
    }

    private static final class TestPatch extends LivingEntityPatch<LivingEntity> {
        private TestPatch(Armature armature) {
            super(null);
            this.armature = armature;
        }

        @Override
        public boolean isFakeEntity() {
            return true;
        }

        @Override
        public void updateMotion(boolean considerInaction) {
        }

        @Override
        public AssetAccessor<? extends StaticAnimation> getHitAnimation(StunType stunType) {
            return null;
        }

        @Override
        public Faction getFaction() {
            return null;
        }
    }
}
