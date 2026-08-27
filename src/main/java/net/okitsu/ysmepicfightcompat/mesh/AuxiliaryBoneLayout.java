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
    private final Map<Integer, Vector3f> extendedArmatureJointPivots;
    private final Map<Integer, Integer> toolAnchorPoseIndices;
    private final Map<Integer, Entry> toolLocatorEntries;
    private final float horizontalScale;
    private final float verticalScale;

    private AuxiliaryBoneLayout(List<Entry> entries,
                                Map<GeometryDocument.Bone, Entry> byBone,
                                Map<String, Entry> byName,
                                Map<Integer, Vector3f> jointPivots,
                                Map<Integer, Vector3f> extendedArmatureJointPivots,
                                Map<Integer, Integer> toolAnchorPoseIndices,
                                Map<Integer, Entry> toolLocatorEntries,
                                float horizontalScale, float verticalScale) {
        this.entries = List.copyOf(entries);
        this.byBone = Map.copyOf(byBone);
        this.byName = Map.copyOf(byName);
        this.jointPivots = Map.copyOf(jointPivots);
        this.extendedArmatureJointPivots = Map.copyOf(extendedArmatureJointPivots);
        this.toolAnchorPoseIndices = Map.copyOf(toolAnchorPoseIndices);
        this.toolLocatorEntries = Map.copyOf(toolLocatorEntries);
        this.horizontalScale = positiveScale(horizontalScale);
        this.verticalScale = positiveScale(verticalScale);
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
        Map<Integer, Entry> toolLocators = new java.util.HashMap<>();
        for (Entry entry : entries) {
            int joint = HumanoidRig.directJointFor(entry.bone());
            if ((joint == HumanoidRig.RIGHT_TOOL || joint == HumanoidRig.LEFT_TOOL)
                    && ModelJointPivots.isPrimaryVariant(entry.bone().name())
                    && ModelJointPivots.isOnExpectedArmBranch(entry.bone(), joint)) {
                toolLocators.putIfAbsent(joint, entry);
            }
        }
        return new AuxiliaryBoneLayout(entries, byBone, byName,
                estimate.pivots(), estimate.extendedArmaturePivots(),
                toolSources, toolLocators,
                horizontalScale, verticalScale);
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

    /** Model-scaled bind pivot used to retarget an attachment between humanoid seams. */
    public Vector3f jointPivot(int joint) {
        Vector3f pivot = jointPivots.get(joint);
        return pivot == null ? null : new Vector3f(pivot);
    }

    /** Bind pivot used only when an integration has extended Epic Fight's armature. */
    Vector3f jointPivot(int joint, boolean extendedArmature) {
        Vector3f pivot = (extendedArmature
                ? extendedArmatureJointPivots : jointPivots).get(joint);
        return pivot == null ? null : new Vector3f(pivot);
    }

    int toolAnchorPoseIndex(int joint) {
        return toolAnchorPoseIndices.getOrDefault(joint, -1);
    }

    Entry toolLocatorEntry(int joint) {
        return toolLocatorEntries.get(joint);
    }

    boolean hasJointPivots() {
        return !jointPivots.isEmpty();
    }

    float horizontalScale() {
        return horizontalScale;
    }

    float verticalScale() {
        return verticalScale;
    }

    private static float positiveScale(float value) {
        return Float.isFinite(value) && value > 1.0E-7F ? value : 1.0F;
    }

    private static Matrix4f bindLocal(GeometryDocument.Bone bone) {
        return new Matrix4f()
                .translate(bone.pivotX(), bone.pivotY(), bone.pivotZ())
                .rotateZ(bone.rotationZ()).rotateY(bone.rotationY()).rotateX(bone.rotationX())
                .translate(-bone.pivotX(), -bone.pivotY(), -bone.pivotZ());
    }
}
