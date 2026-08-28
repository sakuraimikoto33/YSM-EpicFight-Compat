package net.okitsu.ysmepicfightcompat.render;

import net.minecraft.world.entity.player.Player;
import yesman.epicfight.world.capabilities.EpicFightCapabilities;
import yesman.epicfight.world.capabilities.entitypatch.LivingEntityPatch;
import yesman.epicfight.world.capabilities.entitypatch.player.PlayerPatch;

/** Shared battle-mode query without coupling unrelated policies to overlay settings. */
public final class EpicFightMode {
    private EpicFightMode() {
    }

    public static boolean active(Player player) {
        if (player == null) {
            return false;
        }
        LivingEntityPatch<?> patch = EpicFightCapabilities.getEntityPatch(
                player, LivingEntityPatch.class);
        return patch instanceof PlayerPatch<?> playerPatch
                && playerPatch.isEpicFightMode();
    }
}
