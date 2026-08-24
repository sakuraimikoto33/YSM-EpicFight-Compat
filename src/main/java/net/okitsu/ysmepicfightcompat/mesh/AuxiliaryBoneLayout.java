package net.okitsu.ysmepicfightcompat.mesh;

import net.okitsu.ysmepicfightcompat.geometry.GeometryDocument;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Assigns stable private pose indices to YSM bones without extending Epic Fight's armature. */
public final class AuxiliaryBoneLayout {
    /** Epic Fight's compute skinning arrays contain 1000 pose slots. */
    public static final int MAX_TOTAL_POSES = 1000;
    public static final int MAX_MODEL_BONES = MAX_TOTAL_POSES - HumanoidRig.EPIC_JOINT_COUNT;

    public record Entry(GeometryDocument.Bone bone, int auxiliaryIndex, int poseIndex,
                        int anchorJoint, int parentAuxiliaryIndex,
                        Matrix4f bindLocal, Matrix4f bindLocalInverse,
                        Matrix4f bindWorld, Matrix4f bindWorldInverse) {
    }

    private record Visit(GeometryDocument.Bone bone, int parentAuxiliaryIndex,
                         Matrix4f parentBindWorld) {
    }

    private final List<Entry> entries;
    private final Map<GeometryDocument.Bone, Entry> byBone;
    private final Map<String, Entry> byName;
    private final Map<Integer, Vector3f> jointPivots;
    private final Map<Integer, Integer> toolAnchorPoseIndices;

    private AuxiliaryBoneLayout(List<Entry> entries,
                                Map<GeometryDocument.Bone, Entry> byBone,
                                Map<String, Entry> byName,
                                Map<Integer, Vector3f> jointPivots,
                                Map<Integer, Integer> toolAnchorPoseIndices) {
        this.entries = List.copyOf(entries);
        this.byBone = Map.copyOf(byBone);
        this.byName = Map.copyOf(byName);
        this.jointPivots = Map.copyOf(jointPivots);
        this.toolAnchorPoseIndices = Map.copyOf(toolAnchorPoseIndices);
    }

    public static AuxiliaryBoneLayout create(GeometryDocument geometry) {
        return create(geometry, 1.0F, 1.0F);
    }

    public static AuxiliaryBoneLayout create(GeometryDocument geometry,
                                             float horizontalScale, float verticalScale) {
        List<Entry> entries = new ArrayList<>();
        Map<GeometryDocument.Bone, Entry> byBone = new IdentityHashMap<>();
        Map<String, Entry> byName = new java.util.LinkedHashMap<>();
        ArrayDeque<Visit> pending = new ArrayDeque<>();
        List<GeometryDocument.Bone> roots = geometry.roots();
        for (int index = roots.size() - 1; index >= 0; index--) {
            pending.push(new Visit(roots.get(index), -1, new Matrix4f()));
        }
        while (!pending.isEmpty()) {
            Visit visit = pending.pop();
            Matrix4f bindLocal = bindLocal(visit.bone());
            Matrix4f bindWorld = new Matrix4f(visit.parentBindWorld()).mul(bindLocal);
            int parentAuxiliary = visit.parentAuxiliaryIndex();
            if (entries.size() < MAX_MODEL_BONES) {
                int auxiliaryIndex = entries.size();
                Entry entry = new Entry(visit.bone(), auxiliaryIndex,
                        HumanoidRig.EPIC_JOINT_COUNT + entries.size(),
                        HumanoidRig.jointFor(visit.bone()), parentAuxiliary,
                        bindLocal, new Matrix4f(bindLocal).invert(),
                        bindWorld, new Matrix4f(bindWorld).invert());
                entries.add(entry);
                byBone.put(visit.bone(), entry);
                byName.putIfAbsent(visit.bone().name().toLowerCase(Locale.ROOT), entry);
                parentAuxiliary = auxiliaryIndex;
            }
            List<GeometryDocument.Bone> children = visit.bone().children();
            for (int index = children.size() - 1; index >= 0; index--) {
                pending.push(new Visit(children.get(index), parentAuxiliary, bindWorld));
            }
        }
        ModelJointPivots.Estimate estimate = ModelJointPivots.estimateWithSources(
                geometry, horizontalScale, verticalScale);
        Map<Integer, Integer> toolSources = new java.util.HashMap<>();
        estimate.toolSources().forEach((joint, bone) -> {
            Entry entry = byBone.get(bone);
            toolSources.put(joint, entry == null
                    ? HumanoidRig.jointFor(bone) : entry.poseIndex());
        });
        return new AuxiliaryBoneLayout(entries, byBone, byName,
                estimate.pivots(), toolSources);
    }

    public List<Entry> entries() {
        return entries;
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    public int totalPoseCount() {
        return HumanoidRig.EPIC_JOINT_COUNT + entries.size();
    }

    public int poseIndexFor(GeometryDocument.Bone bone) {
        Entry entry = byBone.get(bone);
        return entry == null ? HumanoidRig.jointFor(bone) : entry.poseIndex();
    }

    public Entry entryForBoneName(String name) {
        return name == null ? null : byName.get(name.toLowerCase(Locale.ROOT));
    }

    Vector3f jointPivot(int joint) {
        return jointPivots.get(joint);
    }

    int toolAnchorPoseIndex(int joint) {
        return toolAnchorPoseIndices.getOrDefault(joint, -1);
    }

    boolean hasJointPivots() {
        return !jointPivots.isEmpty();
    }

    private static Matrix4f bindLocal(GeometryDocument.Bone bone) {
        return new Matrix4f()
                .translate(bone.pivotX(), bone.pivotY(), bone.pivotZ())
                .rotateZ(bone.rotationZ()).rotateY(bone.rotationY()).rotateX(bone.rotationX())
                .translate(-bone.pivotX(), -bone.pivotY(), -bone.pivotZ());
    }
}
