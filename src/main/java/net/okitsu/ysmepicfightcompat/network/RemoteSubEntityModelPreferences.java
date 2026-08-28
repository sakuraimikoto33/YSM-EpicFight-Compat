package net.okitsu.ysmepicfightcompat.network;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;

import java.util.LinkedHashMap;
import java.util.UUID;

/** Client snapshots keyed by sub-entity UUID and guarded by its live fingerprint. */
public final class RemoteSubEntityModelPreferences {
    static final int MAX_ENTRIES = 4096;

    public record Decision(boolean known, boolean ysm) {
        public static final Decision UNKNOWN = new Decision(false, false);

        public Decision {
            if (!known) {
                ysm = false;
            }
        }
    }

    private static final LinkedHashMap<UUID, SubEntityModelDisplayState> CURRENT =
            new LinkedHashMap<>(16, 0.75F, true);

    private RemoteSubEntityModelPreferences() {
    }

    public static synchronized Decision resolve(Entity entity, SubEntityModelKind kind) {
        if (entity == null || kind == null) {
            return Decision.UNKNOWN;
        }
        SubEntityModelDisplayState state = CURRENT.get(entity.getUUID());
        UUID ownerUuid = ownerUuid(entity, kind);
        return resolve(state, entity.getId(), entity.getUUID(), ownerUuid,
                BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()), kind);
    }

    /**
     * Finds a server-authoritative launch snapshot when the projectile owner is not
     * present in the client level and therefore cannot participate in live matching.
     */
    public static synchronized SubEntityModelDisplayState findLaunchSnapshot(
            Entity entity, SubEntityModelKind kind) {
        if (entity == null || kind == null
                || kind == SubEntityModelKind.VEHICLE) {
            return null;
        }
        return launchSnapshot(CURRENT.get(entity.getUUID()), entity.getId(),
                entity.getUUID(), BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()),
                kind);
    }

    /**
     * Finds the last server-authoritative vehicle decision after its player
     * passenger is no longer available for live owner matching.
     */
    public static synchronized SubEntityModelDisplayState findVehicleSnapshot(
            Entity vehicle) {
        if (vehicle == null) {
            return null;
        }
        return vehicleSnapshot(CURRENT.get(vehicle.getUUID()), vehicle.getId(),
                vehicle.getUUID(),
                BuiltInRegistries.ENTITY_TYPE.getKey(vehicle.getType()));
    }

    static Decision resolve(SubEntityModelDisplayState state,
                            int entityId, UUID entityUuid, UUID ownerUuid,
                            ResourceLocation entityTypeId,
                            SubEntityModelKind kind) {
        if (state == null || entityUuid == null || ownerUuid == null
                || entityTypeId == null || kind == null
                || state.entityId() != entityId
                || !state.entityUuid().equals(entityUuid)
                || state.kind() != kind
                || !state.ownerUuid().equals(ownerUuid)
                || !state.entityTypeId().equals(entityTypeId)) {
            return Decision.UNKNOWN;
        }
        return new Decision(state.known(), state.ysm());
    }

    static SubEntityModelDisplayState launchSnapshot(
            SubEntityModelDisplayState state, int entityId, UUID entityUuid,
            ResourceLocation entityTypeId, SubEntityModelKind kind) {
        return state != null && entityUuid != null && entityTypeId != null
                && kind != null && kind != SubEntityModelKind.VEHICLE
                && state.entityId() == entityId
                && state.entityUuid().equals(entityUuid)
                && state.kind() == kind
                && state.entityTypeId().equals(entityTypeId) ? state : null;
    }

    static SubEntityModelDisplayState vehicleSnapshot(
            SubEntityModelDisplayState state, int entityId, UUID entityUuid,
            ResourceLocation entityTypeId) {
        return state != null && entityUuid != null && entityTypeId != null
                && state.entityId() == entityId
                && state.entityUuid().equals(entityUuid)
                && state.kind() == SubEntityModelKind.VEHICLE
                && state.entityTypeId().equals(entityTypeId) ? state : null;
    }

    public static synchronized SubEntityModelDisplayState find(UUID entityUuid) {
        return entityUuid == null ? null : CURRENT.get(entityUuid);
    }

    public static synchronized void accept(SubEntityModelDisplayState state) {
        if (state == null) {
            return;
        }
        SubEntityModelDisplayState previous = CURRENT.get(state.entityUuid());
        if (previous != null && state.revision() <= previous.revision()) {
            return;
        }
        CURRENT.put(state.entityUuid(), state);
        while (CURRENT.size() > MAX_ENTRIES) {
            CURRENT.remove(CURRENT.keySet().iterator().next());
        }
    }

    public static synchronized void remove(UUID entityUuid) {
        if (entityUuid == null) {
            return;
        }
        CURRENT.remove(entityUuid);
    }

    public static synchronized void beginConnection() {
        CURRENT.clear();
    }

    private static UUID ownerUuid(Entity entity, SubEntityModelKind kind) {
        if (kind == SubEntityModelKind.PROJECTILE
                || kind == SubEntityModelKind.FISHING_HOOK) {
            return entity instanceof Projectile projectile
                    && projectile.getOwner() instanceof Player player
                    ? player.getUUID() : null;
        }
        for (Entity passenger : entity.getPassengers()) {
            if (passenger instanceof Player player) {
                return player.getUUID();
            }
        }
        return null;
    }
}
