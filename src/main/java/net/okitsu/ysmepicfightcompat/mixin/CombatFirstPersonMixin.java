package net.okitsu.ysmepicfightcompat.mixin;

import net.minecraft.client.player.LocalPlayer;
import net.okitsu.ysmepicfightcompat.render.CombatMeshResolver;
import net.okitsu.ysmepicfightcompat.render.RenderFrameContext;
import net.okitsu.ysmepicfightcompat.mesh.CompatHumanoidMesh;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import yesman.epicfight.api.asset.AssetAccessor;
import yesman.epicfight.client.mesh.HumanoidMesh;
import yesman.epicfight.client.renderer.FirstPersonRenderer;
import yesman.epicfight.client.world.capabilites.entitypatch.player.LocalPlayerPatch;

/** Selects and binds the converted model for Epic Fight's first-person pass. */
@Mixin(value = FirstPersonRenderer.class, remap = false)
public abstract class CombatFirstPersonMixin {
    @Inject(method = "getMeshProvider(Lyesman/epicfight/client/world/capabilites/entitypatch/player/LocalPlayerPatch;)Lyesman/epicfight/api/asset/AssetAccessor;",
            at = @At("HEAD"), cancellable = true)
    private void ysmCompat$resolve(LocalPlayerPatch patch,
                                   CallbackInfoReturnable<AssetAccessor<HumanoidMesh>> result) {
        LocalPlayer player = patch.getOriginal();
        AssetAccessor<HumanoidMesh> mesh = CombatMeshResolver.forPlayer(player);
        if (mesh == null) {
            return;
        }
        HumanoidMesh selected = mesh.get();
        if (!(selected instanceof CompatHumanoidMesh converted)) {
            return;
        }
        RenderFrameContext.bindMesh(player, true, converted);
        result.setReturnValue(mesh);
    }
}
