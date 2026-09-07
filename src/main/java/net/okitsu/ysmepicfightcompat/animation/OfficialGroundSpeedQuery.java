package net.okitsu.ysmepicfightcompat.animation;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.okitsu.ysmepicfightcompat.CompatMod;
import net.okitsu.ysmmapping.api.MappingSnapshot;
import net.okitsu.ysmmapping.api.YsmMappingApi;
import net.okitsu.ysmmapping.api.YsmSymbolKey;
import net.okitsu.ysmmapping.api.YsmSymbols;

import java.lang.ref.WeakReference;
import java.lang.ref.ReferenceQueue;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** Samples the final official query against an already-existing player context. */
public final class OfficialGroundSpeedQuery {
    private static final Set<YsmSymbolKey<?>> SYMBOLS = Set.of(
            YsmSymbols.MOLANG_GROUND_SPEED2_QUERY,
            YsmSymbols.MOLANG_QUERY_CONTEXT_GET);
    // Entity identity, not UUID: integrated-server and replacement-world players may
    // share a UUID but must never share an official motion context.
    private static final Map<WeakIdentityKey<Player>, Captured> CONTEXTS = new HashMap<>();
    private static final ReferenceQueue<Player> COLLECTED = new ReferenceQueue<>();
    private static MappedMolangQuery query;
    private static boolean resolved;

    private OfficialGroundSpeedQuery() {
    }

    /** Called only after the mapped official player-state constructor has completed. */
    public static void register(Player player, Object context) {
        if (player == null || context == null || !player.level().isClientSide()) return;
        synchronized (CONTEXTS) {
            prune();
            CONTEXTS.put(new WeakIdentityKey<>(player, COLLECTED), new Captured(context));
        }
    }

    static double sample(LivingEntity entity) {
        if (!(entity instanceof Player player)) return 0.0D;
        Captured captured;
        synchronized (CONTEXTS) {
            prune();
            captured = CONTEXTS.get(new WeakIdentityKey<>(player, null));
        }
        if (captured == null) return 0.0D;
        MappedMolangQuery current = query();
        return current == null ? 0.0D : captured.sample(current);
    }

    public static void remove(Player player) {
        if (player == null) return;
        synchronized (CONTEXTS) {
            prune();
            CONTEXTS.remove(new WeakIdentityKey<>(player, null));
        }
    }

    public static void clear() {
        synchronized (CONTEXTS) {
            CONTEXTS.clear();
            prune();
        }
    }

    private static void prune() {
        for (var collected = COLLECTED.poll(); collected != null; collected = COLLECTED.poll()) {
            CONTEXTS.remove(collected);
        }
    }

    private static synchronized MappedMolangQuery query() {
        if (resolved) return query;
        resolved = true;
        try {
            MappingSnapshot mapping = YsmMappingApi.resolve(CompatMod.MOD_ID, SYMBOLS);
            query = MappedMolangQuery.resolve(
                    mapping.require(YsmSymbols.MOLANG_GROUND_SPEED2_QUERY),
                    mapping.require(YsmSymbols.MOLANG_QUERY_CONTEXT_GET),
                    OfficialGroundSpeedQuery.class.getClassLoader()).orElse(null);
        } catch (Exception | LinkageError ignored) {
            // Mapping/reflection exceptions may contain private runtime identifiers.
        }
        if (query == null) {
            CompatMod.LOG.warn("YSM-EF Compat: official ground_speed2 query is unavailable");
        }
        return query;
    }

    private static final class Captured {
        private final WeakReference<Object> context;
        private WeakReference<MappedMolangQuery.Bound> binding = new WeakReference<>(null);

        private Captured(Object context) {
            this.context = new WeakReference<>(context);
        }

        private synchronized double sample(MappedMolangQuery query) {
            MappedMolangQuery.Bound bound = binding.get();
            if (bound == null) {
                Object live = context.get();
                if (live == null) return 0.0D;
                bound = query.bind(live).orElse(null);
                if (bound == null) return 0.0D;
                // A strong binding would retain context -> player through the weak-key map.
                binding = new WeakReference<>(bound);
            }
            return bound.sample().orElse(0.0D);
        }
    }
}
