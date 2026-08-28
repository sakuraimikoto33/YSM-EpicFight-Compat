package net.okitsu.ysmepicfightcompat.render;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.entity.projectile.Projectile;
import net.okitsu.ysmepicfightcompat.network.RemoteSubEntityModelPreferences;
import net.okitsu.ysmepicfightcompat.network.SubEntityModelDisplayState;
import net.okitsu.ysmepicfightcompat.network.SubEntityModelKind;

/** Selects official YSM or the original entity renderer for one resolved owner. */
public final class SubEntityRenderPolicy {
    private SubEntityRenderPolicy() {
    }

    public static boolean suppressYsmProjectile(Projectile projectile) {
        return projectile != null
                && suppressLaunchEntity(projectile, projectile.getOwner(),
                SubEntityModelKind.PROJECTILE);
    }

    public static boolean suppressYsmFishingHook(FishingHook hook) {
        return hook != null
                && suppressLaunchEntity(hook, hook.getOwner(),
                SubEntityModelKind.FISHING_HOOK);
    }

    public static boolean suppressYsmVehicle(Entity vehicle) {
        return suppressVehicle(vehicle);
    }

    /**
     * Whether official YSM's passenger-locator pass must leave this rider's
     * PoseStack untouched. The mapped preview helper receives the rider, not the
     * vehicle rendered by the companion custom-vehicle helper.
     */
    public static boolean suppressYsmVehicleLocator(Entity rider) {
        if (rider == null || rider.getVehicle() == null) {
            return false;
        }
        return suppressVehicle(rider.getVehicle());
    }

    /** True only when the model-specific YSM vehicle path owns this rider. */
    public static boolean usesYsmVehicleForRider(Entity rider) {
        return !suppressYsmVehicleLocator(rider);
    }

    static boolean shouldSuppress(boolean battleMode, boolean known, boolean ysm) {
        return battleMode && (!known || !ysm);
    }

    static boolean shouldSuppressWithoutTrackedOwner(
            boolean snapshotPresent, boolean epicFightRendering,
            boolean known, boolean ysm) {
        return snapshotPresent && epicFightRendering && (!known || !ysm);
    }

    private static boolean suppressLaunchEntity(
            Entity target, Entity source, SubEntityModelKind kind) {
        if (source instanceof Player) {
            return suppress(target, source, kind);
        }
        SubEntityModelDisplayState state =
                RemoteSubEntityModelPreferences.findLaunchSnapshot(target, kind);
        return shouldSuppressWithoutTrackedOwner(
                state != null, state != null && state.epicFightRendering(),
                state != null && state.known(),
                state != null && state.ysm());
    }

    private static boolean suppress(Entity target, Entity source,
                                    SubEntityModelKind kind) {
        if (!(source instanceof Player player)) {
            return false;
        }
        RemoteSubEntityModelPreferences.Decision decision =
                RemoteSubEntityModelPreferences.resolve(target, kind);
        return shouldSuppress(EpicFightMode.active(player),
                decision.known(), decision.ysm());
    }

    private static boolean suppressVehicle(Entity vehicle) {
        if (vehicle == null) {
            return false;
        }
        Player source = sourcePassenger(vehicle);
        if (source != null) {
            return suppress(vehicle, source, SubEntityModelKind.VEHICLE);
        }
        SubEntityModelDisplayState state =
                RemoteSubEntityModelPreferences.findVehicleSnapshot(vehicle);
        return shouldSuppressWithoutTrackedOwner(
                state != null, state != null && state.epicFightRendering(),
                state != null && state.known(), state != null && state.ysm());
    }

    private static Player sourcePassenger(Entity vehicle) {
        if (vehicle == null) {
            return null;
        }
        for (Entity passenger : vehicle.getPassengers()) {
            if (passenger instanceof Player player) {
                return player;
            }
        }
        return null;
    }
}
