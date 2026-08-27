package net.okitsu.ysmepicfightcompat.network.geometry;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.okitsu.ysmepicfightcompat.CompatMod;
import net.okitsu.ysmepicfightcompat.assets.LocalModelRepository;
import net.okitsu.ysmepicfightcompat.assets.ModelBundle;
import net.okitsu.ysmepicfightcompat.cache.ModelDiskCache;
import net.okitsu.ysmepicfightcompat.cache.ServerModelDiskCache;
import net.okitsu.ysmepicfightcompat.integration.tlm.TouhouMaidSelectionAccess;
import net.okitsu.ysmepicfightcompat.network.CompatNetwork;
import net.okitsu.ysmepicfightcompat.network.PlayerSelectionNbt;
import net.okitsu.ysmepicfightcompat.network.message.ModelChunkMessage;

import java.io.IOException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** Builds each approved server model once and serves memory or persistent payload caches. */
public final class ServerModelTransfers {
    private static final int MAX_CACHE_ENTRIES = 8;
    private static final long MAX_CACHE_BYTES = 128L * 1024 * 1024;
    private static final int MAX_PENDING_MODELS = 32;
    private static final int MAX_PENDING_PER_RECIPIENT = 2;
    private static final long RATE_WINDOW_NANOS = 10_000_000_000L;
    private static final long MAX_DATA_BYTES_PER_WINDOW = 128L * 1024 * 1024;
    private static final ModelTransferRateLimiter RATE_LIMITER =
            new ModelTransferRateLimiter(RATE_WINDOW_NANOS, 16L,
                    MAX_DATA_BYTES_PER_WINDOW);
    private static final ExecutorService ENCODERS = Executors.newFixedThreadPool(2, task -> {
        Thread worker = new Thread(task, "ysm-ef-model-sender");
        worker.setDaemon(true);
        return worker;
    });
    private static final LinkedHashMap<String, Cached> CACHE =
            new LinkedHashMap<>(16, 0.75F, true);
    private static final Map<String, PendingBatch> WAITERS = new LinkedHashMap<>();
    private static final Map<UUID, Integer> PENDING_BY_RECIPIENT =
            new LinkedHashMap<>();
    private static final Map<UUID, Set<UUID>> TRACKED_ENTITIES =
            new LinkedHashMap<>();
    private static final AtomicInteger GENERATION = new AtomicInteger();
    private static long cachedBytes;

    private record Request(UUID recipientUuid, int sourceEntityId,
                           UUID sourceEntityUuid, byte[] knownPayloadDigest) {
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
                               int sourceEntityId, UUID sourceEntityUuid,
                               byte[] knownPayloadDigest) {
        if (!CompatNetwork.isConnected(recipient) || modelId == null || modelId.isBlank()
                || sourceEntityId < 0 || sourceEntityUuid == null
                || knownPayloadDigest == null
                || knownPayloadDigest.length != 0
                && knownPayloadDigest.length != ModelDiskCache.DIGEST_BYTES) {
            return;
        }
        MinecraftServer server = recipient.server;
        if (!RATE_LIMITER.allowRequest(recipient.getUUID(), System.nanoTime())) {
            return;
        }
        if (!authorizedSelection(recipient, sourceEntityId, sourceEntityUuid, modelId)) {
            return;
        }
        if (!LocalModelRepository.exists(modelId)) {
            ServerModelDiskCache.remove(modelId);
            CompatNetwork.toPlayer(recipient, ModelChunkMessage.unavailable(modelId));
            return;
        }
        boolean start;
        boolean accepted;
        int expectedGeneration = GENERATION.get();
        synchronized (WAITERS) {
            PendingBatch batch = WAITERS.get(modelId);
            if (batch != null && batch.generation() != expectedGeneration) {
                removeBatch(modelId, batch);
                batch = null;
            }
            UUID recipientUuid = recipient.getUUID();
            boolean replacing = batch != null
                    && batch.requests().containsKey(recipientUuid);
            accepted = replacing || (PENDING_BY_RECIPIENT.getOrDefault(
                    recipientUuid, 0) < MAX_PENDING_PER_RECIPIENT
                    && (batch != null || WAITERS.size() < MAX_PENDING_MODELS));
            if (!accepted) {
                start = false;
            } else if (batch == null) {
                batch = new PendingBatch(expectedGeneration, new LinkedHashMap<>());
                WAITERS.put(modelId, batch);
                start = true;
            } else {
                start = false;
            }
            if (accepted) {
                if (!replacing) {
                    PENDING_BY_RECIPIENT.merge(recipientUuid, 1, Integer::sum);
                }
                batch.requests().put(recipientUuid,
                        new Request(recipientUuid, sourceEntityId,
                                sourceEntityUuid, knownPayloadDigest));
            }
        }
        if (accepted && start) {
            ENCODERS.execute(() -> prepare(server, modelId, expectedGeneration));
        }
    }

    /** Records the exact server tracking relation used to authorize model transfer. */
    public static void startedTracking(ServerPlayer recipient, Entity target) {
        if (recipient == null || target == null) {
            return;
        }
        synchronized (WAITERS) {
            TRACKED_ENTITIES.computeIfAbsent(recipient.getUUID(), ignored ->
                    new LinkedHashSet<>()).add(target.getUUID());
        }
    }

    public static void stoppedTracking(ServerPlayer recipient, Entity target) {
        if (recipient == null || target == null) {
            return;
        }
        synchronized (WAITERS) {
            Set<UUID> tracked = TRACKED_ENTITIES.get(recipient.getUUID());
            if (tracked != null) {
                tracked.remove(target.getUUID());
                if (tracked.isEmpty()) {
                    TRACKED_ENTITIES.remove(recipient.getUUID());
                }
            }
        }
    }

    public static void playerDisconnected(ServerPlayer player) {
        if (player == null) {
            return;
        }
        UUID playerUuid = player.getUUID();
        synchronized (WAITERS) {
            TRACKED_ENTITIES.remove(playerUuid);
            TRACKED_ENTITIES.values().forEach(tracked -> tracked.remove(playerUuid));
            WAITERS.values().forEach(batch ->
                    batch.requests().remove(playerUuid));
            PENDING_BY_RECIPIENT.remove(playerUuid);
        }
        RATE_LIMITER.remove(playerUuid);
    }

    public static void clear() {
        GENERATION.incrementAndGet();
        synchronized (WAITERS) {
            WAITERS.clear();
            PENDING_BY_RECIPIENT.clear();
            TRACKED_ENTITIES.clear();
        }
        RATE_LIMITER.clear();
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
            removeBatch(modelId, batch);
            requests = List.copyOf(batch.requests().values());
        }
        if (expectedGeneration != GENERATION.get()) {
            return;
        }
        for (Request request : requests) {
            ServerPlayer player = server.getPlayerList().getPlayer(request.recipientUuid());
            if (!CompatNetwork.isConnected(player)) {
                continue;
            }
            if (!authorizedSelection(player, request.sourceEntityId(),
                    request.sourceEntityUuid(), modelId)) {
                continue;
            }
            if (prepared == null) {
                CompatNetwork.toPlayer(player, ModelChunkMessage.unavailable(modelId));
            } else if (request.knownPayloadDigest().length == ModelDiskCache.DIGEST_BYTES
                    && MessageDigest.isEqual(request.knownPayloadDigest(),
                    prepared.payloadDigest())) {
                CompatNetwork.toPlayer(player,
                        ModelChunkMessage.unchanged(modelId, prepared.payloadDigest()));
            } else {
                if (RATE_LIMITER.allowData(player.getUUID(), System.nanoTime(),
                        prepared.bytes().length)) {
                    sendChunks(player, modelId, prepared);
                }
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

    private static boolean authorizedSelection(
            ServerPlayer recipient, int sourceEntityId,
            UUID sourceEntityUuid, String modelId) {
        Entity source = recipient.serverLevel().getEntity(sourceEntityId);
        if (source == null || source.isRemoved()
                || !sourceEntityUuid.equals(source.getUUID())) {
            return false;
        }
        boolean self = source == recipient;
        boolean tracked = self;
        if (source != recipient) {
            synchronized (WAITERS) {
                Set<UUID> trackedEntities =
                        TRACKED_ENTITIES.get(recipient.getUUID());
                tracked = trackedEntities != null
                        && trackedEntities.contains(sourceEntityUuid);
            }
        }
        String selectedModelId;
        if (source instanceof ServerPlayer player) {
            PlayerSelectionNbt.Selection selection = PlayerSelectionNbt.read(player);
            selectedModelId = selection == null ? null : selection.modelId();
        } else if (TouhouMaidSelectionAccess.integrationLoaded()) {
            TouhouMaidSelectionAccess.Selection selection =
                    TouhouMaidSelectionAccess.resolve(source);
            selectedModelId = selection == null ? null : selection.modelId();
        } else {
            selectedModelId = null;
        }
        return ModelTransferAuthorization.permits(
                sourceEntityId, sourceEntityUuid,
                source.getId(), source.getUUID(), self, tracked,
                modelId, selectedModelId);
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
                removeBatch(modelId, batch);
            }
        }
    }

    /** Must be called while holding {@link #WAITERS}. */
    private static void removeBatch(String modelId, PendingBatch batch) {
        if (!WAITERS.remove(modelId, batch)) {
            return;
        }
        for (UUID recipientUuid : batch.requests().keySet()) {
            int remaining = PENDING_BY_RECIPIENT.getOrDefault(recipientUuid, 0) - 1;
            if (remaining <= 0) {
                PENDING_BY_RECIPIENT.remove(recipientUuid);
            } else {
                PENDING_BY_RECIPIENT.put(recipientUuid, remaining);
            }
        }
    }
}
