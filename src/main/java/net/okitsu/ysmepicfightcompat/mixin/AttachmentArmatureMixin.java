package net.okitsu.ysmepicfightcompat.mixin;

import net.okitsu.ysmepicfightcompat.render.AttachmentArmatureScope;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import yesman.epicfight.api.animation.Joint;
import yesman.epicfight.api.animation.Pose;
import yesman.epicfight.api.model.Armature;
import yesman.epicfight.api.utils.math.OpenMatrix4f;

/** Exposes the already-drawn skeleton only within a converted attachment layer. */
@Mixin(value = Armature.class, remap = false)
public abstract class AttachmentArmatureMixin {
    @Inject(method = "setPose(Lyesman/epicfight/api/animation/Pose;)V",
            at = @At("HEAD"), cancellable = true, remap = false)
    private void ysmCompat$keepGameplayPose(Pose pose, CallbackInfo info) {
        if (AttachmentArmatureScope.suppressPoseWrite((Armature) (Object) this)) {
            info.cancel();
        }
    }

    @Inject(method = "getPoseMatrices()[Lyesman/epicfight/api/utils/math/OpenMatrix4f;",
            at = @At("RETURN"), cancellable = true, remap = false)
    private void ysmCompat$readDisplayedPose(CallbackInfoReturnable<OpenMatrix4f[]> info) {
        info.setReturnValue(AttachmentArmatureScope.resolvePoseMatrices(
                (Armature) (Object) this, info.getReturnValue(), false));
    }

    @Inject(method = "getPoseAsTransformMatrix(Lyesman/epicfight/api/animation/Pose;Z)" +
            "[Lyesman/epicfight/api/utils/math/OpenMatrix4f;",
            at = @At("RETURN"), cancellable = true, remap = false)
    private void ysmCompat$readDisplayedSnapshot(
            Pose pose, boolean applyToOrigin, CallbackInfoReturnable<OpenMatrix4f[]> info) {
        info.setReturnValue(AttachmentArmatureScope.resolvePoseMatrices(
                (Armature) (Object) this, info.getReturnValue(), applyToOrigin));
    }

    @Inject(method = {
            "getBoundTransformFor(Lyesman/epicfight/api/animation/Pose;" +
                    "Lyesman/epicfight/api/animation/Joint;)Lyesman/epicfight/api/utils/math/OpenMatrix4f;",
            "getBindedTransformFor(Lyesman/epicfight/api/animation/Pose;" +
                    "Lyesman/epicfight/api/animation/Joint;)Lyesman/epicfight/api/utils/math/OpenMatrix4f;"
    }, at = @At("RETURN"), cancellable = true, remap = false)
    private void ysmCompat$readDisplayedJoint(
            Pose pose, Joint joint, CallbackInfoReturnable<OpenMatrix4f> info) {
        info.setReturnValue(AttachmentArmatureScope.resolveJointPose(
                (Armature) (Object) this, joint, info.getReturnValue()));
    }
}
