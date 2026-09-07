package net.okitsu.ysmepicfightcompat.animation;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.okitsu.ysmepicfightcompat.integration.parcool.EpicParCoolAnimationAccess;
import net.okitsu.ysmepicfightcompat.integration.parcool.ParCoolAnimationStateAccess;
import net.okitsu.ysmepicfightcompat.integration.swem.SwemAnimationAccess;
import net.okitsu.ysmepicfightcompat.render.EpicFightPoseOwnership;

import javax.annotation.Nullable;

/** Optional integrations only read native visual state; no gameplay/armature mutation. */
public final class ModAnimationResolver {
    private ModAnimationResolver() { }

    public static void clear() {
        ParCoolAnimationStateAccess.clear();
        SwemAnimationAccess.clear();
    }

    @Nullable
    public static ModAnimationType resolveType(LivingEntity entity) {
        ModAnimationSample sample = sample(entity, 0.0F);
        return sample == null ? null : sample.type();
    }

    @Nullable
    public static ModAnimationSample sample(LivingEntity entity, float partialTick) {
        if (!(entity instanceof Player) || entity.isDeadOrDying() || entity.hurtTime > 0
                || entity.isSleeping() || entity.isAutoSpinAttack()) return null;
        if (EpicParCoolAnimationAccess.ownsMovementPose(entity)
                || EpicFightPoseOwnership.combatActionOwnsPose(entity)) return null;
        // Riding takes precedence over stale parkour state after mounting.
        var riding = SwemAnimationAccess.sample(entity, partialTick);
        if (riding != null) {
            return new ModAnimationSample(ModAnimationType.SWEM, riding.clipName(),
                    riding.elapsedSeconds(), riding.restartToken());
        }
        if (entity.isPassenger()) return null;
        var parkour = ParCoolAnimationStateAccess.sample(entity, partialTick);
        return parkour == null ? null : new ModAnimationSample(
                ModAnimationType.PARCOOL, parkour.clipName(),
                parkour.elapsedSeconds(), parkour.generation());
    }
}
