package net.okitsu.ysmepicfightcompat.animation;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.okitsu.ysmepicfightcompat.network.message.ShieldBlockMessage;

import java.lang.ref.ReferenceQueue;
import java.util.HashMap;
import java.util.Map;

/** Client-only successful shield-block observations, owned by exact entity instances. */
public final class ClientShieldBlockState {
    private static final Map<WeakIdentityKey<LivingEntity>, ShieldBlockCooldown> WINDOWS =
            new HashMap<>();
    private static final ReferenceQueue<LivingEntity> COLLECTED = new ReferenceQueue<>();

    private ClientShieldBlockState() {
    }

    /** Receives only the server success message after its network handler has enqueued it. */
    public static void receive(ShieldBlockMessage message) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (message == null || !minecraft.isSameThread() || level == null) {
            return;
        }
        Entity candidate = level.getEntity(message.entityId());
        if (!(candidate instanceof LivingEntity entity) || entity.level() != level
                || entity.isRemoved()
                || !message.matchesIdentity(entity.getId(), entity.getUUID())) {
            return;
        }
        synchronized (WINDOWS) {
            prune();
            WINDOWS.computeIfAbsent(new WeakIdentityKey<>(entity, COLLECTED),
                    ignored -> new ShieldBlockCooldown()).refresh(entity.tickCount);
        }
    }

    public static boolean inCooldown(LivingEntity entity) {
        if (entity == null) {
            return false;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (!minecraft.isSameThread()) {
            return false;
        }
        if (!(entity.level() instanceof ClientLevel level) || minecraft.level != level
                || entity.isRemoved() || level.getEntity(entity.getId()) != entity) {
            remove(entity);
            return false;
        }
        synchronized (WINDOWS) {
            prune();
            WeakIdentityKey<LivingEntity> key = new WeakIdentityKey<>(entity, null);
            ShieldBlockCooldown window = WINDOWS.get(key);
            if (window == null) {
                return false;
            }
            if (!window.inCooldown(entity.tickCount)) {
                WINDOWS.remove(key);
                return false;
            }
            return true;
        }
    }

    public static void remove(LivingEntity entity) {
        if (entity == null) {
            return;
        }
        synchronized (WINDOWS) {
            prune();
            WINDOWS.remove(new WeakIdentityKey<>(entity, null));
        }
    }

    public static void clear() {
        synchronized (WINDOWS) {
            WINDOWS.clear();
            prune();
        }
    }

    private static void prune() {
        for (var collected = COLLECTED.poll(); collected != null; collected = COLLECTED.poll()) {
            WINDOWS.remove(collected);
        }
    }
}
