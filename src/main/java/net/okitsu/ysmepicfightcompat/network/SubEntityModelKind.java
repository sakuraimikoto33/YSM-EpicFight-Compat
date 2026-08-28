package net.okitsu.ysmepicfightcompat.network;

/** Compatibility-owned model families rendered on non-player entities. */
public enum SubEntityModelKind {
    PROJECTILE,
    FISHING_HOOK,
    VEHICLE;

    public static SubEntityModelKind fromNetworkId(int id) {
        SubEntityModelKind[] values = values();
        if (id < 0 || id >= values.length) {
            throw new IllegalArgumentException("Invalid sub-entity model kind: " + id);
        }
        return values[id];
    }
}
