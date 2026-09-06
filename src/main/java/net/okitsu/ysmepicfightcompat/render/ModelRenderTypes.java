package net.okitsu.ysmepicfightcompat.render;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.RenderType;
import net.okitsu.ysmepicfightcompat.mixin.CompositeRenderStateAccessor;
import net.okitsu.ysmepicfightcompat.mixin.CompositeRenderTypeAccessor;
import net.okitsu.ysmepicfightcompat.mixin.RenderTypeSortingAccessor;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/** Applies the official all_cutout back-face policy without changing material semantics. */
public final class ModelRenderTypes extends RenderType {
    private static final Cache<RenderType, RenderType> CULLED = new Cache<>(512);

    private ModelRenderTypes() {
        super("ysm_epicfight_compat:unused", DefaultVertexFormat.NEW_ENTITY,
                VertexFormat.Mode.TRIANGLES, 256, false, false, () -> { }, () -> { });
        throw new UnsupportedOperationException("Static render-type factory");
    }

    /**
     * Call after Epic Fight's texture replacement and only for the normal model pass.
     * A decoration/outline pass keeps its own material. In particular, this must not
     * replace a translucent shader with a cutout shader or discard its alpha value.
     */
    public static RenderType withBackfaceCulling(RenderType source, boolean allCutout) {
        if (!allCutout || source == null || source.isOutline()
                || !(source instanceof CompositeRenderTypeAccessor composite)
                || !(source instanceof RenderTypeSortingAccessor sorting)) {
            return source;
        }
        CompositeState originalState = composite.ysmCompat$state();
        // A mod may append dynamic state shards through an access-widened subclass.
        // Rebuilding only vanilla's fields would silently lose those extensions.
        if (originalState.getClass() != CompositeState.class) {
            return source;
        }
        CompositeRenderStateAccessor.State state =
                (CompositeRenderStateAccessor.State) (Object) originalState;
        if (state.ysmCompat$cull() == CULL) {
            return source;
        }
        return CULLED.get(source, ignored -> create(
                "ysm_epicfight_compat:model_cull", source.format(), source.mode(),
                source.bufferSize(), source.affectsCrumbling(), sorting.ysmCompat$sortOnUpload(),
                copyWithCull(state, source.outline().isPresent())));
    }

    /** Copies all other shards by identity; only the back-face cull state changes. */
    private static CompositeState copyWithCull(CompositeRenderStateAccessor.State source,
                                                boolean affectsOutline) {
        return CompositeState.builder()
                .setTextureState(source.ysmCompat$texture())
                .setShaderState(source.ysmCompat$shader())
                .setTransparencyState(source.ysmCompat$transparency())
                .setDepthTestState(source.ysmCompat$depthTest())
                .setCullState(CULL)
                .setLightmapState(source.ysmCompat$lightmap())
                .setOverlayState(source.ysmCompat$overlay())
                .setLayeringState(source.ysmCompat$layering())
                .setOutputState(source.ysmCompat$output())
                .setTexturingState(source.ysmCompat$texturing())
                .setWriteMaskState(source.ysmCompat$writeMask())
                .setLineState(source.ysmCompat$line())
                .setColorLogicState(source.ysmCompat$colorLogic())
                .createCompositeState(affectsOutline);
    }

    /** Resource reload/session cleanup; entries never own GPU allocations. */
    public static void clear() {
        CULLED.clear();
    }

    /** Bounded render-thread memoization, also independently testable without GL setup. */
    static final class Cache<K, V> {
        private final int limit;
        private final Map<K, V> entries = new LinkedHashMap<>(16, 0.75F, true);

        Cache(int limit) {
            if (limit < 1) {
                throw new IllegalArgumentException("Cache limit must be positive");
            }
            this.limit = limit;
        }

        V get(K key, Function<K, V> factory) {
            V existing = entries.get(key);
            if (existing != null) {
                return existing;
            }
            V created = java.util.Objects.requireNonNull(factory.apply(key));
            entries.put(key, created);
            if (entries.size() > limit) {
                var eldest = entries.keySet().iterator();
                eldest.next();
                eldest.remove();
            }
            return created;
        }

        int size() {
            return entries.size();
        }

        void clear() {
            entries.clear();
        }
    }
}
