package net.okitsu.ysmepicfightcompat.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.okitsu.ysmepicfightcompat.render.RenderFrameContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import yesman.epicfight.api.utils.math.OpenMatrix4f;
import yesman.epicfight.client.renderer.patched.item.RenderItemBase;
import yesman.epicfight.client.renderer.patched.layer.PatchedItemInHandLayer;
import yesman.epicfight.world.capabilities.entitypatch.LivingEntityPatch;

/** Suppresses Epic Fight's duplicate item only when the active YSM model supplies the prop. */
@Mixin(value = PatchedItemInHandLayer.class, remap = false)
public abstract class PatchedItemInHandLayerMixin {
    @Redirect(
            method = "renderLayer(Lyesman/epicfight/world/capabilities/entitypatch/LivingEntityPatch;Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/client/renderer/entity/layers/RenderLayer;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I[Lyesman/epicfight/api/utils/math/OpenMatrix4f;FFFF)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lyesman/epicfight/client/renderer/patched/item/RenderItemBase;renderItemInHand(Lnet/minecraft/world/item/ItemStack;Lyesman/epicfight/world/capabilities/entitypatch/LivingEntityPatch;Lnet/minecraft/world/InteractionHand;[Lyesman/epicfight/api/utils/math/OpenMatrix4f;Lnet/minecraft/client/renderer/MultiBufferSource;Lcom/mojang/blaze3d/vertex/PoseStack;IF)V",
                    remap = false
            ),
            require = 2,
            remap = false
    )
    private void ysmCompat$renderUnlessModelReplaces(
            RenderItemBase renderer, ItemStack stack, LivingEntityPatch<?> patch,
            InteractionHand hand, OpenMatrix4f[] poses, MultiBufferSource buffers,
            PoseStack matrices, int light, float partialTick) {
        LivingEntity entity = patch.getOriginal();
        if (RenderFrameContext.suppressesHeldItem(entity, hand)) {
            return;
        }
        renderer.renderItemInHand(stack, patch, hand, poses, buffers,
                matrices, light, partialTick);
    }
}
