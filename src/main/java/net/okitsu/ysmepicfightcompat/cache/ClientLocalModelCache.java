package net.okitsu.ysmepicfightcompat.cache;

import net.okitsu.ysmepicfightcompat.CompatMod;
import net.okitsu.ysmepicfightcompat.assets.LocalModelRepository;
import net.okitsu.ysmepicfightcompat.assets.ModelBundle;
import net.okitsu.ysmepicfightcompat.config.ClientPreferences;
import net.okitsu.ysmepicfightcompat.network.geometry.GeometryTransferCodec;

import java.io.IOException;
import java.security.MessageDigest;
import java.util.Optional;

/** Persistent parsed-model fallback for models installed on the client. */
public final class ClientLocalModelCache {
    private static final String KEY_PREFIX = "client\0";

    private ClientLocalModelCache() {
    }

    public static void maintain() {
        ModelDiskCache.maintain(CompatCachePaths.client(), ModelDiskCache.mebibytes(
                ClientPreferences.CLIENT_MODEL_DISK_CACHE_MIB.get()));
    }

    public static ModelBundle load(String modelId) {
        long maximumBytes = ModelDiskCache.mebibytes(
                ClientPreferences.CLIENT_MODEL_DISK_CACHE_MIB.get());
        if (maximumBytes <= 0) {
            ModelDiskCache.maintain(CompatCachePaths.client(), 0);
            return LocalModelRepository.load(modelId);
        }
        byte[] sourceDigest = LocalModelRepository.contentDigest(modelId);
        if (sourceDigest == null) {
            ModelDiskCache.maintain(CompatCachePaths.client(), maximumBytes);
            return LocalModelRepository.load(modelId);
        }
        Optional<ModelDiskCache.Entry> cached = ModelDiskCache.read(
                CompatCachePaths.client(), KEY_PREFIX + modelId, maximumBytes);
        if (cached.isPresent() && MessageDigest.isEqual(
                sourceDigest, cached.get().validationDigest())) {
            try {
                return GeometryTransferCodec.decode(modelId, cached.get().payload());
            } catch (IOException exception) {
                ModelDiskCache.remove(CompatCachePaths.client(), KEY_PREFIX + modelId);
            }
        } else if (cached.isPresent()) {
            ModelDiskCache.remove(CompatCachePaths.client(), KEY_PREFIX + modelId);
        }
        ModelBundle model = LocalModelRepository.load(modelId);
        if (model == null || maximumBytes <= 0) {
            if (model == null) {
                ModelDiskCache.remove(CompatCachePaths.client(), KEY_PREFIX + modelId);
            }
            return model;
        }
        try {
            byte[] payload = GeometryTransferCodec.encode(model);
            byte[] payloadDigest = ModelDiskCache.sha256(payload);
            ModelDiskCache.write(CompatCachePaths.client(), KEY_PREFIX + modelId,
                    new ModelDiskCache.Entry(sourceDigest, payloadDigest, payload), maximumBytes);
        } catch (IOException | RuntimeException exception) {
            CompatMod.LOG.debug(
                    "YSM-EF Compat: local model '{}' was not persisted", modelId, exception);
        }
        return model;
    }
}
