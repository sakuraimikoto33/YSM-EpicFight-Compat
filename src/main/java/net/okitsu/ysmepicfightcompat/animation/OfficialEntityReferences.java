package net.okitsu.ysmepicfightcompat.animation;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;

import java.lang.ref.WeakReference;

/** Official YSM's documented reference source, resolved only through Minecraft's public API. */
final class OfficialEntityReferences {
    private OfficialEntityReferences() { }

    /**
     * Players and other living models have no projectile owner. Projectile rendering
     * remains owned by official YSM; this resolver does not install a rendering hook.
     */
    static ExpressionEngine.EntityReference projectileOwner(Entity source) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!minecraft.isSameThread() || !(source instanceof Projectile projectile)
                || !(source.level() instanceof ClientLevel level) || minecraft.level != level
                || source.isRemoved() || !(projectile.getOwner() instanceof Player owner)
                || owner.level() != level || owner.isRemoved()
                || level.getEntity(owner.getId()) != owner) {
            return null;
        }
        WeakReference<ClientLevel> world = new WeakReference<>(level);
        return new WeakEntityReference<>(owner,
                target -> available(target, world),
                target -> new EntityReferenceEnvironment(EntityAnimationEnvironment.referenceView(
                        target, Minecraft.getInstance().getTimer().getGameTimeDeltaPartialTick(false))));
    }

    private static boolean available(Player target, WeakReference<ClientLevel> world) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = world.get();
        return minecraft.isSameThread() && level != null && minecraft.level == level
                && target.level() == level && !target.isRemoved()
                && level.getEntity(target.getId()) == target;
    }
}
