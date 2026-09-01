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

    private record Selection(Entry entry, boolean present) {
        private static final Selection MISSING = new Selection(null, false);
    }

    private final List<Entry> entries;
    private final Map<GeometryDocument.Bone, Entry> byBone;
    private final Map<String, Entry> byName;
    private final Map<Integer, Vector3f> jointPivots;
    private final Map<Integer, Vector3f> extendedArmatureJointPivots;
    private final Map<Integer, Integer> toolAnchorPoseIndices;
    private final Map<Integer, Entry> toolLocatorEntries;
    private final Entry elytraLocatorEntry;
    private final Map<Integer, Entry> attachmentEntries;
    private final Map<Integer, Vector3f> attachmentPivots;
    private final float horizontalScale;
    private final float verticalScale;

    private AuxiliaryBoneLayout(List<Entry> entries,
                                Map<GeometryDocument.Bone, Entry> byBone,
                                Map<String, Entry> byName,
                                Map<Integer, Vector3f> jointPivots,
                                Map<Integer, Vector3f> extendedArmatureJointPivots,
                                Map<Integer, Integer> toolAnchorPoseIndices,
                                Map<Integer, Entry> toolLocatorEntries,
                                Entry elytraLocatorEntry,
                                Map<Integer, Entry> attachmentEntries,
                                Map<Integer, Vector3f> attachmentPivots,
                                float horizontalScale, float verticalScale) {
        this.entries = List.copyOf(entries);
        this.byBone = Map.copyOf(byBone);
        this.byName = Map.copyOf(byName);
        this.jointPivots = Map.copyOf(jointPivots);
        this.extendedArmatureJointPivots = Map.copyOf(extendedArmatureJointPivots);
        this.toolAnchorPoseIndices = Map.copyOf(toolAnchorPoseIndices);
        this.toolLocatorEntries = Map.copyOf(toolLocatorEntries);
        this.elytraLocatorEntry = elytraLocatorEntry;
        this.attachmentEntries = Map.copyOf(attachmentEntries);
        this.attachmentPivots = Map.copyOf(attachmentPivots);
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
        Map<Integer, Entry> attachmentSources = attachmentSources(
                entries, byBone, estimate.toolSources());
        Map<Integer, Vector3f> attachmentPivots = attachmentPivots(
                attachmentSources, estimate.pivots(), horizontalScale, verticalScale);
        Entry elytraLocator = uniqueNamedEntry(
                geometry, byBone, "ElytraLocator");
        return new AuxiliaryBoneLayout(entries, byBone, byName,
                estimate.pivots(), estimate.extendedArmaturePivots(),
                toolSources, toolLocators, elytraLocator,
                attachmentSources, attachmentPivots,
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

    Entry elytraLocatorEntry() {
        return elytraLocatorEntry;
    }

    Entry attachmentEntry(int joint) {
        return attachmentEntries.get(joint);
    }

    Vector3f attachmentPivot(int joint) {
        Vector3f pivot = attachmentPivots.get(joint);
        return pivot == null ? null : new Vector3f(pivot);
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

    private static Map<Integer, Entry> attachmentSources(
            List<Entry> entries, Map<GeometryDocument.Bone, Entry> byBone,
            Map<Integer, GeometryDocument.Bone> toolSources) {
        Map<Integer, Entry> result = new java.util.HashMap<>();
        Entry root = preferred(entries, HumanoidRig.ROOT, "root", "center").entry();
        Selection torsoSelection = preferred(entries, HumanoidRig.TORSO,
                "allbody", "torso", "waist", "hips", "hip", "pelvis");
        Entry torso = torsoSelection.entry();
        if (!torsoSelection.present()) {
            torso = preferred(entries, HumanoidRig.TORSO, "downbody").entry();
        }
        Selection chestSelection = preferred(
                entries, HumanoidRig.CHEST, "upperbody", "chest");
        Entry chest = chestSelection.entry();
        if (!chestSelection.present()) {
            chest = preferred(entries, HumanoidRig.CHEST, "upbody").entry();
        }
        Entry head = preferred(entries, HumanoidRig.HEAD, "head", "allhead").entry();
        Entry rightArm = preferred(entries, HumanoidRig.RIGHT_ARM,
                "rightarm", "armright").entry();
        Entry leftArm = preferred(entries, HumanoidRig.LEFT_ARM,
                "leftarm", "armleft").entry();
        Selection rightForearmSelection = preferred(entries, HumanoidRig.RIGHT_HAND,
                "rightforearm", "forearmright");
        Selection leftForearmSelection = preferred(entries, HumanoidRig.LEFT_HAND,
                "leftforearm", "forearmleft");
        Entry rightForearm = rightForearmSelection.entry();
        Entry leftForearm = leftForearmSelection.entry();
        Entry rightHand = preferred(entries, HumanoidRig.RIGHT_HAND,
                "righthand", "handright").entry();
        Entry leftHand = preferred(entries, HumanoidRig.LEFT_HAND,
                "lefthand", "handleft").entry();
        Entry rightThigh = preferred(entries, HumanoidRig.RIGHT_THIGH,
                "rightleg", "legright").entry();
        Entry leftThigh = preferred(entries, HumanoidRig.LEFT_THIGH,
                "leftleg", "legleft").entry();
        Selection rightLowerLegSelection = preferred(entries, HumanoidRig.RIGHT_LEG,
                "rightlowerleg", "lowerlegright",
                "rightcalf");
        Selection leftLowerLegSelection = preferred(entries, HumanoidRig.LEFT_LEG,
                "leftlowerleg", "lowerlegleft",
                "leftcalf");
        Entry rightLowerLeg = rightLowerLegSelection.entry();
        Entry leftLowerLeg = leftLowerLegSelection.entry();
        Entry rightHandSource = rightForearmSelection.present()
                ? rightForearm : rightHand;
        Entry leftHandSource = leftForearmSelection.present()
                ? leftForearm : leftHand;
        Entry rightLegSource = rightLowerLegSelection.present()
                ? rightLowerLeg : rightThigh;
        Entry leftLegSource = leftLowerLegSelection.present()
                ? leftLowerLeg : leftThigh;

        put(result, HumanoidRig.ROOT, root);
        put(result, HumanoidRig.TORSO, torso);
        put(result, HumanoidRig.CHEST, chest);
        put(result, HumanoidRig.HEAD, head);
        put(result, HumanoidRig.RIGHT_SHOULDER, rightArm);
        put(result, HumanoidRig.RIGHT_ARM, rightArm);
        put(result, HumanoidRig.RIGHT_HAND, rightHandSource);
        put(result, HumanoidRig.RIGHT_ELBOW, rightHandSource);
        put(result, HumanoidRig.LEFT_SHOULDER, leftArm);
        put(result, HumanoidRig.LEFT_ARM, leftArm);
        put(result, HumanoidRig.LEFT_HAND, leftHandSource);
        put(result, HumanoidRig.LEFT_ELBOW, leftHandSource);
        put(result, HumanoidRig.RIGHT_THIGH, rightThigh);
        put(result, HumanoidRig.RIGHT_LEG, rightLegSource);
        put(result, HumanoidRig.RIGHT_KNEE, rightLegSource);
        put(result, HumanoidRig.LEFT_THIGH, leftThigh);
        put(result, HumanoidRig.LEFT_LEG, leftLegSource);
        put(result, HumanoidRig.LEFT_KNEE, leftLegSource);

        Entry rightTool = byBone.get(toolSources.get(HumanoidRig.RIGHT_TOOL));
        Entry leftTool = byBone.get(toolSources.get(HumanoidRig.LEFT_TOOL));
        put(result, HumanoidRig.RIGHT_TOOL, rightTool);
        put(result, HumanoidRig.LEFT_TOOL, leftTool);
        return result;
    }

    private static Map<Integer, Vector3f> attachmentPivots(
            Map<Integer, Entry> sources, Map<Integer, Vector3f> estimated,
            float horizontalScale, float verticalScale) {
        Map<Integer, Vector3f> result = new java.util.HashMap<>();
        sources.forEach((joint, source) -> {
            int pivotJoint = switch (joint) {
                case HumanoidRig.ROOT -> HumanoidRig.TORSO;
                case HumanoidRig.RIGHT_KNEE -> HumanoidRig.RIGHT_LEG;
                case HumanoidRig.LEFT_KNEE -> HumanoidRig.LEFT_LEG;
                default -> joint;
            };
            Vector3f pivot = estimated.get(pivotJoint);
            if (pivot == null) {
                pivot = new Vector3f(source.bone().pivotX(), source.bone().pivotY(),
                        source.bone().pivotZ());
                source.bindWorld().transformPosition(pivot);
                pivot.set(pivot.x() * horizontalScale, pivot.y() * verticalScale,
                        pivot.z() * horizontalScale);
            } else {
                pivot = new Vector3f(pivot);
            }
            if (Float.isFinite(pivot.x()) && Float.isFinite(pivot.y())
                    && Float.isFinite(pivot.z())) {
                result.put(joint, pivot);
            }
        });
        return result;
    }

    private static Selection preferred(List<Entry> entries, int joint, String... names) {
        for (String name : names) {
            Selection selected = select(entries, joint, name);
            if (selected.present()) {
                return selected;
            }
            String defaultName = name + "default";
            selected = select(entries, joint, defaultName);
            if (selected.present()) {
                return selected;
            }
        }
        return Selection.MISSING;
    }

    private static Entry uniqueNamedEntry(
            GeometryDocument geometry,
            Map<GeometryDocument.Bone, Entry> byBone,
            String name) {
        GeometryDocument.Bone selected = null;
        for (GeometryDocument.Bone bone : geometry.bones().values()) {
            if (!bone.name().equalsIgnoreCase(name)) {
                continue;
            }
            if (selected != null) {
                return null;
            }
            selected = bone;
        }
        return selected == null ? null : byBone.get(selected);
    }

    private static Selection select(List<Entry> entries, int joint, String name) {
        Entry selected = null;
        for (Entry entry : entries) {
            if (!rawName(entry.bone().name()).equals(name)
                    || !compatibleBranch(entry.bone(), joint)) {
                continue;
            }
            if (selected != null) {
                return new Selection(null, true);
            }
            selected = entry;
        }
        return selected == null ? Selection.MISSING : new Selection(selected, true);
    }

    private static boolean compatibleBranch(GeometryDocument.Bone bone, int joint) {
        boolean right = joint == HumanoidRig.RIGHT_THIGH
                || joint == HumanoidRig.RIGHT_LEG || joint == HumanoidRig.RIGHT_KNEE
                || joint == HumanoidRig.RIGHT_SHOULDER || joint == HumanoidRig.RIGHT_ARM
                || joint == HumanoidRig.RIGHT_HAND || joint == HumanoidRig.RIGHT_TOOL
                || joint == HumanoidRig.RIGHT_ELBOW;
        boolean left = joint == HumanoidRig.LEFT_THIGH
                || joint == HumanoidRig.LEFT_LEG || joint == HumanoidRig.LEFT_KNEE
                || joint == HumanoidRig.LEFT_SHOULDER || joint == HumanoidRig.LEFT_ARM
                || joint == HumanoidRig.LEFT_HAND || joint == HumanoidRig.LEFT_TOOL
                || joint == HumanoidRig.LEFT_ELBOW;
        if (!right && !left) {
            return true;
        }
        for (GeometryDocument.Bone parent = bone.parent(); parent != null;
             parent = parent.parent()) {
            int direct = HumanoidRig.directJointFor(parent);
            if (right && (direct == HumanoidRig.LEFT_THIGH
                    || direct == HumanoidRig.LEFT_LEG
                    || direct == HumanoidRig.LEFT_ARM
                    || direct == HumanoidRig.LEFT_HAND
                    || direct == HumanoidRig.LEFT_TOOL)
                    || left && (direct == HumanoidRig.RIGHT_THIGH
                    || direct == HumanoidRig.RIGHT_LEG
                    || direct == HumanoidRig.RIGHT_ARM
                    || direct == HumanoidRig.RIGHT_HAND
                    || direct == HumanoidRig.RIGHT_TOOL)) {
                return false;
            }
        }
        return true;
    }

    private static String rawName(String name) {
        return name == null ? "" : name.toLowerCase(Locale.ROOT)
                .replace("_", "").replace(" ", "");
    }

    private static void put(Map<Integer, Entry> target, int joint, Entry entry) {
        if (entry != null) {
            target.put(joint, entry);
        }
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
