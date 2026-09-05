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
        Matrix4f scale = new Matrix4f().scaling(layout.horizontalScale(),
                layout.verticalScale(), layout.horizontalScale());
        Matrix4f inverseScale = new Matrix4f(scale).invert();
        Matrix4f[] worlds = new Matrix4f[layout.entries().size()];
        boolean[] hidden = new boolean[worlds.length];
        Map<String, BoneQuerySnapshot.BoneValues> values = new LinkedHashMap<>();
        for (AuxiliaryBoneLayout.Entry entry : layout.entries()) {
            GeometryDocument.Bone bone = entry.bone();
            BoneQuerySnapshot.BoneValues old = prior.values(bone.name());
            BoneQuerySnapshot.BoneValues fallback = old == null ? bindValues(entry) : old;
            int index = entry.auxiliaryIndex();
            int parent = entry.parentAuxiliaryIndex();
            hidden[index] = hiddenBones != null && hiddenBones.contains(bone.name())
                    || parent >= 0 && hidden[parent];
            OpenMatrix4f skin = complete[entry.poseIndex()];
            Matrix4f world = skin == null ? null : new Matrix4f(inverseScale)
                    .mul(matrix(skin)).mul(scale).mul(entry.bindWorld());
            if (world == null || !world.isFinite()) {
                values.put(bone.name(), fallback);
                continue;
            }
            worlds[index] = world;
            Matrix4f local = new Matrix4f(world);
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
            Matrix4f centered = new Matrix4f().translation(-bone.pivotX(),
                            -bone.pivotY(), -bone.pivotZ())
                    .mul(local).translate(bone.pivotX(), bone.pivotY(), bone.pivotZ());
            if (!centered.isFinite()) {
                values.put(bone.name(), fallback);
                continue;
            }
            Channels channels = channels(centered, fallback.rotation(), fallback.scale());
            Vector3f pivot = world.transformPosition(bone.pivotX(), bone.pivotY(),
                    bone.pivotZ(), new Vector3f());
            Vec3 absolute = hidden[index] ? fallback.absolutePivot() : authoredPosition(pivot);
            values.put(bone.name(), new BoneQuerySnapshot.BoneValues(channels.rotation(),
                    authoredPosition(centered.getTranslation(new Vector3f())),
                    channels.scale(), absolute));
        }
        return new BoneQuerySnapshot(values);
    }

    private static BoneQuerySnapshot.BoneValues bindValues(AuxiliaryBoneLayout.Entry entry) {
        GeometryDocument.Bone bone = entry.bone();
        Vector3f pivot = entry.bindWorld().transformPosition(bone.pivotX(), bone.pivotY(),
                bone.pivotZ(), new Vector3f());
        return new BoneQuerySnapshot.BoneValues(new Vec3(-Math.toDegrees(bone.rotationX()),
                -Math.toDegrees(bone.rotationY()), Math.toDegrees(bone.rotationZ())),
                Vec3.ZERO, new Vec3(1, 1, 1), authoredPosition(pivot));
    }

    private record Channels(Vec3 rotation, Vec3 scale) {
    }

    private static Channels channels(Matrix4f local, Vec3 previousAngles, Vec3 previousScale) {
        Vector3f a = new Vector3f(local.m00(), local.m01(), local.m02());
        Vector3f b = new Vector3f(local.m10(), local.m11(), local.m12());
        Vector3f c = new Vector3f(local.m20(), local.m21(), local.m22());
        Matrix4f hint = new Matrix4f().rotateZ((float) Math.toRadians(previousAngles.z))
                .rotateY((float) Math.toRadians(-previousAngles.y))
                .rotateX((float) Math.toRadians(-previousAngles.x));
        Vector3f x = new Vector3f(a);
        if (x.lengthSquared() <= EPSILON * EPSILON) {
            x.set(b).cross(c);
        }
        if (x.lengthSquared() <= EPSILON * EPSILON) {
            x.set(hint.m00(), hint.m01(), hint.m02());
        }
        x.normalize();
        Vector3f y = new Vector3f(b).sub(new Vector3f(x).mul(x.dot(b)));
        if (y.lengthSquared() <= EPSILON * EPSILON) {
            y.set(c).cross(x);
        }
        if (y.lengthSquared() <= EPSILON * EPSILON) {
            y.set(hint.m10(), hint.m11(), hint.m12());
            y.sub(new Vector3f(x).mul(x.dot(y)));
        }
        if (y.lengthSquared() <= EPSILON * EPSILON) {
            y.set(Math.abs(x.y) < 0.9F ? new Vector3f(0, 1, 0) : new Vector3f(1, 0, 0));
            y.sub(new Vector3f(x).mul(x.dot(y)));
        }
        y.normalize();
        Vector3f z = new Vector3f(x).cross(y).normalize();
        Quaternionf reference = hint.getNormalizedRotation(new Quaternionf());
        float best = -1.0F;
        Vector3f selectedX = x, selectedY = y, selectedZ = z;
        // Preserve existing scale signs before minimizing rotation distance. Otherwise
        // an ordinary first-frame 180-degree turn becomes two invented negative scales.
        // Newly reflected/collapsed axes use the nearest previous proper rotation.
        for (int signX : new int[]{1, -1}) {
            for (int signY : new int[]{1, -1}) {
                Vector3f candidateX = new Vector3f(x).mul(signX);
                Vector3f candidateY = new Vector3f(y).mul(signY);
                Vector3f candidateZ = new Vector3f(z).mul(signX * signY);
                Quaternionf rotation = basis(candidateX, candidateY, candidateZ)
                        .getNormalizedRotation(new Quaternionf());
                float closeness = 2.0F * (sameSign(a.dot(candidateX), previousScale.x)
                        + sameSign(b.dot(candidateY), previousScale.y)
                        + sameSign(c.dot(candidateZ), previousScale.z))
                        + Math.abs(reference.dot(rotation));
                if (closeness > best) {
                    best = closeness;
                    selectedX = candidateX;
                    selectedY = candidateY;
                    selectedZ = candidateZ;
                }
            }
        }
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
        Vec3 scale = new Vec3(a.dot(selectedX), b.dot(selectedY), c.dot(selectedZ));
        return new Channels(rotation, scale);
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

    private static Matrix4f basis(Vector3f x, Vector3f y, Vector3f z) {
        return new Matrix4f(x.x, x.y, x.z, 0, y.x, y.y, y.z, 0,
                z.x, z.y, z.z, 0, 0, 0, 0, 1);
    }

    private static Matrix4f matrix(OpenMatrix4f value) {
        return new Matrix4f(value.m00, value.m01, value.m02, value.m03,
                value.m10, value.m11, value.m12, value.m13,
                value.m20, value.m21, value.m22, value.m23,
                value.m30, value.m31, value.m32, value.m33);
    }
}
