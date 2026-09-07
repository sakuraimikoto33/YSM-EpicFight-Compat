package net.okitsu.ysmepicfightcompat.mesh;

import net.okitsu.ysmepicfightcompat.geometry.GeometryDocument;
import yesman.epicfight.api.utils.math.OpenMatrix4f;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** A read-only hand-locator decision made from one completed model frame. */
public record HandLocatorSelection(Status status,
                                   @Nullable AuxiliaryBoneLayout.Entry locator) {
    // Match the existing authored item-switch collapse test. Reflections are visible.
    private static final double COLLAPSED_DETERMINANT = 1.0E-8F;

    public enum Status {
        MISSING,
        HIDDEN,
        VISIBLE,
        AMBIGUOUS,
        INVALID
    }

    public HandLocatorSelection {
        Objects.requireNonNull(status, "status");
        if ((status == Status.VISIBLE) != (locator != null)) {
            throw new IllegalArgumentException("Only a visible selection has a locator");
        }
    }

    /**
     * No geometry or humanoid branch is required: a locator may be an empty mouth,
     * paw, or tail child. The caller supplies candidates in geometry order; order
     * never resolves ambiguity. This mask is authored visibility, not first-person
     * body-part culling. Neither input matrices nor model state are changed.
     *
     * <p>Explicitly hidden ancestors take precedence over matrix validation. A bad
     * matrix on any otherwise eligible candidate makes the result invalid, rather
     * than silently hiding it and selecting a different branch. A collapsed final
     * matrix is an intentional hide, distinct from a missing locator.</p>
     */
    public static HandLocatorSelection resolve(
            List<AuxiliaryBoneLayout.Entry> candidates,
            @Nullable OpenMatrix4f[] complete,
            @Nullable Set<String> hiddenBones) {
        if (candidates == null) {
            return result(Status.INVALID);
        }
        if (candidates.isEmpty()) {
            return result(Status.MISSING);
        }
        AuxiliaryBoneLayout.Entry selected = null;
        boolean ambiguous = false;
        for (AuxiliaryBoneLayout.Entry candidate : candidates) {
            if (candidate == null || candidate.bone() == null) {
                return result(Status.INVALID);
            }
            Status hidden = hiddenStatus(candidate.bone(), hiddenBones);
            if (hidden == Status.INVALID) {
                return result(Status.INVALID);
            }
            if (hidden == Status.HIDDEN) {
                continue;
            }
            int poseIndex = candidate.poseIndex();
            if (complete == null || poseIndex < 0 || poseIndex >= complete.length
                    || !finite(complete[poseIndex])) {
                return result(Status.INVALID);
            }
            if (Math.abs(determinant(complete[poseIndex])) < COLLAPSED_DETERMINANT) {
                continue;
            }
            if (selected != null) {
                ambiguous = true;
            } else {
                selected = candidate;
            }
        }
        return ambiguous ? result(Status.AMBIGUOUS)
                : selected == null ? result(Status.HIDDEN)
                : new HandLocatorSelection(Status.VISIBLE, selected);
    }

    @Nullable
    private static Status hiddenStatus(GeometryDocument.Bone bone,
                                        @Nullable Set<String> hiddenBones) {
        int depth = 0;
        for (GeometryDocument.Bone current = bone; current != null;
             current = current.parent()) {
            // The parsed layout is bounded. Do not loop forever on malformed input.
            if (++depth > AuxiliaryBoneLayout.MAX_MODEL_BONES) {
                return Status.INVALID;
            }
            if (hiddenBones != null && hiddenBones.contains(current.name())) {
                return Status.HIDDEN;
            }
        }
        return null;
    }

    private static HandLocatorSelection result(Status status) {
        return new HandLocatorSelection(status, null);
    }

    private static double determinant(OpenMatrix4f value) {
        // Promote before multiplication so finite large/small bases do not overflow
        // or underflow merely because this visibility check uses float arithmetic.
        return (double) value.m00 * ((double) value.m11 * value.m22
                - (double) value.m12 * value.m21)
                - (double) value.m10 * ((double) value.m01 * value.m22
                - (double) value.m02 * value.m21)
                + (double) value.m20 * ((double) value.m01 * value.m12
                - (double) value.m02 * value.m11);
    }

    private static boolean finite(@Nullable OpenMatrix4f value) {
        return value != null
                && Float.isFinite(value.m00) && Float.isFinite(value.m01)
                && Float.isFinite(value.m02) && Float.isFinite(value.m03)
                && Float.isFinite(value.m10) && Float.isFinite(value.m11)
                && Float.isFinite(value.m12) && Float.isFinite(value.m13)
                && Float.isFinite(value.m20) && Float.isFinite(value.m21)
                && Float.isFinite(value.m22) && Float.isFinite(value.m23)
                && Float.isFinite(value.m30) && Float.isFinite(value.m31)
                && Float.isFinite(value.m32) && Float.isFinite(value.m33);
    }
}
