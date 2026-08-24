package net.okitsu.ysmepicfightcompat.animation;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.okitsu.ysmepicfightcompat.CompatMod;
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

    private record Key(UUID playerId, int sequence) {
    }

    private record Replacement(AbstractClientPlayer player, String modelId,
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
        if (AttackSoundOwnership.consume(replacement.player(), message.hand(),
                replacement.modelId())) {
            logSuppressed(replacement, message, "played YSM sound");
            return;
        }
        if (replacement.mesh().hasAttackSoundRoute(
                replacement.player(), message.hand())) {
            logSuppressed(replacement, message, "authored YSM sound route");
            return;
        }
        PENDING.putIfAbsent(new Key(message.playerId(), message.sequence()),
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
            if (AttackSoundOwnership.consume(replacement.player(), message.hand(),
                    replacement.modelId())) {
                logSuppressed(replacement, message, "played YSM sound");
                iterator.remove();
                continue;
            }
            if (replacement.mesh().hasAttackSoundRoute(
                    replacement.player(), message.hand())) {
                logSuppressed(replacement, message, "authored YSM sound route");
                iterator.remove();
                continue;
            }
            long age = Math.max(0L, now - pending.receivedAt());
            if (age >= DISCOVERY_WAIT_TICKS) {
                CompatMod.LOG.debug(
                        "YSM-EF Compat: keeping Epic Fight swing sound '{}' for player='{}' model='{}' hand={} because no authored YSM attack sound route became active",
                        message.sound(), replacement.player().getScoreboardName(),
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

    private static Replacement replacement(ClientLevel level,
                                           AttackSwingSoundMessage message) {
        Entity entity = level.getEntity(message.entityId());
        if (!(entity instanceof AbstractClientPlayer player)
                || !player.getUUID().equals(message.playerId())) {
            return null;
        }
        PlayerSelectionResolver.Selection selection = PlayerSelectionResolver.current(player);
        if (selection == null) {
            return null;
        }
        CompatHumanoidMesh mesh = CombatMeshCache.readyMesh(selection.modelId());
        if (mesh == null || !mesh.replacesAttackItem(player, message.hand())) {
            return null;
        }
        return new Replacement(player, selection.modelId(), mesh);
    }

    private static void play(ClientLevel level, AttackSwingSoundMessage message) {
        SoundEvent sound = BuiltInRegistries.SOUND_EVENT.getOptional(message.sound())
                .orElse(null);
        if (sound != null) {
            level.playLocalSound(message.x(), message.y(), message.z(), sound,
                    SoundSource.PLAYERS, message.volume(), message.pitch(), false);
        }
    }

    private static void logSuppressed(Replacement replacement,
                                      AttackSwingSoundMessage message,
                                      String reason) {
        CompatMod.LOG.debug(
                "YSM-EF Compat: suppressing Epic Fight swing sound '{}' for player='{}' model='{}' hand={} ({})",
                message.sound(), replacement.player().getScoreboardName(),
                replacement.modelId(), message.hand(), reason);
    }
}
