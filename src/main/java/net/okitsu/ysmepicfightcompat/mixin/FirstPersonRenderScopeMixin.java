package net.okitsu.ysmepicfightcompat.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.okitsu.ysmepicfightcompat.render.RenderFrameContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import yesman.epicfight.client.events.engine.RenderEngine;
import yesman.epicfight.client.renderer.FirstPersonRenderer;
import yesman.epicfight.client.world.capabilites.entitypatch.player.LocalPlayerPatch;

import java.util.Map;

/** Gives Epic Fight's first-person render a nested and exception-safe compat scope. */
@Mixin(value = RenderEngine.Events.class, remap = false)
public abstract class FirstPersonRenderScopeMixin {
    private static final Map<String, Boolean> DEFAULT_ARMS = Map.of(
            "leftArm", true, "leftSleeve", true,
            "rightArm", true, "rightSleeve", true);

    @Redirect(
            method = "renderHand(Lnet/minecraftforge/client/event/RenderHandEvent;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lyesman/epicfight/client/renderer/FirstPersonRenderer;render(Lnet/minecraft/client/player/LocalPlayer;Lyesman/epicfight/client/world/capabilites/entitypatch/player/LocalPlayerPatch;Lnet/minecraft/client/renderer/entity/LivingEntityRenderer;Lnet/minecraft/client/renderer/MultiBufferSource;Lcom/mojang/blaze3d/vertex/PoseStack;IF)V",
                    remap = false
            ),
            remap = false
    )
    private static void ysmCompat$renderScoped(
            FirstPersonRenderer renderer, LocalPlayer player, LocalPlayerPatch patch,
            LivingEntityRenderer<LocalPlayer, PlayerModel<LocalPlayer>> entityRenderer,
            MultiBufferSource buffers, PoseStack matrices, int light, float partialTick) {
        var settings = patch.getPovSettings();
        Map<String, Boolean> visibleParts = settings == null
                ? DEFAULT_ARMS : settings.visibilities();
        boolean showUnlisted = settings != null && settings.visibilityOthers();
        RenderFrameContext.Frame scope = RenderFrameContext.pushFirstPerson(
                player, visibleParts, showUnlisted,
                patch.getAccurateYRot(partialTick));
        try {
            renderer.render(player, patch, entityRenderer, buffers,
                    matrices, light, partialTick);
        } finally {
            RenderFrameContext.pop(scope);
        }
    }
}
