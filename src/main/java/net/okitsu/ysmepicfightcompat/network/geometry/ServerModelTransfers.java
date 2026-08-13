package net.okitsu.ysmepicfightcompat.network.geometry;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.okitsu.ysmepicfightcompat.CompatMod;
import net.okitsu.ysmepicfightcompat.assets.LocalModelRepository;
import net.okitsu.ysmepicfightcompat.assets.ModelBundle;
import net.okitsu.ysmepicfightcompat.network.CompatNetwork;
import net.okitsu.ysmepicfightcompat.network.PlayerSelectionNbt;
import net.okitsu.ysmepicfightcompat.network.message.ModelChunkMessage;

import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** Prepares approved model geometry outside the server tick and streams bounded chunks. */
public final class ServerModelTransfers {
    private static final int MAX_CACHE_ENTRIES = 8;
    private static final long MAX_CACHE_BYTES = 128L * 1024 * 1024;
    private static final ExecutorService ENCODERS = Executors.newFixedThreadPool(2, task -> {
        Thread worker = new Thread(task, "ysm-ef-model-sender");
        worker.setDaemon(true);
        return worker;
    });
    private static final Set<Request> RUNNING = ConcurrentHashMap.newKeySet();
    private static final LinkedHashMap<String, Cached> CACHE =
            new LinkedHashMap<>(16, 0.75F, true);
    private static final AtomicInteger GENERATION = new AtomicInteger();
    private static long cachedBytes;

    private record Request(UUID playerId, String modelId) {
    }

    private record Cached(long sourceStamp, byte[] bytes) {
    }

    private ServerModelTransfers() {
    }

    public static void request(ServerPlayer recipient, String modelId) {
        if (!CompatNetwork.isConnected(recipient) || modelId == null || modelId.isBlank()) {
            return;
        }
        MinecraftServer server = recipient.server;
        if (!selectedByOnlinePlayer(server, modelId) || !LocalModelRepository.exists(modelId)) {
            CompatNetwork.toPlayer(recipient, ModelChunkMessage.unavailable(modelId));
            return;
        }
        Request request = new Request(recipient.getUUID(), modelId);
        if (!RUNNING.add(request)) {
            return;
        }
        int expectedGeneration = GENERATION.get();
        ENCODERS.execute(() -> prepare(server, request, expectedGeneration));
    }

    public static void clear() {
        GENERATION.incrementAndGet();
        RUNNING.clear();
        synchronized (CACHE) {
            CACHE.clear();
            cachedBytes = 0;
        }
    }

    private static void prepare(MinecraftServer server, Request request, int expectedGeneration) {
        byte[] payload = null;
        try {
            payload = encoded(request.modelId(), expectedGeneration);
        } catch (Exception exception) {
            CompatMod.LOG.warn(
                    "YSM-EF Compat: failed to prepare server model '{}'", request.modelId(), exception);
        }
        byte[] completed = payload;
        if (expectedGeneration != GENERATION.get()) {
            RUNNING.remove(request);
            return;
        }
        server.execute(() -> {
            try {
                if (expectedGeneration != GENERATION.get()) {
                    return;
                }
                ServerPlayer player = server.getPlayerList().getPlayer(request.playerId());
                if (!CompatNetwork.isConnected(player)) {
                    return;
                }
                if (completed == null || !selectedByOnlinePlayer(server, request.modelId())) {
                    CompatNetwork.toPlayer(player, ModelChunkMessage.unavailable(request.modelId()));
                } else {
                    sendChunks(player, request.modelId(), completed);
                }
            } finally {
                RUNNING.remove(request);
            }
        });
    }

    private static byte[] encoded(String modelId, int expectedGeneration) throws IOException {
        long stamp = LocalModelRepository.metadataStamp(modelId);
        synchronized (CACHE) {
            Cached known = CACHE.get(modelId);
            if (stamp >= 0 && known != null && known.sourceStamp() == stamp) {
                return known.bytes();
            }
        }
        ModelBundle model = LocalModelRepository.load(modelId);
        if (model == null) {
            return null;
        }
        byte[] payload = GeometryTransferCodec.encode(model);
        synchronized (CACHE) {
            if (expectedGeneration != GENERATION.get() || stamp < 0) {
                return payload;
            }
            Cached previous = CACHE.put(modelId, new Cached(stamp, payload));
            if (previous != null) {
                cachedBytes -= previous.bytes().length;
            }
            cachedBytes += payload.length;
            while (CACHE.size() > MAX_CACHE_ENTRIES || cachedBytes > MAX_CACHE_BYTES) {
                String oldest = CACHE.keySet().iterator().next();
                cachedBytes -= CACHE.remove(oldest).bytes().length;
            }
        }
        return payload;
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

    private static void sendChunks(ServerPlayer recipient, String modelId, byte[] payload) {
        UUID transfer = UUID.randomUUID();
        int chunkCount = (payload.length + ModelChunkMessage.CHUNK_BYTES - 1)
                / ModelChunkMessage.CHUNK_BYTES;
        for (int index = 0; index < chunkCount; index++) {
            int offset = index * ModelChunkMessage.CHUNK_BYTES;
            int length = Math.min(ModelChunkMessage.CHUNK_BYTES, payload.length - offset);
            CompatNetwork.toPlayer(recipient, new ModelChunkMessage(
                    transfer, modelId, payload.length, index, chunkCount,
                    Arrays.copyOfRange(payload, offset, offset + length)));
        }
        CompatMod.LOG.info(
                "YSM-EF Compat: streamed '{}' to '{}' in {} chunks",
                modelId, recipient.getGameProfile().getName(), chunkCount);
    }
}
