package net.okitsu.ysmepicfightcompat.cache;

import net.okitsu.ysmepicfightcompat.config.ServerPreferences;

import java.util.Optional;

/** Persistent server-side store for generated transfer payloads. */
public final class ServerModelDiskCache {
    private static final String KEY_PREFIX = "server\0";

    private ServerModelDiskCache() {
    }

    public static Optional<ModelDiskCache.Entry> read(String modelId) {
        if (!ServerPreferences.SERVER_MODEL_DISK_CACHE_ENABLED.get()) {
            return Optional.empty();
        }
        return ModelDiskCache.read(CompatCachePaths.server(), KEY_PREFIX + modelId,
                maximumBytes());
    }

    public static boolean write(String modelId, byte[] sourceDigest,
                                byte[] payloadDigest, byte[] payload) {
        if (!ServerPreferences.SERVER_MODEL_DISK_CACHE_ENABLED.get()) {
            return false;
        }
        return ModelDiskCache.write(CompatCachePaths.server(), KEY_PREFIX + modelId,
                new ModelDiskCache.Entry(sourceDigest, payloadDigest, payload), maximumBytes());
    }

    public static void remove(String modelId) {
        ModelDiskCache.remove(CompatCachePaths.server(), KEY_PREFIX + modelId);
    }

    public static void maintain() {
        if (ServerPreferences.SERVER_MODEL_DISK_CACHE_ENABLED.get()) {
            ModelDiskCache.maintain(CompatCachePaths.server(), maximumBytes());
        }
    }

    private static long maximumBytes() {
        return ModelDiskCache.mebibytes(ServerPreferences.SERVER_MODEL_DISK_CACHE_MIB.get());
    }
}
