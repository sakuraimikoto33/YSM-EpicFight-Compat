package net.okitsu.ysmepicfightcompat.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.world.entity.Pose;
import net.okitsu.ysmepicfightcompat.render.RenderFrameContext;
import net.okitsu.ysmepicfightcompat.render.EpicFightPoseOwnership;
import net.okitsu.ysmepicfightcompat.render.FirstPersonPoseTransform;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import yesman.epicfight.client.events.engine.RenderEngine;
import yesman.epicfight.api.client.animation.AnimationSubFileReader.PovSettings.RootTransformation;
import yesman.epicfight.client.renderer.FirstPersonRenderer;
import yesman.epicfight.client.world.capabilites.entitypatch.player.LocalPlayerPatch;

import java.util.Map;

/** Gives Epic Fight's first-person render a nested and exception-safe compat scope. */
@Mixin(value = RenderEngine.class, remap = false)
public abstract class FirstPersonRenderScopeMixin {
    private static final Map<String, Boolean> DEFAULT_ARMS = Map.of(
            "leftArm", true, "leftSleeve", true,
            "rightArm", true, "rightSleeve", true);

    @Redirect(
            method = "epicfight$renderHand(Lnet/neoforged/neoforge/client/event/RenderHandEvent;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lyesman/epicfight/client/renderer/FirstPersonRenderer;render(Lnet/minecraft/client/player/LocalPlayer;Lyesman/epicfight/client/world/capabilites/entitypatch/player/LocalPlayerPatch;Lnet/minecraft/client/renderer/entity/LivingEntityRenderer;Lnet/minecraft/client/renderer/MultiBufferSource;Lcom/mojang/blaze3d/vertex/PoseStack;IF)V",
                    remap = false
            ),
            remap = false
    )
    private void ysmCompat$renderScoped(
            FirstPersonRenderer renderer, LocalPlayer player, LocalPlayerPatch patch,
            LivingEntityRenderer<LocalPlayer, PlayerModel<LocalPlayer>> entityRenderer,
            MultiBufferSource buffers, PoseStack matrices, int light, float partialTick) {
        var settings = patch.getPovSettings();
        Map<String, Boolean> visibleParts = settings == null
                ? DEFAULT_ARMS : settings.visibilities();
        boolean showUnlisted = settings != null && settings.visibilityOthers();
        float modelYaw = patch.getAccurateYRot(partialTick);
        // CAMERA/null POVs omit the outer look rotations needed by a canonical
        // full-body YSM pose. EF retains the incoming hand framing; only the
        // eventual YSM-owned skin will consume this root correction.
        var fullBodyTransform = FirstPersonPoseTransform.forCameraRelativePose(
                settings == null || settings.rootTransformation() == RootTransformation.CAMERA,
                matrices.last().pose(), player.getViewXRot(partialTick),
                player.getYRot(), modelYaw, player.getEyeHeight(Pose.STANDING));
        RenderFrameContext.Frame scope = RenderFrameContext.pushFirstPerson(
                player, visibleParts, showUnlisted,
                modelYaw, EpicFightPoseOwnership.actionOwnsPose(player, patch),
                fullBodyTransform);
        try {
            renderer.render(player, patch, entityRenderer, buffers,
                    matrices, light, partialTick);
        } finally {
            RenderFrameContext.pop(scope);
        }
    }
}
