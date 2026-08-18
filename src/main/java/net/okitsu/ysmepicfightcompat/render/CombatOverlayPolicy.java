package net.okitsu.ysmepicfightcompat.render;

import net.minecraft.world.entity.player.Player;
import net.okitsu.ysmepicfightcompat.config.ClientPreferences;
import yesman.epicfight.world.capabilities.EpicFightCapabilities;
import yesman.epicfight.world.capabilities.entitypatch.LivingEntityPatch;
import yesman.epicfight.world.capabilities.entitypatch.player.PlayerPatch;

/** Decides whether official YSM's extra player overlay may render this frame. */
public final class CombatOverlayPolicy {
    private CombatOverlayPolicy() {
    }

    public static boolean shouldSuppress(Player player) {
        if (player == null) {
            return false;
        }
        LivingEntityPatch<?> patch = EpicFightCapabilities.getEntityPatch(
                player, LivingEntityPatch.class);
        return shouldSuppress(patch instanceof PlayerPatch<?> playerPatch
                && playerPatch.isEpicFightMode());
    }

    static boolean shouldSuppress(boolean battleMode) {
        return ClientPreferences.suppressBattleModeOverlay() && battleMode;
    }
}
