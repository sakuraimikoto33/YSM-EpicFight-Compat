package net.okitsu.ysmepicfightcompat.animation;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.okitsu.ysmepicfightcompat.CompatMod;
import net.okitsu.ysmepicfightcompat.network.CompatNetwork;
import net.okitsu.ysmepicfightcompat.network.ConfigurationVariableValues;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** Mirrors non-persistent {@code v.*} changes while official YSM rendering is suspended. */
public final class OfficialConfigurationVariables {
    private static final Map<UUID, ScopedState> STATES = new ConcurrentHashMap<>();
    private static final AtomicBoolean UPDATE_LOGGED = new AtomicBoolean();

    private static final class ScopedState {
        private final ConfigurationVariableOverrides overrides =
                new ConfigurationVariableOverrides();
        private String modelId;

        synchronized void bindModel(String nextModelId) {
            if (modelId == null) {
                modelId = nextModelId;
            } else if (!Objects.equals(modelId, nextModelId)) {
                modelId = nextModelId;
                overrides.clear();
            }
        }

        synchronized void accept(String nextModelId, Map<String, Double> values) {
            modelId = nextModelId;
            overrides.replace(values);
        }
    }

    private OfficialConfigurationVariables() {
    }

    public static void apply(Player player, String expression) {
        if (player == null || expression == null || expression.isBlank()) {
            return;
        }
        ScopedState state = STATES.computeIfAbsent(player.getUUID(), ignored -> new ScopedState());
        EntityAnimationEnvironment fallback = new EntityAnimationEnvironment(
                player, new HashMap<>(), new HashSet<>());
        fallback.update(0.0F, false, 0.0D);
        Map<String, Double> changes = state.overrides.evaluate(expression, fallback);
        if (!changes.isEmpty()) {
            CompatNetwork.sendConfigurationUpdate(changes);
        }
        if (UPDATE_LOGGED.compareAndSet(false, true)) {
            CompatMod.LOG.info(
                    "YSM-EF Compat: official YSM configuration variable update was mirrored");
        }
    }

    static ConfigurationVariableOverrides.Lookup lookup(LivingEntity entity, int slot) {
        if (!(entity instanceof Player)) {
            return ConfigurationVariableOverrides.Lookup.missing();
        }
        ScopedState state = STATES.get(entity.getUUID());
        return state == null ? ConfigurationVariableOverrides.Lookup.missing()
                : state.overrides.lookup(slot);
    }

    public static void bindModel(Player player, String modelId) {
        if (player == null) {
            return;
        }
        ScopedState state = STATES.get(player.getUUID());
        if (state != null) {
            state.bindModel(modelId == null ? "" : modelId);
        }
    }

    public static void reset(Player player) {
        if (player != null) {
            STATES.remove(player.getUUID());
        }
    }

    /** Accepts a bounded server snapshot for a player on the current connection. */
    public static void acceptSnapshot(UUID playerId, String modelId,
                                      Map<String, Double> values) {
        if (playerId == null) {
            return;
        }
        Map<String, Double> checked = ConfigurationVariableValues.validate(values);
        STATES.computeIfAbsent(playerId, ignored -> new ScopedState())
                .accept(modelId == null ? "" : modelId, checked);
    }

    public static void clear() {
        STATES.clear();
        UPDATE_LOGGED.set(false);
    }
}
