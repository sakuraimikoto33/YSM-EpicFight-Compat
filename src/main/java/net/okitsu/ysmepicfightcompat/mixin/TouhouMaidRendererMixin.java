package net.okitsu.ysmepicfightcompat.mixin;

import net.okitsu.ysmepicfightcompat.integration.tlm.TouhouMaidRenderBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import yesman.epicfight.api.asset.AssetAccessor;

/** Replaces EFTLM's maid mesh only for a ready, selected official-YSM model. */
@Pseudo
@Mixin(targets = "net.EFTLM.EF.Render.PatchedLivingMaidRenderer", remap = false)
public abstract class TouhouMaidRendererMixin {
    @Inject(
            method = "getMeshProvider(Lnet/EFTLM/EF/Capability/MaidPatch;)" +
                    "Lyesman/epicfight/api/asset/AssetAccessor;",
            at = @At("HEAD"), cancellable = true, require = 1, remap = false
    )
    private void ysmCompat$useOfficialYsmMaidMesh(
            @Coerce Object patch,
            CallbackInfoReturnable<AssetAccessor<?>> result) {
        AssetAccessor<?> converted = TouhouMaidRenderBridge.meshProvider(this, patch);
        if (converted != null) {
            result.setReturnValue(converted);
        }
    }
}
