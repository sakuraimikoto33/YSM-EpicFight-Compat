package net.okitsu.ysmepicfightcompat.animation;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.okitsu.ysmepicfightcompat.CompatMod;
import net.okitsu.ysmmapping.api.MappingSnapshot;
import net.okitsu.ysmmapping.api.YsmMappingApi;
import net.okitsu.ysmmapping.api.YsmMethodSymbol;
import net.okitsu.ysmmapping.api.YsmSymbolKey;
import net.okitsu.ysmmapping.api.YsmSymbols;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** Reads the live roaming provider already maintained by official YSM. */
public final class OfficialRoamingVariables {
    private static final Set<YsmSymbolKey<?>> REQUIRED_SYMBOLS = Set.of(
            YsmSymbols.PLAYER_STATE_ROAMING_PROVIDER_GETTER,
            YsmSymbols.PLAYER_STATE_ROAMING_VALUE_GETTER,
            YsmSymbols.PLAYER_STATE_ROAMING_NAME_HASHER);
    private static final View MISSING_VIEW = new View(null, null);
    private static final Map<UUID, WeakReference<Object>> CAPABILITIES =
            new ConcurrentHashMap<>();
    private static final AtomicBoolean REGISTRATION_LOGGED = new AtomicBoolean();
    private static final AtomicBoolean READ_FAILURE_LOGGED = new AtomicBoolean();

    private record Access(MethodHandle providerGetter, MethodHandle valueGetter,
                          RoamingVariableLookup lookup) {
    }

    static final class View {
        private final Access access;
        private final Object provider;

        private View(Access access, Object provider) {
            this.access = access;
            this.provider = provider;
        }

        RoamingVariableLookup.Lookup lookup(String variableName) {
            if (access == null || provider == null) {
                return RoamingVariableLookup.Lookup.missing();
            }
            try {
                return access.lookup().lookup(variableName,
                        hash -> invokeValue(access.valueGetter(), provider, hash));
            } catch (RuntimeException exception) {
                logReadFailure(exception);
                return RoamingVariableLookup.Lookup.missing();
            }
        }
    }

    private static volatile Access access;
    private static volatile boolean resolved;

    private OfficialRoamingVariables() {
    }

    /** Called by the mapped official YSM player-state constructor Mixin. */
    public static void register(Player player, Object capability) {
        if (player != null && capability != null) {
            CAPABILITIES.put(player.getUUID(), new WeakReference<>(capability));
            if (REGISTRATION_LOGGED.compareAndSet(false, true)) {
                CompatMod.LOG.info(
                        "YSM-EF Compat: official YSM live roaming holder was captured");
            }
        }
    }

    static View view(LivingEntity entity) {
        if (!(entity instanceof Player player)) {
            return MISSING_VIEW;
        }
        WeakReference<Object> reference = CAPABILITIES.get(player.getUUID());
        Object capability = reference == null ? null : reference.get();
        if (capability == null) {
            if (reference != null) {
                CAPABILITIES.remove(player.getUUID(), reference);
            }
            return MISSING_VIEW;
        }
        Access current = access();
        if (current == null) {
            return MISSING_VIEW;
        }
        try {
            Object provider = current.providerGetter().invoke(capability);
            return provider == null ? MISSING_VIEW : new View(current, provider);
        } catch (Throwable exception) {
            logReadFailure(exception);
            return MISSING_VIEW;
        }
    }

    public static void clear() {
        CAPABILITIES.clear();
    }

    private static Access access() {
        if (resolved) {
            return access;
        }
        synchronized (OfficialRoamingVariables.class) {
            if (resolved) {
                return access;
            }
            resolved = true;
            try {
                MappingSnapshot mapping = YsmMappingApi.resolve(
                        CompatMod.MOD_ID, REQUIRED_SYMBOLS);
                MethodHandle providerGetter = instanceMethod(
                        mapping.require(YsmSymbols.PLAYER_STATE_ROAMING_PROVIDER_GETTER));
                MethodHandle valueGetter = instanceMethod(
                        mapping.require(YsmSymbols.PLAYER_STATE_ROAMING_VALUE_GETTER),
                        int.class);
                MethodHandle hasher = staticHasher(
                        mapping.require(YsmSymbols.PLAYER_STATE_ROAMING_NAME_HASHER));
                access = new Access(providerGetter, valueGetter,
                        new RoamingVariableLookup(name -> invokeHasher(hasher, name)));
                CompatMod.LOG.info(
                        "YSM-EF Compat: official YSM live roaming bridge is ready");
            } catch (Exception exception) {
                CompatMod.LOG.warn(
                        "YSM-EF Compat: official YSM roaming state is unavailable", exception);
            }
            return access;
        }
    }

    private static Object invokeValue(MethodHandle getter, Object provider, int hash) {
        try {
            return getter.invoke(provider, hash);
        } catch (Throwable exception) {
            throw new IllegalStateException("Official YSM roaming value lookup failed", exception);
        }
    }

    private static int invokeHasher(MethodHandle hasher, String name) {
        try {
            return (int) hasher.invoke(name);
        } catch (Throwable exception) {
            throw new IllegalStateException("Official YSM roaming hash failed", exception);
        }
    }

    private static MethodHandle instanceMethod(YsmMethodSymbol symbol, Class<?>... parameters)
            throws ReflectiveOperationException, IllegalAccessException {
        Method method = owner(symbol.owner()).getDeclaredMethod(symbol.name(), parameters);
        if (Modifier.isStatic(method.getModifiers())) {
            throw new ReflectiveOperationException(
                    "Official YSM roaming accessor is unexpectedly static");
        }
        method.setAccessible(true);
        return MethodHandles.lookup().unreflect(method);
    }

    private static MethodHandle staticHasher(YsmMethodSymbol symbol)
            throws ReflectiveOperationException, IllegalAccessException {
        Method method = owner(symbol.owner()).getDeclaredMethod(symbol.name(), String.class);
        if (!Modifier.isStatic(method.getModifiers()) || method.getReturnType() != int.class) {
            throw new ReflectiveOperationException(
                    "Official YSM roaming hasher does not match its semantic contract");
        }
        method.setAccessible(true);
        return MethodHandles.lookup().unreflect(method);
    }

    private static Class<?> owner(String internalName) throws ClassNotFoundException {
        return Class.forName(internalName.replace('/', '.'), false,
                OfficialRoamingVariables.class.getClassLoader());
    }

    private static void logReadFailure(Throwable exception) {
        if (READ_FAILURE_LOGGED.compareAndSet(false, true)) {
            CompatMod.LOG.warn("YSM-EF Compat: official roaming value could not be read",
                    exception);
        }
    }
}
