package net.okitsu.ysmepicfightcompat.animation;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.okitsu.ysmepicfightcompat.CompatMod;
import net.okitsu.ysmepicfightcompat.integration.tlm.TouhouMaidSelectionAccess;
import net.okitsu.ysmepicfightcompat.mesh.CombatMeshCache;
import net.okitsu.ysmepicfightcompat.mesh.CompatHumanoidMesh;
import net.okitsu.ysmepicfightcompat.network.message.AttackSwingSoundMessage;
import net.okitsu.ysmepicfightcompat.render.PlayerSelectionResolver;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Chooses a successful YSM attack sound or the exact Epic Fight fallback on each client. */
public final class ClientAttackSoundRouter {
    private static final long DISCOVERY_WAIT_TICKS = 1L;

    private record Key(UUID entityUuid, int sequence) {
    }

    private record Replacement(LivingEntity entity, String modelId,
                               CompatHumanoidMesh mesh) {
    }

    private record Pending(AttackSwingSoundMessage message, long receivedAt) {
    }

    private static final Map<Key, Pending> PENDING = new LinkedHashMap<>();

    private ClientAttackSoundRouter() {
    }

    public static synchronized void receive(AttackSwingSoundMessage message) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return;
        }
        Replacement replacement = replacement(level, message);
        if (replacement == null) {
            play(level, message);
            return;
        }
        if (AttackSoundOwnership.consume(replacement.entity(), message.hand(),
                replacement.modelId())) {
            logSuppressed(replacement, message, "played YSM sound");
            return;
        }
        if (replacement.mesh().hasAttackSoundRoute(
                replacement.entity(), message.hand())) {
            logSuppressed(replacement, message, "authored YSM sound route");
            return;
        }
        PENDING.putIfAbsent(new Key(message.entityUuid(), message.sequence()),
                new Pending(message, level.getGameTime()));
    }

    /** Called after model animation outputs advance at the end of the client tick. */
    public static synchronized void tick() {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            PENDING.clear();
            return;
        }
        long now = level.getGameTime();
        Iterator<Map.Entry<Key, Pending>> iterator = PENDING.entrySet().iterator();
        while (iterator.hasNext()) {
            Pending pending = iterator.next().getValue();
            AttackSwingSoundMessage message = pending.message();
            Replacement replacement = replacement(level, message);
            if (replacement == null) {
                play(level, message);
                iterator.remove();
                continue;
            }
            if (AttackSoundOwnership.consume(replacement.entity(), message.hand(),
                    replacement.modelId())) {
                logSuppressed(replacement, message, "played YSM sound");
                iterator.remove();
                continue;
            }
            if (replacement.mesh().hasAttackSoundRoute(
                    replacement.entity(), message.hand())) {
                logSuppressed(replacement, message, "authored YSM sound route");
                iterator.remove();
                continue;
            }
            long age = Math.max(0L, now - pending.receivedAt());
            if (age >= DISCOVERY_WAIT_TICKS) {
                CompatMod.LOG.debug(
                        "YSM-EF Compat: keeping Epic Fight swing sound '{}' for entity='{}' model='{}' hand={} because no authored YSM attack sound route became active",
                        message.sound(), replacement.entity().getScoreboardName(),
                        replacement.modelId(), message.hand());
                play(level, message);
                iterator.remove();
            }
        }
    }

    public static synchronized void clear() {
        PENDING.clear();
        AttackSoundOwnership.clear();
    }

    /** Drops delayed fallback and ownership state when one tracked entity leaves. */
    public static synchronized void release(LivingEntity entity) {
        if (entity == null) {
            return;
        }
        UUID entityUuid = entity.getUUID();
        PENDING.keySet().removeIf(key -> key.entityUuid().equals(entityUuid));
        AttackSoundOwnership.release(entity);
    }

    private static Replacement replacement(ClientLevel level,
                                           AttackSwingSoundMessage message) {
        Entity candidate = level.getEntity(message.entityId());
        if (!(candidate instanceof LivingEntity entity)
                || !entity.getUUID().equals(message.entityUuid())) {
            return null;
        }
        String modelId;
        if (entity instanceof AbstractClientPlayer player) {
            PlayerSelectionResolver.Selection selection =
                    PlayerSelectionResolver.current(player);
            modelId = selection == null ? null : selection.modelId();
        } else {
            TouhouMaidSelectionAccess.Selection selection =
                    TouhouMaidSelectionAccess.resolve(entity);
            modelId = selection == null ? null : selection.modelId();
        }
        if (modelId == null) {
            return null;
        }
        CompatHumanoidMesh mesh = CombatMeshCache.readyMesh(modelId);
        if (mesh == null || !mesh.replacesAttackItem(entity, message.hand())) {
            return null;
        }
        return new Replacement(entity, modelId, mesh);
    }

    private static void play(ClientLevel level, AttackSwingSoundMessage message) {
        SoundEvent sound = BuiltInRegistries.SOUND_EVENT.getOptional(message.sound())
                .orElse(null);
        if (sound != null) {
            Entity entity = level.getEntity(message.entityId());
            SoundSource source = entity instanceof LivingEntity living
                    ? living.getSoundSource() : SoundSource.PLAYERS;
            level.playLocalSound(message.x(), message.y(), message.z(), sound,
                    source, message.volume(), message.pitch(), false);
        }
    }

    private static void logSuppressed(Replacement replacement,
                                      AttackSwingSoundMessage message,
                                      String reason) {
        CompatMod.LOG.debug(
                "YSM-EF Compat: suppressing Epic Fight swing sound '{}' for entity='{}' model='{}' hand={} ({})",
                message.sound(), replacement.entity().getScoreboardName(),
                replacement.modelId(), message.hand(), reason);
    }
}
