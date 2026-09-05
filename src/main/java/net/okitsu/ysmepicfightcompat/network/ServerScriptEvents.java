package net.okitsu.ysmepicfightcompat.network;

import net.minecraft.server.level.ServerPlayer;
import net.okitsu.ysmepicfightcompat.network.message.ScriptSyncSnapshotMessage;

import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Authenticates the sender's current official selection before relaying a numeric event. */
public final class ServerScriptEvents {
    private static final Map<ServerPlayer, ScriptSyncRateLimiter> LIMITERS = new WeakHashMap<>();
    private static final AtomicLong SEQUENCE = new AtomicLong();

    private ServerScriptEvents() {
    }

    public static void accept(ServerPlayer sender, String modelId, double[] arguments) {
        if (!CompatNetwork.isConnected(sender) || sender.isRemoved()) {
            return;
        }
        synchronized (LIMITERS) {
            if (!LIMITERS.computeIfAbsent(sender, ignored -> new ScriptSyncRateLimiter())
                    .allow(sender.server.getTickCount())) {
                return;
            }
        }
        PlayerSelectionNbt.Selection selected = PlayerSelectionNbt.read(sender);
        if (selected == null || !ScriptSyncValues.selectedModel(modelId, selected.modelId())) {
            return;
        }
        // Identity and ordering are server-owned; the request contains neither field.
        long sequence = SEQUENCE.incrementAndGet();
        if (sequence <= 0L) {
            return;
        }
        CompatNetwork.toTrackersAndSelf(sender, new ScriptSyncSnapshotMessage(
                sender.getId(), sender.getUUID(), sequence, modelId, arguments));
    }
}
