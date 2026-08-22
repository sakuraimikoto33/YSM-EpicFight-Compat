package net.okitsu.ysmepicfightcompat.mesh;

import net.okitsu.ysmepicfightcompat.geometry.GeometryDocument;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Estimates humanoid bind pivots from model geometry and authored bone controls. */
final class ModelJointPivots {
    private static final float TOP_RING_EPSILON = 0.05F;

    private record Visit(GeometryDocument.Bone bone, Matrix4f parentTransform) {
    }

    private ModelJointPivots() {
    }

    static Map<Integer, Vector3f> estimate(GeometryDocument geometry,
                                          float horizontalScale, float verticalScale) {
        Map<Integer, List<Vector3f>> geometryByJoint = new HashMap<>();
        Map<Integer, Vector3f> authoredPivots = new HashMap<>();
        ArrayDeque<Visit> pending = new ArrayDeque<>();
        List<GeometryDocument.Bone> roots = geometry.roots();
        for (int index = roots.size() - 1; index >= 0; index--) {
            pending.push(new Visit(roots.get(index), new Matrix4f()));
        }
        while (!pending.isEmpty()) {
            Visit visit = pending.pop();
            GeometryDocument.Bone bone = visit.bone();
            Matrix4f transform = bindTransform(visit.parentTransform(), bone);
            if (isLowerLimbSegmentRoot(bone) && !hasTrailingDigit(bone.name())) {
                authoredPivots.putIfAbsent(HumanoidRig.jointFor(bone),
                        new Vector3f(bone.pivotX(), bone.pivotY(), bone.pivotZ())
                                .mulPosition(visit.parentTransform())
                                .mul(horizontalScale, verticalScale, horizontalScale));
            }
            if (isUpperLimbSegment(bone) && !hasTrailingDigit(bone.name())) {
                List<Vector3f> vertices = geometryByJoint.computeIfAbsent(
                        HumanoidRig.jointFor(bone), ignored -> new ArrayList<>());
                for (GeometryDocument.Face face : bone.faces()) {
                    for (Vector3f authored : face.positions()) {
                        vertices.add(new Vector3f(authored).mulPosition(transform)
                                .mul(horizontalScale, verticalScale, horizontalScale));
                    }
                }
            }
            List<GeometryDocument.Bone> children = bone.children();
            for (int index = children.size() - 1; index >= 0; index--) {
                pending.push(new Visit(children.get(index), transform));
            }
        }

        Map<Integer, Vector3f> result = new HashMap<>();
        putPair(result, HumanoidRig.RIGHT_SHOULDER, HumanoidRig.RIGHT_ARM,
                topOf(geometryByJoint.get(HumanoidRig.RIGHT_ARM)));
        putPair(result, HumanoidRig.LEFT_SHOULDER, HumanoidRig.LEFT_ARM,
                topOf(geometryByJoint.get(HumanoidRig.LEFT_ARM)));
        putPair(result, HumanoidRig.RIGHT_HAND, HumanoidRig.RIGHT_ELBOW,
                topOf(geometryByJoint.get(HumanoidRig.RIGHT_HAND)));
        putPair(result, HumanoidRig.LEFT_HAND, HumanoidRig.LEFT_ELBOW,
                topOf(geometryByJoint.get(HumanoidRig.LEFT_HAND)));
        put(result, HumanoidRig.RIGHT_THIGH, authoredPivots.get(HumanoidRig.RIGHT_THIGH));
        put(result, HumanoidRig.RIGHT_LEG, authoredPivots.get(HumanoidRig.RIGHT_LEG));
        put(result, HumanoidRig.LEFT_THIGH, authoredPivots.get(HumanoidRig.LEFT_THIGH));
        put(result, HumanoidRig.LEFT_LEG, authoredPivots.get(HumanoidRig.LEFT_LEG));
        return result;
    }

    private static boolean isUpperLimbSegment(GeometryDocument.Bone bone) {
        if (!HumanoidRig.isMajorBone(bone)) {
            return false;
        }
        int joint = HumanoidRig.jointFor(bone);
        return joint == HumanoidRig.RIGHT_ARM || joint == HumanoidRig.LEFT_ARM
                || joint == HumanoidRig.RIGHT_HAND || joint == HumanoidRig.LEFT_HAND;
    }

    private static boolean isLowerLimbSegmentRoot(GeometryDocument.Bone bone) {
        if (!HumanoidRig.isMajorBone(bone)) {
            return false;
        }
        int joint = HumanoidRig.jointFor(bone);
        if (joint != HumanoidRig.RIGHT_THIGH && joint != HumanoidRig.RIGHT_LEG
                && joint != HumanoidRig.LEFT_THIGH && joint != HumanoidRig.LEFT_LEG) {
            return false;
        }
        GeometryDocument.Bone parent = bone.parent();
        return parent == null || HumanoidRig.jointFor(parent) != joint;
    }

    private static boolean hasTrailingDigit(String name) {
        return name != null && !name.isEmpty() && Character.isDigit(name.charAt(name.length() - 1));
    }

    private static Matrix4f bindTransform(Matrix4f parent, GeometryDocument.Bone bone) {
        return new Matrix4f(parent)
                .translate(bone.pivotX(), bone.pivotY(), bone.pivotZ())
                .rotateZ(bone.rotationZ()).rotateY(bone.rotationY()).rotateX(bone.rotationX())
                .translate(-bone.pivotX(), -bone.pivotY(), -bone.pivotZ());
    }

    private static Vector3f topOf(List<Vector3f> vertices) {
        if (vertices == null || vertices.isEmpty()) {
            return null;
        }
        float maxY = -Float.MAX_VALUE;
        for (Vector3f vertex : vertices) {
            maxY = Math.max(maxY, vertex.y());
        }
        Vector3f result = new Vector3f();
        int count = 0;
        for (Vector3f vertex : vertices) {
            if (vertex.y() >= maxY - TOP_RING_EPSILON) {
                result.add(vertex);
                count++;
            }
        }
        return count == 0 ? null : result.div(count);
    }

    private static void putPair(Map<Integer, Vector3f> target, int first, int second,
                                Vector3f pivot) {
        if (pivot == null) {
            return;
        }
        target.put(first, new Vector3f(pivot));
        target.put(second, new Vector3f(pivot));
    }

    private static void put(Map<Integer, Vector3f> target, int joint, Vector3f pivot) {
        if (pivot != null) {
            target.put(joint, new Vector3f(pivot));
        }
    }
}
