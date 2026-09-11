package net.okitsu.ysmepicfightcompat.animation;

import net.minecraft.world.entity.player.Player;
import net.okitsu.ysmepicfightcompat.CompatMod;
import net.okitsu.ysmmapping.api.MappingSnapshot;
import net.okitsu.ysmmapping.api.YsmMappingApi;
import net.okitsu.ysmmapping.api.YsmSymbolKey;
import net.okitsu.ysmmapping.api.YsmSymbols;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** Client selections live in the animation state; the persistent selection NBT is server-only. */
public final class OfficialClientModelSelection {
    private static final Set<YsmSymbolKey<?>> SYMBOLS = Set.of(
            YsmSymbols.PLAYER_STATE_MODEL_ID_GETTER,
            YsmSymbols.PLAYER_STATE_MODEL_DISABLED_GETTER);
    private static final Map<WeakIdentityKey<Player>, WeakReference<Object>> STATES = new HashMap<>();
    private static final ReferenceQueue<Player> COLLECTED = new ReferenceQueue<>();
    private static MappedClientModelSelection access;
    private static boolean resolved;

    private OfficialClientModelSelection() {
    }

    /** Captures an existing client state without retaining its player through the state object. */
    public static void register(Player player, Object state) {
        if (player == null || state == null || !player.level().isClientSide()) return;
        synchronized (STATES) {
            prune();
            STATES.put(new WeakIdentityKey<>(player, COLLECTED), new WeakReference<>(state));
        }
    }

    static String modelId(Player player) {
        if (player == null) return null;
        Object state;
        synchronized (STATES) {
            prune();
            WeakReference<Object> reference = STATES.get(new WeakIdentityKey<>(player, null));
            state = reference == null ? null : reference.get();
        }
        if (state == null) return null;
        MappedClientModelSelection current = access();
        return current == null ? null : current.modelId(state);
    }

    public static void remove(Player player) {
        if (player == null) return;
        synchronized (STATES) {
            prune();
            STATES.remove(new WeakIdentityKey<>(player, null));
        }
    }

    /** Only the session lifecycle clears captures; binding configuration state must not clear them. */
    public static void clear() {
        synchronized (STATES) {
            STATES.clear();
            prune();
        }
    }

    private static void prune() {
        for (var collected = COLLECTED.poll(); collected != null; collected = COLLECTED.poll()) {
            STATES.remove(collected);
        }
    }

    private static synchronized MappedClientModelSelection access() {
        if (resolved) return access;
        resolved = true;
        try {
            MappingSnapshot mapping = YsmMappingApi.resolve(CompatMod.MOD_ID, SYMBOLS);
            access = MappedClientModelSelection.resolve(
                    mapping.require(YsmSymbols.PLAYER_STATE_MODEL_ID_GETTER),
                    mapping.require(YsmSymbols.PLAYER_STATE_MODEL_DISABLED_GETTER),
                    OfficialClientModelSelection.class.getClassLoader()).orElse(null);
        } catch (Exception | LinkageError unavailable) {
            // Do not expose private runtime identifiers from mapping/reflection exceptions.
        }
        if (access == null) {
            CompatMod.LOG.warn("YSM-EF Compat: official client model selection is unavailable");
        }
        return access;
    }
}
