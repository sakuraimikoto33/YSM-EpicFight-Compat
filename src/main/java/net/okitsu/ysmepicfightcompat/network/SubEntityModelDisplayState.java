package net.okitsu.ysmepicfightcompat.network;

import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

/** Server-validated source fingerprint paired with an owner-resolved display result. */
public record SubEntityModelDisplayState(
        int entityId,
        UUID entityUuid,
        UUID ownerUuid,
        long revision,
        SubEntityModelKind kind,
        ResourceLocation entityTypeId,
        boolean epicFightRendering,
        boolean known,
        boolean ysm) {
    public SubEntityModelDisplayState {
        if (entityId < 0 || entityUuid == null || ownerUuid == null
                || revision <= 0L || kind == null
                || entityTypeId == null) {
            throw new IllegalArgumentException("Invalid sub-entity model display state");
        }
        if (!known) {
            ysm = false;
        }
    }
}
