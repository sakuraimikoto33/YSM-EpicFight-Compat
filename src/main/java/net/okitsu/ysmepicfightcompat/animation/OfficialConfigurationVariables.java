package net.okitsu.ysmepicfightcompat.animation;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.okitsu.ysmepicfightcompat.CompatMod;
import net.okitsu.ysmepicfightcompat.network.CompatNetwork;
import net.okitsu.ysmepicfightcompat.network.ConfigurationVariableValues;
import net.okitsu.ysmepicfightcompat.network.PlayerSelectionNbt;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** Mirrors non-persistent {@code v.*} changes while official YSM rendering is suspended. */
public final class OfficialConfigurationVariables {
    private static final Map<UUID, ModelConfigurationOverrides> STATES = new ConcurrentHashMap<>();
    private static final AtomicBoolean UPDATE_LOGGED = new AtomicBoolean();

    private OfficialConfigurationVariables() {
    }

    public static void apply(Player player, String expression) {
        if (player == null || expression == null || expression.isBlank()) {
            return;
        }
        // Configuration UI operations must use the live official selection, not the render cache.
        PlayerSelectionNbt.Selection selection = PlayerSelectionNbt.read(player);
        if (selection == null) {
            reset(player);
            return;
        }
        ModelConfigurationOverrides state = STATES.computeIfAbsent(player.getUUID(),
                ignored -> new ModelConfigurationOverrides());
        EntityAnimationEnvironment fallback = new EntityAnimationEnvironment(
                player, new HashMap<>(), new HashSet<>(), selection.modelId());
        fallback.update(0.0F, false, 0.0D);
        Map<String, Double> changes = state.evaluate(selection.modelId(), expression, fallback);
        if (!changes.isEmpty()) {
            CompatNetwork.sendConfigurationUpdate(changes);
        }
        if (UPDATE_LOGGED.compareAndSet(false, true)) {
            CompatMod.LOG.info(
                    "YSM-EF Compat: official YSM configuration variable update was mirrored");
        }
    }

    static ConfigurationVariableOverrides.Lookup lookup(LivingEntity entity, String modelId,
                                                         int slot) {
        if (!(entity instanceof Player)) {
            return ConfigurationVariableOverrides.Lookup.missing();
        }
        ModelConfigurationOverrides state = STATES.get(entity.getUUID());
        return state == null ? ConfigurationVariableOverrides.Lookup.missing()
                : state.lookup(modelId, slot);
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
        STATES.computeIfAbsent(playerId, ignored -> new ModelConfigurationOverrides())
                .accept(modelId == null ? "" : modelId, checked);
    }

    public static void clear() {
        STATES.clear();
        UPDATE_LOGGED.set(false);
    }
}
