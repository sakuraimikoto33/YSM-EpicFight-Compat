package net.okitsu.ysmepicfightcompat.network.geometry;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.okitsu.ysmepicfightcompat.CompatMod;
import net.okitsu.ysmepicfightcompat.assets.LocalModelRepository;
import net.okitsu.ysmepicfightcompat.assets.ModelBundle;
import net.okitsu.ysmepicfightcompat.cache.ModelDiskCache;
import net.okitsu.ysmepicfightcompat.cache.ServerModelDiskCache;
import net.okitsu.ysmepicfightcompat.network.CompatNetwork;
import net.okitsu.ysmepicfightcompat.network.PlayerSelectionNbt;
import net.okitsu.ysmepicfightcompat.network.message.ModelChunkMessage;

import java.io.IOException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** Builds each approved server model once and serves memory or persistent payload caches. */
public final class ServerModelTransfers {
    private static final int MAX_CACHE_ENTRIES = 8;
    private static final long MAX_CACHE_BYTES = 128L * 1024 * 1024;
    private static final ExecutorService ENCODERS = Executors.newFixedThreadPool(2, task -> {
        Thread worker = new Thread(task, "ysm-ef-model-sender");
        worker.setDaemon(true);
        return worker;
    });
    private static final LinkedHashMap<String, Cached> CACHE =
            new LinkedHashMap<>(16, 0.75F, true);
    private static final Map<String, PendingBatch> WAITERS = new LinkedHashMap<>();
    private static final AtomicInteger GENERATION = new AtomicInteger();
    private static long cachedBytes;

    private record Request(UUID playerId, byte[] knownPayloadDigest) {
        private Request {
            knownPayloadDigest = Arrays.copyOf(knownPayloadDigest, knownPayloadDigest.length);
        }
    }

    private record Cached(byte[] sourceDigest, byte[] payloadDigest, byte[] bytes) {
    }

    private record Prepared(byte[] payloadDigest, byte[] bytes) {
    }

    private record PendingBatch(int generation, Map<UUID, Request> requests) {
    }

    private ServerModelTransfers() {
    }

    public static void request(ServerPlayer recipient, String modelId,
                               byte[] knownPayloadDigest) {
        if (!CompatNetwork.isConnected(recipient) || modelId == null || modelId.isBlank()) {
            return;
        }
        MinecraftServer server = recipient.server;
        if (!selectedByOnlinePlayer(server, modelId) || !LocalModelRepository.exists(modelId)) {
            ServerModelDiskCache.remove(modelId);
            CompatNetwork.toPlayer(recipient, ModelChunkMessage.unavailable(modelId));
            return;
        }
        boolean start;
        int expectedGeneration = GENERATION.get();
        synchronized (WAITERS) {
            PendingBatch batch = WAITERS.get(modelId);
            if (batch == null || batch.generation() != expectedGeneration) {
                batch = new PendingBatch(expectedGeneration, new LinkedHashMap<>());
                WAITERS.put(modelId, batch);
                start = true;
            } else {
                start = false;
            }
            batch.requests().put(recipient.getUUID(),
                    new Request(recipient.getUUID(), knownPayloadDigest));
        }
        if (start) {
            ENCODERS.execute(() -> prepare(server, modelId, expectedGeneration));
        }
    }

    public static void clear() {
        GENERATION.incrementAndGet();
        synchronized (WAITERS) {
            WAITERS.clear();
        }
        synchronized (CACHE) {
            CACHE.clear();
            cachedBytes = 0;
        }
        ENCODERS.execute(ServerModelDiskCache::maintain);
    }

    private static void prepare(MinecraftServer server, String modelId,
                                int expectedGeneration) {
        Prepared prepared = null;
        try {
            prepared = encoded(modelId, expectedGeneration);
        } catch (Exception exception) {
            CompatMod.LOG.warn(
                    "YSM-EF Compat: failed to prepare server model '{}'", modelId, exception);
        }
        Prepared completed = prepared;
        if (expectedGeneration != GENERATION.get()) {
            discardWaiters(modelId, expectedGeneration);
            return;
        }
        server.execute(() -> deliver(server, modelId, completed, expectedGeneration));
    }

    private static void deliver(MinecraftServer server, String modelId, Prepared prepared,
                                int expectedGeneration) {
        List<Request> requests;
        if (expectedGeneration != GENERATION.get()) {
            return;
        }
        synchronized (WAITERS) {
            PendingBatch batch = WAITERS.get(modelId);
            if (batch == null || batch.generation() != expectedGeneration) {
                return;
            }
            WAITERS.remove(modelId);
            requests = List.copyOf(batch.requests().values());
        }
        if (expectedGeneration != GENERATION.get()) {
            return;
        }
        for (Request request : requests) {
            ServerPlayer player = server.getPlayerList().getPlayer(request.playerId());
            if (!CompatNetwork.isConnected(player)) {
                continue;
            }
            if (prepared == null || !selectedByOnlinePlayer(server, modelId)) {
                CompatNetwork.toPlayer(player, ModelChunkMessage.unavailable(modelId));
            } else if (request.knownPayloadDigest().length == ModelDiskCache.DIGEST_BYTES
                    && MessageDigest.isEqual(request.knownPayloadDigest(),
                    prepared.payloadDigest())) {
                CompatNetwork.toPlayer(player,
                        ModelChunkMessage.unchanged(modelId, prepared.payloadDigest()));
            } else {
                sendChunks(player, modelId, prepared);
            }
        }
    }

    private static Prepared encoded(String modelId, int expectedGeneration) throws IOException {
        byte[] sourceDigest = LocalModelRepository.contentDigest(modelId);
        if (sourceDigest == null) {
            ServerModelDiskCache.remove(modelId);
            return null;
        }
        synchronized (CACHE) {
            Cached known = CACHE.get(modelId);
            if (known != null && MessageDigest.isEqual(
                    sourceDigest, known.sourceDigest())) {
                return new Prepared(known.payloadDigest(), known.bytes());
            }
        }
        Optional<ModelDiskCache.Entry> disk = ServerModelDiskCache.read(modelId);
        byte[] payload;
        byte[] payloadDigest;
        if (disk.isPresent() && MessageDigest.isEqual(
                sourceDigest, disk.get().validationDigest())) {
            payload = disk.get().payload();
            payloadDigest = disk.get().payloadDigest();
        } else {
            ModelBundle model = LocalModelRepository.load(modelId);
            if (model == null) {
                ServerModelDiskCache.remove(modelId);
                return null;
            }
            payload = GeometryTransferCodec.encode(model);
            payloadDigest = ModelDiskCache.sha256(payload);
            ServerModelDiskCache.write(modelId, sourceDigest, payloadDigest, payload);
        }
        synchronized (CACHE) {
            if (expectedGeneration != GENERATION.get()) {
                return new Prepared(payloadDigest, payload);
            }
            Cached previous = CACHE.put(modelId,
                    new Cached(sourceDigest, payloadDigest, payload));
            if (previous != null) {
                cachedBytes -= previous.bytes().length;
            }
            cachedBytes += payload.length;
            while (CACHE.size() > MAX_CACHE_ENTRIES || cachedBytes > MAX_CACHE_BYTES) {
                String oldest = CACHE.keySet().iterator().next();
                cachedBytes -= CACHE.remove(oldest).bytes().length;
            }
        }
        return new Prepared(payloadDigest, payload);
    }

    private static boolean selectedByOnlinePlayer(MinecraftServer server, String modelId) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            PlayerSelectionNbt.Selection selection = PlayerSelectionNbt.read(player);
            if (selection != null && modelId.equals(selection.modelId())) {
                return true;
            }
        }
        return false;
    }

    private static void sendChunks(ServerPlayer recipient, String modelId, Prepared prepared) {
        byte[] payload = prepared.bytes();
        UUID transfer = UUID.randomUUID();
        int chunkCount = (payload.length + ModelChunkMessage.CHUNK_BYTES - 1)
                / ModelChunkMessage.CHUNK_BYTES;
        for (int index = 0; index < chunkCount; index++) {
            int offset = index * ModelChunkMessage.CHUNK_BYTES;
            int length = Math.min(ModelChunkMessage.CHUNK_BYTES, payload.length - offset);
            CompatNetwork.toPlayer(recipient, new ModelChunkMessage(
                    ModelChunkMessage.Status.DATA, transfer, modelId,
                    prepared.payloadDigest(), payload.length, index, chunkCount,
                    Arrays.copyOfRange(payload, offset, offset + length)));
        }
        CompatMod.LOG.info(
                "YSM-EF Compat: streamed '{}' to '{}' in {} chunks",
                modelId, recipient.getGameProfile().getName(), chunkCount);
    }

    private static void discardWaiters(String modelId, int expectedGeneration) {
        synchronized (WAITERS) {
            PendingBatch batch = WAITERS.get(modelId);
            if (batch != null && batch.generation() == expectedGeneration) {
                WAITERS.remove(modelId);
            }
        }
    }
}
