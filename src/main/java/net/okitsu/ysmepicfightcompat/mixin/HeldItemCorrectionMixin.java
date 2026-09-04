package net.okitsu.ysmepicfightcompat.mixin;

import net.minecraft.world.InteractionHand;
import net.okitsu.ysmepicfightcompat.render.HeldItemPoseResolver;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import yesman.epicfight.api.utils.math.OpenMatrix4f;
import yesman.epicfight.api.animation.Joint;
import yesman.epicfight.client.renderer.patched.item.RenderItemBase;
import yesman.epicfight.world.capabilities.entitypatch.LivingEntityPatch;

/** Selects either Epic Fight's Tool correction or the complete authored YSM locator. */
@Mixin(value = RenderItemBase.class, remap = false)
public abstract class HeldItemCorrectionMixin {
    @Redirect(
            method = "getCorrectionMatrix(Lyesman/epicfight/world/capabilities/entitypatch/LivingEntityPatch;Lnet/minecraft/world/InteractionHand;[Lyesman/epicfight/api/utils/math/OpenMatrix4f;)Lyesman/epicfight/api/utils/math/OpenMatrix4f;",
            at = @At(
                    value = "INVOKE",
                    target = "Lyesman/epicfight/world/capabilities/entitypatch/LivingEntityPatch;getParentJointOfHand(Lnet/minecraft/world/InteractionHand;)Lyesman/epicfight/api/animation/Joint;",
                    remap = false
            ),
            remap = false
    )
    private Joint ysmCompat$keepOrdinaryLadderItemInHand(
            LivingEntityPatch<?> patch, InteractionHand hand) {
        return HeldItemPoseResolver.resolveParentJoint(patch, hand);
    }

    @Redirect(
            method = "getCorrectionMatrix(Lyesman/epicfight/world/capabilities/entitypatch/LivingEntityPatch;Lnet/minecraft/world/InteractionHand;[Lyesman/epicfight/api/utils/math/OpenMatrix4f;)Lyesman/epicfight/api/utils/math/OpenMatrix4f;",
            at = @At(
                    value = "INVOKE",
                    target = "Lyesman/epicfight/api/utils/math/OpenMatrix4f;mulFront(Lyesman/epicfight/api/utils/math/OpenMatrix4f;)Lyesman/epicfight/api/utils/math/OpenMatrix4f;",
                    remap = false
            ),
            remap = false
    )
    private OpenMatrix4f ysmCompat$correctFist(
            OpenMatrix4f correction, OpenMatrix4f selectedPose,
            LivingEntityPatch<?> patch, InteractionHand hand, OpenMatrix4f[] poses) {
        return HeldItemPoseResolver.resolveCorrection(
                patch, poses, correction, selectedPose);
    }
}
