package net.okitsu.ysmepicfightcompat.mixin;

import net.minecraft.world.entity.player.Player;
import net.okitsu.ysmepicfightcompat.animation.OfficialRoamingVariables;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Associates each player with official YSM's live roaming-state holder. */
@Mixin(targets = "net.okitsu.ysmepicfightcompat.ysmref.PlayerStateCapabilityAlias",
        remap = false)
public abstract class PlayerStateCapabilityMixin {
    @Inject(
            method = "<init>(Lnet/minecraft/world/entity/player/Player;)V",
            at = @At("RETURN"),
            remap = false
    )
    private void ysmCompat$capturePlayer(Player player, CallbackInfo callback) {
        OfficialRoamingVariables.register(player, this);
    }
}
