package net.okitsu.ysmepicfightcompat.mesh;

import net.minecraft.world.phys.Vec3;
import net.okitsu.ysmepicfightcompat.animation.BoneQuerySnapshot;
import net.okitsu.ysmepicfightcompat.geometry.GeometryDocument;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import yesman.epicfight.api.utils.math.OpenMatrix4f;

import java.util.AbstractSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class DisplayedBoneQueriesTest {
    private static final double TOLERANCE = 0.001D;

    @Test
    void bindPoseRestoresOriginalAxesUnitsAndRotationWithoutChangingTheSource() {
        GeometryDocument.Bone head = bone("Head", "", new Vec3(2, 20, 3), new Vec3(15, -25, 35));
        AuxiliaryBoneLayout layout = layout(head);
        OpenMatrix4f[] poses = skins(layout, Map.of("Head", layout.entries().get(0).bindWorld()));
        BoneQuerySnapshot result = DisplayedBoneQueries.capture(layout, poses, null, Set.of());
        assertVector(new Vec3(15, -25, 35), result.values("Head").rotation());
        assertVector(Vec3.ZERO, result.values("Head").position());
        assertVector(new Vec3(1, 1, 1), result.values("Head").scale());
        assertVector(new Vec3(2, 20, 3), result.values("Head").absolutePivot());
        assertEquals(0.0F, poses[layout.entries().get(0).poseIndex()].m30, TOLERANCE);
    }

    @Test
    void hierarchyRecoveryRemovesInheritedRotationAndNonUniformModelScale() {
        GeometryDocument.Bone body = bone("Body", "", new Vec3(0, 8, 0), Vec3.ZERO);
        GeometryDocument.Bone head = bone("Head", "Body", new Vec3(2, 20, 3), new Vec3(5, 0, 0));
        AuxiliaryBoneLayout layout = layout(body, head);
        Matrix4f bodyWorld = local(body, new Vec3(4, 1, 2), new Vec3(10, 20, 30), new Vec3(1.25, 0.7, 1.1));
        Matrix4f headWorld = new Matrix4f(bodyWorld).mul(local(head,
                new Vec3(-3, 4, -5), new Vec3(25, -35, 45), new Vec3(0.5, 1.4, 0.8)));
        BoneQuerySnapshot result = DisplayedBoneQueries.capture(layout,
                skins(layout, Map.of("Body", bodyWorld, "Head", headWorld)), null, Set.of());
        assertVector(new Vec3(-3, 4, -5), result.values("Head").position());
        assertVector(new Vec3(25, -35, 45), result.values("Head").rotation());
        assertVector(new Vec3(0.5, 1.4, 0.8), result.values("Head").scale());
        Vector3f pivot = headWorld.transformPosition(head.pivotX(), head.pivotY(), head.pivotZ(), new Vector3f());
        assertVector(new Vec3(-pivot.x * 16, pivot.y * 16, pivot.z * 16), result.values("Head").absolutePivot());
    }

    @Test
    void hiddenAndCollapsedParentsPreserveThePreviouslyPublishedAbsolutePivot() {
        GeometryDocument.Bone body = bone("Body", "", Vec3.ZERO, Vec3.ZERO);
        GeometryDocument.Bone head = bone("Head", "Body", new Vec3(0, 20, 0), Vec3.ZERO);
        AuxiliaryBoneLayout layout = layout(body, head);
        Matrix4f firstHead = local(head, new Vec3(2, 3, 4), new Vec3(10, 20, 30), new Vec3(1, 1, 1));
        BoneQuerySnapshot first = DisplayedBoneQueries.capture(layout,
                skins(layout, Map.of("Body", new Matrix4f(), "Head", firstHead)), null, Set.of());
        Matrix4f nextBody = new Matrix4f().translation(9, 8, 7);
        Matrix4f nextHead = new Matrix4f(nextBody).mul(local(head,
                new Vec3(5, 6, 7), new Vec3(15, 25, 35), new Vec3(1, 1, 1)));
        BoneQuerySnapshot hidden = DisplayedBoneQueries.capture(layout,
                skins(layout, Map.of("Body", nextBody, "Head", nextHead)), first, Set.of("Body"));
        assertVector(first.values("Head").absolutePivot(), hidden.values("Head").absolutePivot());
        assertVector(new Vec3(15, 25, 35), hidden.values("Head").rotation());

        Matrix4f collapsed = new Matrix4f().translation(3, 4, 5).scale(0.0F);
        BoneQuerySnapshot zero = DisplayedBoneQueries.capture(layout,
                skins(layout, Map.of("Body", collapsed, "Head", new Matrix4f(collapsed).mul(firstHead))), first, Set.of());
        assertVector(Vec3.ZERO, zero.values("Body").scale());
        assertEquals(first.values("Head"), zero.values("Head"));
    }

    @Test
    void fullTurnsDoNotTurnIntoInventedNegativeScales() {
        GeometryDocument.Bone bone = bone("Root", "", Vec3.ZERO, Vec3.ZERO);
        AuxiliaryBoneLayout layout = layout(bone);
        BoneQuerySnapshot result = DisplayedBoneQueries.capture(layout, skins(layout,
                Map.of("Root", local(bone, Vec3.ZERO, new Vec3(0, 120, 0), new Vec3(1, 1, 1)))), null, Set.of());
        assertVector(new Vec3(0, 120, 0), result.values("Root").rotation());
        assertVector(new Vec3(1, 1, 1), result.values("Root").scale());
    }

    @Test
    void aSingleReflectedAxisPreservesSignedScaleAndNearbyRotation() {
        GeometryDocument.Bone bone = bone("Root", "", Vec3.ZERO, Vec3.ZERO);
        AuxiliaryBoneLayout layout = layout(bone);
        BoneQuerySnapshot result = DisplayedBoneQueries.capture(layout, skins(layout,
                Map.of("Root", local(bone, Vec3.ZERO, new Vec3(15, 20, 25), new Vec3(-1, 2, 3)))), null, Set.of());
        assertVector(new Vec3(15, 20, 25), result.values("Root").rotation());
        assertVector(new Vec3(-1, 2, 3), result.values("Root").scale());
    }

    @Test
    void invalidAndMissingCompletedPosesRetainThePreviousSnapshot() {
        GeometryDocument.Bone bone = bone("Root", "", Vec3.ZERO, Vec3.ZERO);
        AuxiliaryBoneLayout layout = layout(bone);
        OpenMatrix4f[] poses = skins(layout, Map.of("Root", new Matrix4f().translate(1, 2, 3)));
        BoneQuerySnapshot first = DisplayedBoneQueries.capture(layout, poses, null, Set.of());
        assertSame(first, DisplayedBoneQueries.capture(layout, null, first, Set.of()));
        poses[layout.entries().get(0).poseIndex()].m00 = Float.NaN;
        BoneQuerySnapshot invalid = DisplayedBoneQueries.capture(layout, poses, first, Set.of());
        assertEquals(first.values("Root"), invalid.values("Root"));
    }

    @Test
    void batchedCaptureMatchesIndependentCapturesAcrossSignedAndCollapsedAxes() {
        Vec3[] scales = {new Vec3(1, 1, 1), new Vec3(-1, 2, 3),
                new Vec3(1, -2, 3), new Vec3(1, 2, -3), new Vec3(-1, -2, 3),
                new Vec3(0, 2, 3), new Vec3(0, 0, 3), Vec3.ZERO};
        GeometryDocument.Bone[] bones = new GeometryDocument.Bone[24];
        Map<String, Matrix4f> worlds = new LinkedHashMap<>();
        for (int index = 0; index < bones.length; index++) {
            Vec3 pivot = new Vec3(index - 4, index + 2, 3 - index);
            Vec3 bindRotation = new Vec3(index * 3, index - 5, 7 - index);
            bones[index] = bone("Root" + index, "", pivot, bindRotation);
            worlds.put(bones[index].name(), local(bones[index],
                    new Vec3(index - 3, 5 - index, index * 2),
                    new Vec3(20 + index * 37, -90 + index * 19, 70 - index * 43),
                    scales[index % scales.length]));
        }
        AuxiliaryBoneLayout batchLayout = layout(bones);
        BoneQuerySnapshot batch = DisplayedBoneQueries.capture(batchLayout,
                skins(batchLayout, worlds), null, Set.of());

        for (int index = 0; index < bones.length; index++) {
            GeometryDocument.Bone single = bone(bones[index].name(), "",
                    new Vec3(index - 4, index + 2, 3 - index),
                    new Vec3(index * 3, index - 5, 7 - index));
            AuxiliaryBoneLayout singleLayout = layout(single);
            BoneQuerySnapshot independent = DisplayedBoneQueries.capture(singleLayout,
                    skins(singleLayout, Map.of(single.name(), worlds.get(single.name()))),
                    null, Set.of());
            assertValues(independent.values(single.name()), batch.values(single.name()));
        }
    }

    @Test
    void laterCapturesAndSourceChangesCannotAlterPublishedValues() {
        GeometryDocument.Bone body = bone("Body", "", new Vec3(1, 2, 3), new Vec3(4, 5, 6));
        GeometryDocument.Bone head = bone("Head", "Body", new Vec3(2, 20, 3), Vec3.ZERO);
        AuxiliaryBoneLayout layout = layout(body, head);
        Matrix4f bodyWorld = local(body, new Vec3(3, 2, 1),
                new Vec3(10, 20, 30), new Vec3(-1, 2, 3));
        Matrix4f headWorld = new Matrix4f(bodyWorld).mul(local(head,
                new Vec3(-4, 5, 6), new Vec3(15, 25, 35), new Vec3(1, 1, 1)));
        OpenMatrix4f[] poses = skins(layout, Map.of("Body", bodyWorld, "Head", headWorld));
        float[][] originalPoses = new float[poses.length][];
        for (int index = 0; index < poses.length; index++) {
            originalPoses[index] = components(poses[index]);
        }
        float[][] originalBind = new float[layout.entries().size()][];
        float[][] originalInverse = new float[layout.entries().size()][];
        for (AuxiliaryBoneLayout.Entry entry : layout.entries()) {
            originalBind[entry.auxiliaryIndex()] = entry.bindWorld().get(new float[16]);
            originalInverse[entry.auxiliaryIndex()] = entry.bindWorldInverse().get(new float[16]);
        }
        BoneQuerySnapshot first = DisplayedBoneQueries.capture(layout, poses, null, Set.of());
        BoneQuerySnapshot.BoneValues firstBody = first.values("Body");
        BoneQuerySnapshot.BoneValues firstHead = first.values("Head");
        DisplayedBoneQueries.capture(layout, poses, first, Set.of("Body"));
        for (int index = 0; index < poses.length; index++) {
            assertArrayEquals(originalPoses[index], components(poses[index]), 0.0F);
        }
        for (AuxiliaryBoneLayout.Entry entry : layout.entries()) {
            assertArrayEquals(originalBind[entry.auxiliaryIndex()],
                    entry.bindWorld().get(new float[16]), 0.0F);
            assertArrayEquals(originalInverse[entry.auxiliaryIndex()],
                    entry.bindWorldInverse().get(new float[16]), 0.0F);
        }

        for (OpenMatrix4f pose : poses) {
            pose.m30 += 50.0F;
        }
        DisplayedBoneQueries.capture(layout, poses, first, Set.of());
        assertEquals(firstBody, first.values("Body"));
        assertEquals(firstHead, first.values("Head"));
    }

    @Test
    void nestedCaptureDoesNotOverwriteOuterScratchOrParentWorlds() {
        GeometryDocument.Bone body = bone("Body", "", Vec3.ZERO, Vec3.ZERO);
        GeometryDocument.Bone head = bone("Head", "Body", new Vec3(0, 20, 0), Vec3.ZERO);
        AuxiliaryBoneLayout outerLayout = layout(body, head);
        Matrix4f bodyWorld = local(body, new Vec3(4, 5, 6),
                new Vec3(15, 25, 35), new Vec3(2, 3, 4));
        Matrix4f headWorld = new Matrix4f(bodyWorld).mul(local(head,
                new Vec3(1, 2, 3), new Vec3(40, 50, 60), new Vec3(-1, 2, 3)));
        OpenMatrix4f[] outerPoses = skins(outerLayout,
                Map.of("Body", bodyWorld, "Head", headWorld));
        BoneQuerySnapshot expectedOuter = DisplayedBoneQueries.capture(
                outerLayout, outerPoses, null, Set.of());
        GeometryDocument.Bone inner = bone("Inner", "", new Vec3(7, 8, 9), Vec3.ZERO);
        GeometryDocument innerGeometry = new GeometryDocument();
        innerGeometry.add(inner);
        innerGeometry.linkHierarchy();
        AuxiliaryBoneLayout innerLayout = AuxiliaryBoneLayout.create(innerGeometry, 4.0F, 5.0F);
        OpenMatrix4f[] innerPoses = skins(innerLayout, Map.of("Inner", local(inner,
                new Vec3(-9, -8, -7), new Vec3(-60, -50, -40), new Vec3(3, -2, 1))));
        BoneQuerySnapshot expectedInner = DisplayedBoneQueries.capture(
                innerLayout, innerPoses, null, Set.of());
        AtomicReference<BoneQuerySnapshot> nested = new AtomicReference<>();
        Set<String> reentrantHidden = new AbstractSet<>() {
            @Override
            public boolean contains(Object value) {
                if ("Head".equals(value)) {
                    nested.set(DisplayedBoneQueries.capture(
                            innerLayout, innerPoses, null, Set.of()));
                }
                return false;
            }

            @Override
            public Iterator<String> iterator() {
                return Set.<String>of().iterator();
            }

            @Override
            public int size() {
                return 0;
            }
        };
        BoneQuerySnapshot actual = DisplayedBoneQueries.capture(
                outerLayout, outerPoses, null, reentrantHidden);
        assertNotNull(nested.get());
        assertValues(expectedInner.values("Inner"), nested.get().values("Inner"));
        assertValues(expectedOuter.values("Body"), actual.values("Body"));
        assertValues(expectedOuter.values("Head"), actual.values("Head"));
    }

    @Test
    void validParentWorldStillServesChildrenWhenItsOwnLocalRecoveryFails() {
        GeometryDocument.Bone body = bone("Body", "", Vec3.ZERO, Vec3.ZERO);
        GeometryDocument.Bone neck = bone("Neck", "Body", new Vec3(0, 16, 0), Vec3.ZERO);
        GeometryDocument.Bone head = bone("Head", "Neck", new Vec3(0, 20, 0), Vec3.ZERO);
        AuxiliaryBoneLayout layout = layout(body, neck, head);
        BoneQuerySnapshot previous = DisplayedBoneQueries.capture(layout, skins(layout,
                Map.of("Body", new Matrix4f(), "Neck", new Matrix4f(), "Head", new Matrix4f())),
                null, Set.of());
        Matrix4f neckWorld = local(neck, new Vec3(2, 3, 4),
                new Vec3(15, 25, 35), new Vec3(1, 1, 1));
        Matrix4f headWorld = new Matrix4f(neckWorld).mul(local(head,
                new Vec3(5, 6, 7), new Vec3(20, 30, 40), new Vec3(1, 2, 3)));
        BoneQuerySnapshot result = DisplayedBoneQueries.capture(layout, skins(layout,
                Map.of("Body", new Matrix4f().scale(0.0F),
                        "Neck", neckWorld, "Head", headWorld)), previous, Set.of());
        assertEquals(previous.values("Neck"), result.values("Neck"));
        assertVector(new Vec3(5, 6, 7), result.values("Head").position());
        assertVector(new Vec3(20, 30, 40), result.values("Head").rotation());
        assertVector(new Vec3(1, 2, 3), result.values("Head").scale());
    }

    private static GeometryDocument.Bone bone(String name, String parent, Vec3 pivot, Vec3 rotation) {
        GeometryDocument.Bone bone = new GeometryDocument.Bone(name);
        bone.parentName(parent);
        bone.pivot((float) -pivot.x / 16, (float) pivot.y / 16, (float) pivot.z / 16);
        bone.rotation((float) Math.toRadians(-rotation.x), (float) Math.toRadians(-rotation.y),
                (float) Math.toRadians(rotation.z));
        return bone;
    }

    private static AuxiliaryBoneLayout layout(GeometryDocument.Bone... bones) {
        GeometryDocument geometry = new GeometryDocument();
        for (GeometryDocument.Bone bone : bones) geometry.add(bone);
        geometry.linkHierarchy();
        return AuxiliaryBoneLayout.create(geometry, 2.0F, 3.0F);
    }

    private static Matrix4f local(GeometryDocument.Bone bone, Vec3 position, Vec3 rotation, Vec3 scale) {
        return new Matrix4f().translation(bone.pivotX() - (float) position.x / 16,
                        bone.pivotY() + (float) position.y / 16, bone.pivotZ() + (float) position.z / 16)
                .rotateZ((float) Math.toRadians(rotation.z)).rotateY((float) Math.toRadians(-rotation.y))
                .rotateX((float) Math.toRadians(-rotation.x)).scale((float) scale.x, (float) scale.y, (float) scale.z)
                .translate(-bone.pivotX(), -bone.pivotY(), -bone.pivotZ());
    }

    private static OpenMatrix4f[] skins(AuxiliaryBoneLayout layout, Map<String, Matrix4f> worlds) {
        OpenMatrix4f[] result = new OpenMatrix4f[layout.totalPoseCount()];
        for (int index = 0; index < result.length; index++) result[index] = new OpenMatrix4f();
        Matrix4f scale = new Matrix4f().scaling(layout.horizontalScale(), layout.verticalScale(), layout.horizontalScale());
        for (AuxiliaryBoneLayout.Entry entry : layout.entries()) {
            Matrix4f value = new Matrix4f(scale).mul(worlds.get(entry.bone().name()))
                    .mul(entry.bindWorldInverse()).mul(new Matrix4f(scale).invert());
            OpenMatrix4f output = result[entry.poseIndex()];
            output.m00 = value.m00(); output.m01 = value.m01(); output.m02 = value.m02(); output.m03 = value.m03();
            output.m10 = value.m10(); output.m11 = value.m11(); output.m12 = value.m12(); output.m13 = value.m13();
            output.m20 = value.m20(); output.m21 = value.m21(); output.m22 = value.m22(); output.m23 = value.m23();
            output.m30 = value.m30(); output.m31 = value.m31(); output.m32 = value.m32(); output.m33 = value.m33();
        }
        return result;
    }

    private static void assertVector(Vec3 expected, Vec3 actual) {
        assertEquals(expected.x, actual.x, TOLERANCE);
        assertEquals(expected.y, actual.y, TOLERANCE);
        assertEquals(expected.z, actual.z, TOLERANCE);
    }

    private static void assertValues(BoneQuerySnapshot.BoneValues expected,
                                     BoneQuerySnapshot.BoneValues actual) {
        assertVector(expected.rotation(), actual.rotation());
        assertVector(expected.position(), actual.position());
        assertVector(expected.scale(), actual.scale());
        assertVector(expected.absolutePivot(), actual.absolutePivot());
    }

    private static float[] components(OpenMatrix4f value) {
        return new float[]{value.m00, value.m01, value.m02, value.m03,
                value.m10, value.m11, value.m12, value.m13,
                value.m20, value.m21, value.m22, value.m23,
                value.m30, value.m31, value.m32, value.m33};
    }
}
