package net.okitsu.ysmepicfightcompat.render;

import net.minecraft.util.Mth;
import org.joml.Matrix4f;
import yesman.epicfight.api.utils.math.OpenMatrix4f;

import javax.annotation.Nullable;

/** Converts only a model-authored first-person skin into Epic Fight's world-facing basis. */
public final class FirstPersonPoseTransform {
    private static final float SINGULAR_DETERMINANT_EPSILON = 1.0E-12F;

    private FirstPersonPoseTransform() {
    }

    /**
     * Epic Fight's camera-relative root supplies {@code T(-eye) * incoming}, while
     * its world-facing root supplies {@code Rx(pitch) * Ry(view-model) * T(-eye)}.
     * Left-multiplying an authored skin by this correction bridges those bases
     * without rotating the native first-person pose or changing its provider.
     * The incoming hand transform is removed because the world-facing root does
     * not use it. Renderer framing outside that root remains untouched.
     *
     * @param eyeHeight the same eye translation used by the renderer, in blocks
     */
    @Nullable
    public static OpenMatrix4f forCameraRelativePose(
            boolean cameraRelativeRoot, @Nullable Matrix4f incoming,
            float viewPitch, float viewYaw, float modelYaw, float eyeHeight) {
        if (!cameraRelativeRoot || incoming == null || !incoming.isFinite()
                || incoming.m03() != 0.0F || incoming.m13() != 0.0F
                || incoming.m23() != 0.0F || incoming.m33() != 1.0F
                || !Float.isFinite(viewPitch) || !Float.isFinite(viewYaw)
                || !Float.isFinite(modelYaw) || !Float.isFinite(eyeHeight)
                || eyeHeight <= 0.0F) {
            return null;
        }
        float determinant = incoming.determinant();
        if (!Float.isFinite(determinant)
                || Math.abs(determinant) <= SINGULAR_DETERMINANT_EPSILON) {
            return null;
        }
        // Subtract in double precision so two finite input yaws cannot overflow.
        float relativeYaw = (float) Mth.wrapDegrees((double) viewYaw - modelYaw);
        Matrix4f correction = new Matrix4f(incoming).invert()
                .translate(0.0F, eyeHeight, 0.0F)
                .rotateX((float) Math.toRadians(Mth.wrapDegrees(viewPitch)))
                .rotateY((float) Math.toRadians(relativeYaw))
                .translate(0.0F, -eyeHeight, 0.0F);
        return correction.isFinite() ? OpenMatrix4f.importFromMojangMatrix(correction) : null;
    }
}
