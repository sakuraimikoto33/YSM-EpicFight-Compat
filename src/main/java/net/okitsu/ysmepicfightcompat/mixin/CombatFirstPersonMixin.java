package net.okitsu.ysmepicfightcompat.mixin;

import net.minecraft.client.player.LocalPlayer;
import net.okitsu.ysmepicfightcompat.render.CombatMeshResolver;
import net.okitsu.ysmepicfightcompat.render.RenderFrameContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import yesman.epicfight.api.asset.AssetAccessor;
import yesman.epicfight.client.mesh.HumanoidMesh;
import yesman.epicfight.client.renderer.FirstPersonRenderer;
import yesman.epicfight.client.world.capabilites.entitypatch.player.LocalPlayerPatch;

import java.util.Map;

/** Selects the converted model and visibility set for Epic Fight's first-person pass. */
@Mixin(value = FirstPersonRenderer.class, remap = false)
public abstract class CombatFirstPersonMixin {
    private static final Map<String, Boolean> DEFAULT_ARMS = Map.of(
            "leftArm", true, "leftSleeve", true,
            "rightArm", true, "rightSleeve", true);

    @Inject(method = "getMeshProvider(Lyesman/epicfight/client/world/capabilites/entitypatch/player/LocalPlayerPatch;)Lyesman/epicfight/api/asset/AssetAccessor;",
            at = @At("HEAD"), cancellable = true)
    private void ysmCompat$resolve(LocalPlayerPatch patch,
                                   CallbackInfoReturnable<AssetAccessor<HumanoidMesh>> result) {
        LocalPlayer player = patch.getOriginal();
        AssetAccessor<HumanoidMesh> mesh = CombatMeshResolver.forPlayer(player);
        if (mesh == null) {
            return;
        }
        var settings = patch.getPovSettings();
        if (settings == null) {
            RenderFrameContext.firstPerson(player, DEFAULT_ARMS, false);
        } else {
            RenderFrameContext.firstPerson(player, settings.visibilities(),
                    settings.visibilityOthers());
        }
        result.setReturnValue(mesh);
    }

    @Inject(method = "render(Lnet/minecraft/client/player/LocalPlayer;Lyesman/epicfight/client/world/capabilites/entitypatch/player/LocalPlayerPatch;Lnet/minecraft/client/renderer/entity/LivingEntityRenderer;Lnet/minecraft/client/renderer/MultiBufferSource;Lcom/mojang/blaze3d/vertex/PoseStack;IF)V",
            at = @At("RETURN"))
    private void ysmCompat$finish(CallbackInfo callback) {
        RenderFrameContext.clear();
    }
}
