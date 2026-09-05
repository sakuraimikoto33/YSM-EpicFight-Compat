package net.okitsu.ysmepicfightcompat.animation;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.okitsu.ysmepicfightcompat.network.CompatNetwork;
import net.okitsu.ysmepicfightcompat.network.ScriptSyncRateLimiter;
import net.okitsu.ysmepicfightcompat.network.ScriptSyncValues;
import net.okitsu.ysmepicfightcompat.network.message.ScriptSyncRequestMessage;
import net.okitsu.ysmepicfightcompat.network.message.ScriptSyncSnapshotMessage;
import net.okitsu.ysmepicfightcompat.render.PlayerSelectionResolver;

import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.Map;
import java.util.WeakHashMap;

/** Render-thread delivery of authenticated numeric sync events into a matching live model. */
public final class ClientScriptEvents {
    private static final class Binding {
        private String modelId = "";
        private WeakReference<MolangScriptRuntime> runtime = new WeakReference<>(null);
        private long lastSequence;
        private final ScriptSyncRateLimiter senderLimit = new ScriptSyncRateLimiter();
    }

    private record Pending(WeakReference<LivingEntity> source,
                           ScriptSyncSnapshotMessage snapshot, long receivedAt) {
    }

    private static final Map<LivingEntity, Binding> BINDINGS = new WeakHashMap<>();
    private static final ArrayDeque<Pending> PENDING = new ArrayDeque<>();

    private ClientScriptEvents() {
    }

    public static synchronized void bind(LivingEntity entity, String modelId,
                                         MolangScriptRuntime runtime) {
        if (entity == null || runtime == null || modelId == null || modelId.isBlank()) {
            return;
        }
        Binding binding = BINDINGS.computeIfAbsent(entity, ignored -> new Binding());
        MolangScriptRuntime previous = binding.runtime.get();
        if (previous != null && previous != runtime) {
            previous.syncSender(null);
        }
        binding.modelId = modelId;
        binding.runtime = new WeakReference<>(runtime);
        WeakReference<LivingEntity> source = new WeakReference<>(entity);
        WeakReference<MolangScriptRuntime> boundRuntime = new WeakReference<>(runtime);
        runtime.syncSender(arguments -> send(source, boundRuntime, modelId, arguments));
        flushPending();
    }

    public static synchronized void accept(ScriptSyncSnapshotMessage message) {
        Minecraft client = Minecraft.getInstance();
        if (message == null || client.level == null) {
            return;
        }
        flushPending();
        if (!(client.level.getEntity(message.entityId()) instanceof Player entity)) {
            return;
        }
        Binding binding = BINDINGS.computeIfAbsent(entity, ignored -> new Binding());
        if (!ScriptSyncValues.accepts(message.entityId(), message.entityUuid(),
                message.modelId(), message.sequence(), entity.getId(), entity.getUUID(),
                selectedModel(entity), binding.lastSequence)) {
            return;
        }
        binding.lastSequence = message.sequence();
        MolangScriptRuntime runtime = binding.runtime.get();
        if (runtime != null && message.modelId().equals(binding.modelId)) {
            runtime.enqueueSync(message.arguments());
            return;
        }
        if (PENDING.size() >= ScriptSyncValues.MAX_PENDING) {
            PENDING.removeFirst();
        }
        PENDING.addLast(new Pending(new WeakReference<>(entity), message,
                client.level.getGameTime()));
    }

    public static synchronized void unbind(LivingEntity entity, MolangScriptRuntime runtime) {
        Binding binding = BINDINGS.get(entity);
        if (binding != null && binding.runtime.get() == runtime) {
            binding.runtime.clear();
            runtime.syncSender(null);
        }
    }

    private static synchronized void send(WeakReference<LivingEntity> source,
                                          WeakReference<MolangScriptRuntime> runtime,
                                          String modelId, double[] arguments) {
        Minecraft client = Minecraft.getInstance();
        LivingEntity entity = source.get();
        if (entity == null || client.player != entity || client.level == null
                || entity.level() != client.level || entity.isRemoved()
                || client.getConnection() == null
                || !client.getConnection().getConnection().isConnected()
                || !CompatNetwork.CHANNEL.isRemotePresent(client.getConnection().getConnection())
                || !MolangScriptRuntime.validSync(arguments)) {
            return;
        }
        Binding binding = BINDINGS.get(entity);
        if (binding == null || binding.runtime.get() != runtime.get()
                || !modelId.equals(binding.modelId)
                || !ScriptSyncValues.selectedModel(modelId, selectedModel(entity))
                || !binding.senderLimit.allow(client.level.getGameTime())) {
            return;
        }
        // Do not echo locally: the sender receives the same authenticated server snapshot.
        CompatNetwork.CHANNEL.sendToServer(new ScriptSyncRequestMessage(modelId, arguments));
    }

    private static void flushPending() {
        Minecraft client = Minecraft.getInstance();
        Iterator<Pending> iterator = PENDING.iterator();
        while (iterator.hasNext()) {
            Pending pending = iterator.next();
            LivingEntity entity = pending.source().get();
            ScriptSyncSnapshotMessage message = pending.snapshot();
            if (client.level == null || entity == null || entity.isRemoved()
                    || entity.level() != client.level
                    || client.level.getEntity(message.entityId()) != entity
                    || !message.entityUuid().equals(entity.getUUID())
                    || !ScriptSyncValues.pendingAlive(pending.receivedAt(), client.level.getGameTime())
                    || !ScriptSyncValues.selectedModel(message.modelId(), selectedModel(entity))) {
                iterator.remove();
                continue;
            }
            Binding binding = BINDINGS.get(entity);
            MolangScriptRuntime runtime = binding == null ? null : binding.runtime.get();
            if (runtime != null && message.modelId().equals(binding.modelId)) {
                runtime.enqueueSync(message.arguments());
                iterator.remove();
            }
        }
    }

    private static String selectedModel(LivingEntity entity) {
        if (!(entity instanceof Player player)) {
            return "";
        }
        PlayerSelectionResolver.Selection selection = PlayerSelectionResolver.current(player);
        return selection == null ? "" : selection.modelId();
    }

    public static synchronized void clear() {
        for (Binding binding : BINDINGS.values()) {
            MolangScriptRuntime runtime = binding.runtime.get();
            if (runtime != null) {
                runtime.syncSender(null);
                runtime.reset();
            }
        }
        BINDINGS.clear();
        PENDING.clear();
    }
}
