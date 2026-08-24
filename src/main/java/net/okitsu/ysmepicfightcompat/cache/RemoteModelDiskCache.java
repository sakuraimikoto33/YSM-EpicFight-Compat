package net.okitsu.ysmepicfightcompat.cache;

import net.okitsu.ysmepicfightcompat.config.ClientPreferences;

import java.util.Optional;

/** Server-scoped persistent cache for received model payloads. */
public final class RemoteModelDiskCache {
    private static final String KEY_PREFIX = "remote\0";

    private RemoteModelDiskCache() {
    }

    public static Optional<ModelDiskCache.Entry> read(String serverIdentity, String modelId) {
        return ModelDiskCache.read(CompatCachePaths.remote(),
                key(serverIdentity, modelId), maximumBytes());
    }

    public static boolean write(String serverIdentity, String modelId,
                                byte[] payloadDigest, byte[] payload) {
        return ModelDiskCache.write(CompatCachePaths.remote(), key(serverIdentity, modelId),
                new ModelDiskCache.Entry(payloadDigest, payloadDigest, payload), maximumBytes());
    }

    public static void remove(String serverIdentity, String modelId) {
        ModelDiskCache.remove(CompatCachePaths.remote(), key(serverIdentity, modelId));
    }

    public static void maintain() {
        ModelDiskCache.maintain(CompatCachePaths.remote(), maximumBytes());
    }

    private static String key(String serverIdentity, String modelId) {
        return KEY_PREFIX + serverIdentity + '\0' + modelId;
    }

    private static long maximumBytes() {
        return ModelDiskCache.mebibytes(ClientPreferences.REMOTE_MODEL_DISK_CACHE_MIB.get());
    }
}
