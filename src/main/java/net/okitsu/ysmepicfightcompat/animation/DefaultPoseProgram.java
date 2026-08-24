package net.okitsu.ysmepicfightcompat.animation;

import net.okitsu.ysmepicfightcompat.geometry.GeometryDocument;
import net.okitsu.ysmepicfightcompat.mesh.CompatHumanoidMesh;
import net.okitsu.ysmepicfightcompat.mesh.HumanoidRig;
import net.okitsu.ysmepicfightcompat.mesh.SkinMeshCompiler;
import yesman.epicfight.api.client.model.MeshPart;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Precomputes the non-animated model form used while Epic Fight owns deformation. */
public final class DefaultPoseProgram {
    private static final double HIDDEN_SCALE = 0.01D;
    private static final int HEALTH = ExpressionEngine.querySlot("query.health");
    private static final int MAX_HEALTH = ExpressionEngine.querySlot("query.max_health");
    private static final int ON_GROUND = ExpressionEngine.querySlot("query.is_on_ground");
    private static final int ALIVE = ExpressionEngine.querySlot("query.is_alive");
    private static final int IDLE = ExpressionEngine.querySlot("ctrl.idle");

    private final Map<String, Boolean> hiddenByBone;
    private final Map<String, Integer> jointByBone;

    public DefaultPoseProgram(GeometryDocument geometry, Map<String, AnimationClip> animations) {
        jointByBone = new HashMap<>();
        geometry.bones().values().forEach(bone -> jointByBone.put(bone.name(), HumanoidRig.jointFor(bone)));
        hiddenByBone = calculateVisibility(geometry, animations);
    }

    public void apply(CompatHumanoidMesh mesh, Map<String, Boolean> firstPersonParts,
                      boolean showUnlistedParts, boolean firstPerson,
                      Set<String> runtimeHiddenBones) {
        for (Map.Entry<String, MeshPart> entry : mesh.partsView()) {
            String name = entry.getKey();
            if (!name.startsWith(SkinMeshCompiler.BONE_PART_PREFIX)) {
                continue;
            }
            String bone = name.substring(SkinMeshCompiler.BONE_PART_PREFIX.length());
            boolean hidden = runtimeHiddenBones == null
                    ? hiddenByBone.getOrDefault(bone, false)
                    : runtimeHiddenBones.contains(bone);
            if (firstPerson) {
                int joint = jointByBone.getOrDefault(bone, HumanoidRig.ROOT);
                hidden |= !isJointVisible(joint, firstPersonParts, showUnlistedParts);
            }
            entry.getValue().setHidden(hidden);
        }
    }

    public int hiddenBoneCount() {
        return (int) hiddenByBone.values().stream().filter(Boolean::booleanValue).count();
    }

    static boolean isJointVisible(int joint, Map<String, Boolean> parts, boolean fallback) {
        return switch (joint) {
            case 1, 2, 3 -> visible(parts, fallback, "rightLeg", "rightPants");
            case 4, 5, 6 -> visible(parts, fallback, "leftLeg", "leftPants");
            case 9 -> visible(parts, fallback, "head", "hat");
            case 10, 11, 12, 13, 14 -> visible(parts, fallback, "rightArm", "rightSleeve");
            case 15, 16, 17, 18, 19 -> visible(parts, fallback, "leftArm", "leftSleeve");
            case 0, 7, 8 -> visible(parts, fallback, "torso", "jacket");
            default -> fallback;
        };
    }

    static Map<String, Boolean> calculateVisibility(
            GeometryDocument geometry, Map<String, AnimationClip> animations) {
        List<AnimationClip> parallel = new ArrayList<>();
        for (AnimationClip clip : animations.values()) {
            if (clip.name().startsWith("pre_parallel") || clip.name().startsWith("parallel")) {
                parallel.add(clip);
            }
        }
        parallel.sort(Comparator.comparing((AnimationClip clip) ->
                        clip.name().startsWith("pre_parallel") ? 0 : 1)
                .thenComparing(AnimationClip::name));

        NeutralEnvironment environment = new NeutralEnvironment();
        Map<String, double[]> directScale = new HashMap<>();
        for (AnimationClip clip : parallel) {
            for (AnimationClip.TimelineEvent event : clip.timeline()) {
                if (event.time() <= 0.0F) {
                    event.statements().forEach(statement ->
                            ExpressionEngine.compile(statement).evaluate(environment));
                }
            }
            double sampledWeight = sampleScalar(clip.blendWeight(), environment);
            double blendWeight = Double.isFinite(sampledWeight) ? sampledWeight : 1.0D;
            if (Math.abs(blendWeight) <= HIDDEN_SCALE) {
                continue;
            }
            clip.boneTracks().forEach((bone, tracks) -> {
                // YSM models may put variable assignments on non-geometry Molang bones.
                // Evaluate channels in the serialized rotation/position/scale order even
                // when only scale contributes to this precomputed visibility snapshot.
                if (tracks.rotation() != null) {
                    sampleAtZero(tracks.rotation(), environment);
                }
                if (tracks.position() != null) {
                    sampleAtZero(tracks.position(), environment);
                }
                if (tracks.scale() != null) {
                    double[] scale = sampleAtZero(tracks.scale(), environment);
                    for (int axis = 0; axis < scale.length; axis++) {
                        scale[axis] = 1.0D + (scale[axis] - 1.0D) * blendWeight;
                        if (!Double.isFinite(scale[axis])) {
                            scale[axis] = 1.0D;
                        }
                    }
                    directScale.put(bone, scale);
                }
            });
        }

        Map<String, Boolean> result = new HashMap<>();
        Map<String, Double> effective = new HashMap<>();
        for (GeometryDocument.Bone bone : geometry.bones().values()) {
            result.put(bone.name(), effectiveScale(bone, directScale, effective) < HIDDEN_SCALE);
        }
        return Map.copyOf(result);
    }

    private static double[] sampleAtZero(AnimationClip.Track track,
                                         ExpressionEngine.Environment environment) {
        AnimationClip.Keyframe selected = track.keyframes().get(0);
        for (AnimationClip.Keyframe candidate : track.keyframes()) {
            if (candidate.time() <= 0.0F) {
                selected = candidate;
            }
        }
        double[] result = new double[3];
        for (int axis = 0; axis < result.length; axis++) {
            String expression = selected.value().expression(axis);
            result[axis] = expression == null
                    ? selected.value().constant(axis)
                    : ExpressionEngine.compile(expression).evaluate(environment);
        }
        return result;
    }

    private static double sampleScalar(AnimationClip.ScalarValue value,
                                       ExpressionEngine.Environment environment) {
        return value.expression() == null ? value.constant()
                : ExpressionEngine.compile(value.expression()).evaluate(environment);
    }

    private static double effectiveScale(GeometryDocument.Bone bone,
                                         Map<String, double[]> direct,
                                         Map<String, Double> memo) {
        Double known = memo.get(bone.name());
        if (known != null) {
            return known;
        }
        double[] scale = direct.get(bone.name());
        double own = scale == null ? 1.0D : Math.min(scale[0], Math.min(scale[1], scale[2]));
        double inherited = bone.parent() == null ? 1.0D : effectiveScale(bone.parent(), direct, memo);
        double result = own * inherited;
        memo.put(bone.name(), result);
        return result;
    }

    private static boolean visible(Map<String, Boolean> parts, boolean fallback, String... names) {
        for (String name : names) {
            if (parts.getOrDefault(name, fallback)) {
                return true;
            }
        }
        return false;
    }

    private static final class NeutralEnvironment implements ExpressionEngine.Environment {
        private final Map<Integer, Double> values = new HashMap<>();
        private final Set<Integer> assigned = new HashSet<>();

        @Override
        public double readVariable(int slot) {
            return values.getOrDefault(slot, 0.0D);
        }

        @Override
        public boolean hasVariable(int slot) {
            return assigned.contains(slot);
        }

        @Override
        public void writeVariable(int slot, double value) {
            values.put(slot, value);
            assigned.add(slot);
        }

        @Override
        public double readQuery(int slot) {
            if (slot == HEALTH || slot == MAX_HEALTH) {
                return 20.0D;
            }
            return slot == ON_GROUND || slot == ALIVE || slot == IDLE ? 1.0D : 0.0D;
        }

        @Override
        public double invoke(String name, double[] arguments) {
            return 0.0D;
        }

        @Override
        public double invokeWithText(String name, String[] arguments) {
            return 0.0D;
        }
    }
}
