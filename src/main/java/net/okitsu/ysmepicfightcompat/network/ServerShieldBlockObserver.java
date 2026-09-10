package net.okitsu.ysmepicfightcompat.network;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.event.entity.living.LivingShieldBlockEvent;
import net.okitsu.ysmepicfightcompat.network.message.ShieldBlockMessage;

/** Observes the final NeoForge result without changing damage, shield use or Epic Fight guards. */
public final class ServerShieldBlockObserver {
    private ServerShieldBlockObserver() {
    }

    public static void afterShieldBlock(LivingEntity entity, LivingShieldBlockEvent result) {
        if (entity == null || entity.level().isClientSide() || entity.isRemoved()
                || entity.getId() < 0 || result == null
                || result.getEntity() != entity
                || !wasSuccessful(result.isCanceled(), result.getBlocked(), result.getBlockedDamage())) {
            return;
        }
        ShieldBlockMessage message = new ShieldBlockMessage(entity.getId(), entity.getUUID());
        if (entity instanceof Player player) {
            CompatNetwork.toTrackersAndSelf(player, message);
        } else {
            CompatNetwork.toTrackers(entity, message);
        }
    }

    static boolean wasSuccessful(boolean canceled, boolean blocked, float blockedDamage) {
        return !canceled && blocked && Float.isFinite(blockedDamage) && blockedDamage > 0.0F;
    }
}
