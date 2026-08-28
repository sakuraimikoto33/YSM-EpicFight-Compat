package net.okitsu.ysmepicfightcompat.render;

import net.minecraft.world.entity.player.Player;
import net.okitsu.ysmepicfightcompat.config.ClientPreferences;

/** Decides whether official YSM's extra player overlay may render this frame. */
public final class CombatOverlayPolicy {
    private CombatOverlayPolicy() {
    }

    public static boolean shouldSuppress(Player player) {
        if (player == null) {
            return false;
        }
        return shouldSuppress(EpicFightMode.active(player));
    }

    static boolean shouldSuppress(boolean battleMode) {
        return ClientPreferences.suppressBattleModeOverlay() && battleMode;
    }
}
