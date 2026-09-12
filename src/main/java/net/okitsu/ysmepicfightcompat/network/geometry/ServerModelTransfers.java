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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
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
    private static final ExecutorService ENCODERS = new ThreadPoolExecutor(2, 2,
            0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(MAX_PENDING_MODELS * 2), task -> {
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
    private static final int CHUNKS_PER_TICK = 16;
    private static final ModelDeliveryQueue<DeliveryRequest, CompatNetwork.PreparedModelPacket>
            DELIVERIES = new ModelDeliveryQueue<>(MAX_PENDING_MODELS, ModelChunkPreparation.MAX_RETAINED_BYTES);
    private static long cachedBytes;

    private record Request(UUID recipientUuid, int sourceEntityId,
                           UUID sourceEntityUuid, byte[] knownPayloadDigest,
                           Object recipientConnection) {
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

    private record DeliveryRequest(MinecraftServer server, String modelId,
                                   Request request, int generation) {
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
                                sourceEntityUuid, knownPayloadDigest, recipient.connection));
            }
        }
        if (accepted && start) {
            try {
                ENCODERS.execute(() -> prepare(server, modelId, expectedGeneration));
            } catch (RejectedExecutionException busy) {
                discardWaiters(modelId, expectedGeneration);
            }
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
        DELIVERIES.clear();
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
        try {
            ENCODERS.execute(ServerModelDiskCache::maintain);
        } catch (RejectedExecutionException busy) {
            // Persistent-cache maintenance can wait for the next normal cache access.
        }
    }

    private static void prepare(MinecraftServer server, String modelId,
                                int expectedGeneration) {
        if (expectedGeneration != GENERATION.get()) {
            discardWaiters(modelId, expectedGeneration);
            return;
        }
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
        try {
            CompatNetwork.PreparedModelPacket status = CompatNetwork.prepareModelChunk(
                    completed == null ? ModelChunkMessage.unavailable(modelId)
                            : ModelChunkMessage.unchanged(modelId, completed.payloadDigest()));
            server.execute(() -> deliver(server, modelId, completed, status, expectedGeneration));
        } catch (RuntimeException exception) {
            discardWaiters(modelId, expectedGeneration);
            CompatMod.LOG.warn("YSM-EF Compat: failed to encode model status '{}'", modelId, exception);
        }
    }

    private static void deliver(MinecraftServer server, String modelId, Prepared prepared,
                                CompatNetwork.PreparedModelPacket status,
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
        List<Request> dataRequests = new ArrayList<>();
        for (Request request : requests) {
            ServerPlayer player = server.getPlayerList().getPlayer(request.recipientUuid());
            if (!CompatNetwork.isConnected(player) || player.connection != request.recipientConnection()) {
                continue;
            }
            if (!authorizedSelection(player, request.sourceEntityId(),
                    request.sourceEntityUuid(), modelId)) {
                continue;
            }
            if (prepared == null) {
                CompatNetwork.sendPreparedModelChunk(player, status);
            } else if (request.knownPayloadDigest().length == ModelDiskCache.DIGEST_BYTES
                    && MessageDigest.isEqual(request.knownPayloadDigest(),
                    prepared.payloadDigest())) {
                CompatNetwork.sendPreparedModelChunk(player, status);
            } else {
                dataRequests.add(request);
            }
        }
        if (prepared != null && !dataRequests.isEmpty()) {
            prepareDelivery(server, modelId, prepared, dataRequests, expectedGeneration);
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

    private static void prepareDelivery(MinecraftServer server, String modelId, Prepared prepared,
                                        List<Request> requests, int generation) {
        // Charge the source plus one wire representation before allocating any chunk copies.
        ModelDeliveryQueue.Reservation reservation = DELIVERIES.reserve(
                ModelChunkPreparation.retainedBytes(modelId.length(), prepared.bytes().length));
        if (reservation == null) {
            return;
        }
        List<DeliveryRequest> recipients = requests.stream()
                .filter(request -> RATE_LIMITER.allowData(request.recipientUuid(), System.nanoTime(),
                        prepared.bytes().length))
                .map(request -> new DeliveryRequest(server, modelId, request, generation))
                .toList();
        if (recipients.isEmpty()) {
            DELIVERIES.cancel(reservation);
            return;
        }
        try {
            ENCODERS.execute(() -> {
                try {
                    List<CompatNetwork.PreparedModelPacket> packets = ModelChunkPreparation.prepare(
                            modelId, prepared.payloadDigest(), prepared.bytes(),
                            () -> generation == GENERATION.get() && DELIVERIES.isCurrent(reservation),
                            CompatNetwork::prepareModelChunk);
                    if (generation != GENERATION.get() || packets.isEmpty()) {
                        DELIVERIES.cancel(reservation);
                        return;
                    }
                    // Publication holds no game objects beyond the immutable recipient handles.
                    DELIVERIES.publish(reservation, recipients, packets);
                } catch (Throwable exception) {
                    DELIVERIES.cancel(reservation);
                    CompatMod.LOG.warn("YSM-EF Compat: failed to prepare model chunks '{}'",
                            modelId, exception);
                }
            });
        } catch (RejectedExecutionException busy) {
            DELIVERIES.cancel(reservation);
        }
    }

    /** Game-state checks and bounded packet submission stay on the server tick thread. */
    public static void tick(MinecraftServer server) {
        try {
            DELIVERIES.drain(CHUNKS_PER_TICK, (delivery, packet) -> {
                if (delivery.server() != server || delivery.generation() != GENERATION.get()) {
                    return false;
                }
                Request request = delivery.request();
                ServerPlayer player = server.getPlayerList().getPlayer(request.recipientUuid());
                if (!CompatNetwork.isConnected(player)
                        || player.connection != request.recipientConnection()
                        || !authorizedSelection(player, request.sourceEntityId(),
                        request.sourceEntityUuid(), delivery.modelId())) {
                    return false;
                }
                CompatNetwork.sendPreparedModelChunk(player, packet);
                return true;
            });
        } catch (RuntimeException exception) {
            CompatMod.LOG.warn("YSM-EF Compat: model packet delivery failed", exception);
        }
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
