package net.okitsu.ysmepicfightcompat.assets;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.ResourceLocation;
import net.okitsu.ysmepicfightcompat.CompatMod;
import net.okitsu.ysmmapping.api.MappingSnapshot;
import net.okitsu.ysmmapping.api.YsmMappingApi;
import net.okitsu.ysmmapping.api.YsmMethodSymbol;
import net.okitsu.ysmmapping.api.YsmSymbolKey;
import net.okitsu.ysmmapping.api.YsmSymbols;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Obtains dynamic textures from official YSM through Mapping API semantic symbols. */
public final class OfficialTextureResolver {
    private static final Set<YsmSymbolKey<?>> REQUIRED_SYMBOLS = Set.of(
            YsmSymbols.CLIENT_MODEL_LOOKUP,
            YsmSymbols.CLIENT_MODEL_DATA_GETTER,
            YsmSymbols.CLIENT_MODEL_TEXTURES_GETTER,
            YsmSymbols.CLIENT_TEXTURE_CACHE_ACQUIRE,
            YsmSymbols.CLIENT_TEXTURE_LOCATION_GETTER);

    private record Handles(MethodHandle findModel, MethodHandle data, MethodHandle textures,
                           MethodHandle acquireTexture, MethodHandle location) {
    }

    private record Lease(AbstractTexture texture, Object token) {
    }

    private static final Map<String, Lease> ACTIVE_LEASES = new ConcurrentHashMap<>();
    private static volatile Handles handles;
    private static volatile boolean resolved;

    private OfficialTextureResolver() {
    }

    public static ResourceLocation resolve(String modelId, String requestedTexture) {
        if (modelId == null || modelId.isBlank() || !RenderSystem.isOnRenderThreadOrInit()) {
            return null;
        }
        Handles access = handles();
        if (access == null) {
            return null;
        }
        String cacheKey = modelId + '#' + (requestedTexture == null ? "" : requestedTexture);
        try {
            Lease existing = ACTIVE_LEASES.get(cacheKey);
            if (existing != null) {
                return location(access, existing.token());
            }
            Object lookupResult = access.findModel().invoke(modelId);
            if (!(lookupResult instanceof Optional<?> model) || model.isEmpty()) {
                return null;
            }
            Object modelData = access.data().invoke(model.get());
            Object textureObject = access.textures().invoke(modelData);
            if (!(textureObject instanceof Map<?, ?> available)) {
                return null;
            }
            AbstractTexture selected = selectFrom(available, requestedTexture);
            if (selected == null) {
                return null;
            }
            Object token = access.acquireTexture().invoke(selected, true);
            if (token == null) {
                return null;
            }
            ACTIVE_LEASES.put(cacheKey, new Lease(selected, token));
            return location(access, token);
        } catch (Throwable exception) {
            CompatMod.LOG.warn(
                    "YSM-EF Compat: official texture lookup failed for '{}'", modelId, exception);
            return null;
        }
    }

    static AbstractTexture selectFrom(Map<?, ?> available, String requestedTexture) {
        for (String candidate : candidates(requestedTexture)) {
            Object selected = available.get(candidate);
            if (selected instanceof AbstractTexture texture) {
                return texture;
            }
        }
        for (Object selected : available.values()) {
            if (selected instanceof AbstractTexture texture) {
                return texture;
            }
        }
        return null;
    }

    public static void clear() {
        ACTIVE_LEASES.clear();
    }

    public static void release(String modelId) {
        String prefix = modelId + '#';
        ACTIVE_LEASES.keySet().removeIf(key -> key.startsWith(prefix));
    }

    private static List<String> candidates(String requestedTexture) {
        if (requestedTexture == null || requestedTexture.isBlank()) {
            return List.of();
        }
        LinkedHashSet<String> names = new LinkedHashSet<>();
        names.add(requestedTexture);
        String normalized = requestedTexture.replace('\\', '/');
        String filename = normalized.substring(normalized.lastIndexOf('/') + 1);
        names.add(filename);
        int dot = filename.lastIndexOf('.');
        names.add(dot > 0 ? filename.substring(0, dot) : filename + ".png");
        return new ArrayList<>(names);
    }

    private static ResourceLocation location(Handles access, Object token) throws Throwable {
        Object result = access.location().invoke(token);
        if (result instanceof Optional<?> optional
                && optional.orElse(null) instanceof ResourceLocation value) {
            return value;
        }
        return null;
    }

    private static Handles handles() {
        if (resolved) {
            return handles;
        }
        synchronized (OfficialTextureResolver.class) {
            if (resolved) {
                return handles;
            }
            resolved = true;
            try {
                MappingSnapshot mapping = YsmMappingApi.resolve(
                        CompatMod.MOD_ID, REQUIRED_SYMBOLS);
                handles = new Handles(
                        handle(mapping.require(YsmSymbols.CLIENT_MODEL_LOOKUP), String.class),
                        handle(mapping.require(YsmSymbols.CLIENT_MODEL_DATA_GETTER)),
                        handle(mapping.require(YsmSymbols.CLIENT_MODEL_TEXTURES_GETTER)),
                        handle(mapping.require(YsmSymbols.CLIENT_TEXTURE_CACHE_ACQUIRE),
                                AbstractTexture.class, boolean.class),
                        handle(mapping.require(YsmSymbols.CLIENT_TEXTURE_LOCATION_GETTER)));
                CompatMod.LOG.info(
                        "YSM-EF Compat: official YSM texture cache access is ready");
            } catch (Exception exception) {
                CompatMod.LOG.warn(
                        "YSM-EF Compat: official YSM texture cache is unavailable", exception);
            }
            return handles;
        }
    }

    private static MethodHandle handle(YsmMethodSymbol symbol, Class<?>... parameters)
            throws ReflectiveOperationException, IllegalAccessException {
        ClassLoader loader = OfficialTextureResolver.class.getClassLoader();
        Class<?> owner = Class.forName(symbol.owner().replace('/', '.'), false, loader);
        Method method = owner.getDeclaredMethod(symbol.name(), parameters);
        method.setAccessible(true);
        return MethodHandles.lookup().unreflect(method);
    }
}
