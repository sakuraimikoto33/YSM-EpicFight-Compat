package net.okitsu.ysmepicfightcompat.mesh;

import net.minecraft.world.phys.Vec3;
import net.okitsu.ysmepicfightcompat.animation.BoneQuerySnapshot;
import net.okitsu.ysmepicfightcompat.geometry.GeometryDocument;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import yesman.epicfight.api.utils.math.OpenMatrix4f;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Read-only recovery of authored bone channels from the exact completed skin matrices. */
public final class DisplayedBoneQueries {
    private static final float EPSILON = 1.0E-7F;

    private DisplayedBoneQueries() {
    }

    /**
     * The caller owns {@code previous} per entity, and publishes the result only after
     * its current animation evaluation has finished. No renderer matrix is mutated.
     * Coordinates invert the parser's X reflection and 1/16 conversion; rotations use
     * the same authored Z-Y-X angle convention as animation tracks.
     */
    public static BoneQuerySnapshot capture(AuxiliaryBoneLayout layout,
                                            OpenMatrix4f[] complete,
                                            BoneQuerySnapshot previous,
                                            Set<String> hiddenBones) {
        BoneQuerySnapshot prior = previous == null ? BoneQuerySnapshot.EMPTY : previous;
        if (layout == null || complete == null || complete.length < layout.totalPoseCount()) {
            return prior;
        }
        // Scratch belongs to this capture, not the shared mesh or a thread-local.
        // Nested captures must not replace an outer capture's intermediate values.
        Scratch scratch = new Scratch(layout);
        Matrix4f[] worlds = new Matrix4f[layout.entries().size()];
        boolean[] neededByChild = new boolean[worlds.length];
        for (AuxiliaryBoneLayout.Entry entry : layout.entries()) {
            if (entry.parentAuxiliaryIndex() >= 0) {
                neededByChild[entry.parentAuxiliaryIndex()] = true;
            }
        }
        boolean[] hidden = new boolean[worlds.length];
        Map<String, BoneQuerySnapshot.BoneValues> values = new LinkedHashMap<>();
        for (AuxiliaryBoneLayout.Entry entry : layout.entries()) {
            GeometryDocument.Bone bone = entry.bone();
            BoneQuerySnapshot.BoneValues old = prior.values(bone.name());
            BoneQuerySnapshot.BoneValues fallback = old == null ? bindValues(entry, scratch) : old;
            int index = entry.auxiliaryIndex();
            int parent = entry.parentAuxiliaryIndex();
            hidden[index] = hiddenBones != null && hiddenBones.contains(bone.name())
                    || parent >= 0 && hidden[parent];
            OpenMatrix4f skin = complete[entry.poseIndex()];
            Matrix4f world = skin == null ? null : (neededByChild[index]
                    ? new Matrix4f(scratch.inverseScale) : scratch.world.set(scratch.inverseScale))
                    .mul(matrix(skin, scratch.skin)).mul(scratch.scale).mul(entry.bindWorld());
            if (world == null || !world.isFinite()) {
                values.put(bone.name(), fallback);
                continue;
            }
            // Only parents need an independent world matrix after this iteration.
            // Store it before local recovery, even if its own parent is collapsed.
            if (neededByChild[index]) {
                worlds[index] = world;
            }
            Matrix4f local = scratch.local.set(world);
            if (parent >= 0) {
                Matrix4f parentWorld = worlds[parent];
                if (parentWorld == null || !invertible(parentWorld)) {
                    // A collapsed parent loses the child's local transform. Keep the
                    // previous value instead of inventing an identity local pose.
                    values.put(bone.name(), fallback);
                    continue;
                }
                local.set(parentWorld).invert().mul(world);
            }
            Matrix4f centered = scratch.centered.translation(-bone.pivotX(),
                            -bone.pivotY(), -bone.pivotZ())
                    .mul(local).translate(bone.pivotX(), bone.pivotY(), bone.pivotZ());
            if (!centered.isFinite()) {
                values.put(bone.name(), fallback);
                continue;
            }
            channels(centered, fallback.rotation(), fallback.scale(), scratch);
            Vector3f pivot = world.transformPosition(bone.pivotX(), bone.pivotY(),
                    bone.pivotZ(), scratch.pivot);
            Vec3 absolute = hidden[index] ? fallback.absolutePivot() : authoredPosition(pivot);
            values.put(bone.name(), new BoneQuerySnapshot.BoneValues(scratch.rotation,
                    authoredPosition(centered.getTranslation(scratch.translation)),
                    scratch.signedScale, absolute));
        }
        return new BoneQuerySnapshot(values);
    }

    private static BoneQuerySnapshot.BoneValues bindValues(
            AuxiliaryBoneLayout.Entry entry, Scratch scratch) {
        GeometryDocument.Bone bone = entry.bone();
        Vector3f pivot = entry.bindWorld().transformPosition(bone.pivotX(), bone.pivotY(),
                bone.pivotZ(), scratch.pivot);
        return new BoneQuerySnapshot.BoneValues(new Vec3(-Math.toDegrees(bone.rotationX()),
                -Math.toDegrees(bone.rotationY()), Math.toDegrees(bone.rotationZ())),
                Vec3.ZERO, new Vec3(1, 1, 1), authoredPosition(pivot));
    }

    private static void channels(Matrix4f local, Vec3 previousAngles, Vec3 previousScale,
                                 Scratch scratch) {
        Vector3f a = scratch.a.set(local.m00(), local.m01(), local.m02());
        Vector3f b = scratch.b.set(local.m10(), local.m11(), local.m12());
        Vector3f c = scratch.c.set(local.m20(), local.m21(), local.m22());
        Matrix4f hint = scratch.hint.identity().rotateZ((float) Math.toRadians(previousAngles.z))
                .rotateY((float) Math.toRadians(-previousAngles.y))
                .rotateX((float) Math.toRadians(-previousAngles.x));
        Vector3f x = scratch.x.set(a);
        if (x.lengthSquared() <= EPSILON * EPSILON) {
            x.set(b).cross(c);
        }
        if (x.lengthSquared() <= EPSILON * EPSILON) {
            x.set(hint.m00(), hint.m01(), hint.m02());
        }
        x.normalize();
        Vector3f y = scratch.y.set(b).sub(scratch.projection.set(x).mul(x.dot(b)));
        if (y.lengthSquared() <= EPSILON * EPSILON) {
            y.set(c).cross(x);
        }
        if (y.lengthSquared() <= EPSILON * EPSILON) {
            y.set(hint.m10(), hint.m11(), hint.m12());
            y.sub(scratch.projection.set(x).mul(x.dot(y)));
        }
        if (y.lengthSquared() <= EPSILON * EPSILON) {
            if (Math.abs(x.y) < 0.9F) {
                y.set(0, 1, 0);
            } else {
                y.set(1, 0, 0);
            }
            y.sub(scratch.projection.set(x).mul(x.dot(y)));
        }
        y.normalize();
        Vector3f z = scratch.z.set(x).cross(y).normalize();
        Quaternionf reference = hint.getNormalizedRotation(scratch.reference);
        float best = -1.0F;
        int selectedSignX = 1;
        int selectedSignY = 1;
        // Preserve existing scale signs before minimizing rotation distance. Otherwise
        // an ordinary first-frame 180-degree turn becomes two invented negative scales.
        // Newly reflected/collapsed axes use the nearest previous proper rotation.
        for (int signX = 1; signX >= -1; signX -= 2) {
            for (int signY = 1; signY >= -1; signY -= 2) {
                Vector3f candidateX = scratch.candidateX.set(x).mul(signX);
                Vector3f candidateY = scratch.candidateY.set(y).mul(signY);
                Vector3f candidateZ = scratch.candidateZ.set(z).mul(signX * signY);
                Quaternionf rotation = basis(candidateX, candidateY, candidateZ, scratch.basis)
                        .getNormalizedRotation(scratch.candidateRotation);
                float closeness = 2.0F * (sameSign(a.dot(candidateX), previousScale.x)
                        + sameSign(b.dot(candidateY), previousScale.y)
                        + sameSign(c.dot(candidateZ), previousScale.z))
                        + Math.abs(reference.dot(rotation));
                if (closeness > best) {
                    best = closeness;
                    selectedSignX = signX;
                    selectedSignY = signY;
                }
            }
        }
        Vector3f selectedX = scratch.selectedX.set(x).mul(selectedSignX);
        Vector3f selectedY = scratch.selectedY.set(y).mul(selectedSignY);
        Vector3f selectedZ = scratch.selectedZ.set(z).mul(selectedSignX * selectedSignY);
        double pitchY = Math.asin(Math.max(-1.0D, Math.min(1.0D, -selectedX.z)));
        double pitchX, rollZ;
        if (Math.abs(Math.cos(pitchY)) > EPSILON) {
            pitchX = Math.atan2(selectedY.z, selectedZ.z);
            rollZ = Math.atan2(selectedX.y, selectedX.x);
        } else {
            pitchX = -Math.toRadians(previousAngles.x);
            rollZ = Math.atan2(-selectedY.x, selectedY.y) + Math.signum(pitchY) * pitchX;
        }
        Vec3 rotation = authoredRotation(pitchX, pitchY, rollZ, previousAngles);
        Vec3 alternate = authoredRotation(pitchX + Math.PI, Math.PI - pitchY,
                rollZ + Math.PI, previousAngles);
        if (alternate.distanceToSqr(previousAngles) < rotation.distanceToSqr(previousAngles)) {
            rotation = alternate;
        }
        // Only immutable values leave this scratch through the published snapshot.
        scratch.rotation = rotation;
        scratch.signedScale = new Vec3(a.dot(selectedX), b.dot(selectedY), c.dot(selectedZ));
    }

    private static double near(double angle, double previous) {
        return angle + 360.0D * Math.rint((previous - angle) / 360.0D);
    }

    private static Vec3 authoredRotation(double x, double y, double z, Vec3 previous) {
        return new Vec3(near(-Math.toDegrees(x), previous.x),
                near(-Math.toDegrees(y), previous.y), near(Math.toDegrees(z), previous.z));
    }

    private static int sameSign(double current, double previous) {
        return Math.abs(current) > EPSILON && Math.abs(previous) > EPSILON
                && Math.signum(current) == Math.signum(previous) ? 1 : 0;
    }

    private static Vec3 authoredPosition(Vector3f value) {
        return new Vec3(-value.x * 16.0D, value.y * 16.0D, value.z * 16.0D);
    }

    private static boolean invertible(Matrix4f value) {
        float determinant = value.determinant();
        return Float.isFinite(determinant) && Math.abs(determinant) > 1.0E-12F;
    }

    private static Matrix4f basis(Vector3f x, Vector3f y, Vector3f z, Matrix4f destination) {
        return destination.set(x.x, x.y, x.z, 0, y.x, y.y, y.z, 0,
                z.x, z.y, z.z, 0, 0, 0, 0, 1);
    }

    private static Matrix4f matrix(OpenMatrix4f value, Matrix4f destination) {
        return destination.set(value.m00, value.m01, value.m02, value.m03,
                value.m10, value.m11, value.m12, value.m13,
                value.m20, value.m21, value.m22, value.m23,
                value.m30, value.m31, value.m32, value.m33);
    }

    private static final class Scratch {
        private final Matrix4f scale;
        private final Matrix4f inverseScale;
        private final Matrix4f world = new Matrix4f();
        private final Matrix4f skin = new Matrix4f();
        private final Matrix4f local = new Matrix4f();
        private final Matrix4f centered = new Matrix4f();
        private final Matrix4f hint = new Matrix4f();
        private final Matrix4f basis = new Matrix4f();
        private final Vector3f a = new Vector3f();
        private final Vector3f b = new Vector3f();
        private final Vector3f c = new Vector3f();
        private final Vector3f x = new Vector3f();
        private final Vector3f y = new Vector3f();
        private final Vector3f z = new Vector3f();
        private final Vector3f projection = new Vector3f();
        private final Vector3f selectedX = new Vector3f();
        private final Vector3f selectedY = new Vector3f();
        private final Vector3f selectedZ = new Vector3f();
        private final Vector3f candidateX = new Vector3f();
        private final Vector3f candidateY = new Vector3f();
        private final Vector3f candidateZ = new Vector3f();
        private final Vector3f pivot = new Vector3f();
        private final Vector3f translation = new Vector3f();
        private final Quaternionf reference = new Quaternionf();
        private final Quaternionf candidateRotation = new Quaternionf();
        private Vec3 rotation;
        private Vec3 signedScale;

        private Scratch(AuxiliaryBoneLayout layout) {
            scale = new Matrix4f().scaling(layout.horizontalScale(),
                    layout.verticalScale(), layout.horizontalScale());
            inverseScale = new Matrix4f(scale).invert();
        }
    }
}
