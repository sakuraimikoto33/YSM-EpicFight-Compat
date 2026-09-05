package net.okitsu.ysmepicfightcompat.animation;

import net.minecraft.world.phys.Vec3;

import java.util.LinkedHashMap;
import java.util.Map;

/** Immutable previous-completed-frame data; queries never inspect live renderer bones. */
public final class BoneQuerySnapshot {
    public static final BoneQuerySnapshot EMPTY = new BoneQuerySnapshot(Map.of());
    private static final int MAX_BONES = 1000;
    private final Map<String, BoneValues> bones;

    /** Rotation is expressed in degrees; positions use authored model units. */
    public record BoneValues(Vec3 rotation, Vec3 position, Vec3 scale, Vec3 absolutePivot) {
        public BoneValues {
            rotation = finite(rotation, Vec3.ZERO);
            position = finite(position, Vec3.ZERO);
            scale = finite(scale, new Vec3(1.0D, 1.0D, 1.0D));
            absolutePivot = finite(absolutePivot, Vec3.ZERO);
        }
    }

    public BoneQuerySnapshot(Map<String, BoneValues> source) {
        Map<String, BoneValues> copy = new LinkedHashMap<>();
        if (source != null) {
            for (Map.Entry<String, BoneValues> entry : source.entrySet()) {
                if (copy.size() >= MAX_BONES) {
                    break;
                }
                if (entry.getKey() != null && !entry.getKey().isBlank()
                        && entry.getValue() != null) {
                    copy.put(entry.getKey(), entry.getValue());
                }
            }
        }
        bones = Map.copyOf(copy);
    }

    public Map<String, Double> query(String function, String name) {
        BoneValues bone = name == null ? null : bones.get(name);
        Vec3 value = bone == null ? Vec3.ZERO : switch (function) {
            case "ysm.bone_rot" -> bone.rotation();
            case "ysm.bone_pos" -> bone.position();
            case "ysm.bone_scale" -> bone.scale();
            case "ysm.bone_pivot_abs" -> bone.absolutePivot();
            default -> Vec3.ZERO;
        };
        return Map.of("x", value.x, "y", value.y, "z", value.z);
    }

    /** Immutable value access for the next completed-frame capture. */
    public BoneValues values(String name) {
        return name == null ? null : bones.get(name);
    }

    private static Vec3 finite(Vec3 value, Vec3 fallback) {
        return value != null && Double.isFinite(value.x) && Double.isFinite(value.y)
                && Double.isFinite(value.z) ? value : fallback;
    }
}
