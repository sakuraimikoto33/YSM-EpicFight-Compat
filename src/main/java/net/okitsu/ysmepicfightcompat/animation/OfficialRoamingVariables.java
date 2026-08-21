package net.okitsu.ysmepicfightcompat.animation;

import net.minecraft.client.Minecraft;
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
import java.lang.invoke.MethodType;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** Reads the live animation-variable providers already maintained by official YSM. */
public final class OfficialRoamingVariables {
    private static final Set<YsmSymbolKey<?>> REQUIRED_SYMBOLS = Set.of(
            YsmSymbols.PLAYER_STATE_ACTIVE_ANIMATION_GETTER,
            YsmSymbols.PLAYER_STATE_ANIMATION_PLAYING_GETTER,
            YsmSymbols.PLAYER_STATE_ANIMATION_STOP_PACKET_FACTORY,
            YsmSymbols.PLAYER_STATE_ANIMATION_STOP_SENDER,
            YsmSymbols.PLAYER_STATE_ROAMING_PROVIDER_GETTER,
            YsmSymbols.PLAYER_STATE_ROAMING_VALUE_GETTER,
            YsmSymbols.PLAYER_STATE_ROAMING_VALUE_SETTER,
            YsmSymbols.PLAYER_STATE_ROAMING_NAME_HASHER);
    private static final View MISSING_VIEW = new View(null, null);
    private static final Map<UUID, WeakReference<Object>> CAPABILITIES =
            new ConcurrentHashMap<>();
    private static final AtomicBoolean REGISTRATION_LOGGED = new AtomicBoolean();
    private static final AtomicBoolean READ_FAILURE_LOGGED = new AtomicBoolean();
    private static final AtomicBoolean STOP_FAILURE_LOGGED = new AtomicBoolean();

    public record RouletteState(String animationName, boolean playing) {
        public static final RouletteState NONE = new RouletteState("", false);

        public RouletteState {
            animationName = animationName == null ? "" : animationName;
        }
    }

    private record Access(MethodHandle activeAnimationGetter,
                          MethodHandle animationPlayingGetter,
                          MethodHandle animationStopPacketFactory,
                          MethodHandle animationStopSender,
                          MethodHandle providerGetter, MethodHandle valueGetter,
                          MethodHandle valueSetter,
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

        boolean writeRoaming(String variableName, double value) {
            if (access == null || provider == null
                    || !RoamingVariableLookup.isRoaming(variableName)) {
                return false;
            }
            try {
                int hash = access.lookup().roamingHash(variableName);
                float finiteValue = (float) value;
                if (!Float.isFinite(finiteValue)) {
                    finiteValue = 0.0F;
                }
                access.valueSetter().invoke(provider, hash, Float.valueOf(finiteValue));
                return true;
            } catch (Throwable exception) {
                logReadFailure(exception);
                return false;
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
            OfficialConfigurationVariables.reset(player);
            CAPABILITIES.put(player.getUUID(), new WeakReference<>(capability));
            if (REGISTRATION_LOGGED.compareAndSet(false, true)) {
                CompatMod.LOG.info(
                        "YSM-EF Compat: official YSM live animation state was captured");
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

    /** Reads the official YSM roulette state already synchronized for this player. */
    public static RouletteState rouletteState(LivingEntity entity) {
        Object capability = capability(entity);
        Access current = capability == null ? null : access();
        if (current == null) {
            return RouletteState.NONE;
        }
        try {
            Object name = current.activeAnimationGetter().invoke(capability);
            Object playing = current.animationPlayingGetter().invoke(capability);
            return new RouletteState(name instanceof String value ? value : "",
                    playing instanceof Boolean value && value);
        } catch (Throwable exception) {
            logReadFailure(exception);
            return RouletteState.NONE;
        }
    }

    /** Uses official YSM's own packet path to finish a local one-shot animation. */
    public static void stopLocalRouletteAnimation(LivingEntity entity) {
        if (entity == null || entity != Minecraft.getInstance().player) {
            return;
        }
        Access current = access();
        if (current == null) {
            return;
        }
        try {
            Object packet = current.animationStopPacketFactory().invoke();
            current.animationStopSender().invoke(packet);
        } catch (Throwable exception) {
            if (STOP_FAILURE_LOGGED.compareAndSet(false, true)) {
                CompatMod.LOG.warn(
                        "YSM-EF Compat: official roulette stop could not be sent",
                        exception);
            }
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
                MethodHandle activeAnimationGetter = instanceMethod(
                        mapping.require(YsmSymbols.PLAYER_STATE_ACTIVE_ANIMATION_GETTER));
                MethodHandle animationPlayingGetter = instanceMethod(
                        mapping.require(YsmSymbols.PLAYER_STATE_ANIMATION_PLAYING_GETTER));
                MethodHandle animationStopPacketFactory = staticMethod(
                        mapping.require(YsmSymbols.PLAYER_STATE_ANIMATION_STOP_PACKET_FACTORY));
                MethodHandle animationStopSender = staticMethod(
                        mapping.require(YsmSymbols.PLAYER_STATE_ANIMATION_STOP_SENDER));
                if (animationStopPacketFactory.type().parameterCount() != 0
                        || animationStopPacketFactory.type().returnType() == void.class
                        || animationStopSender.type().parameterCount() != 1
                        || animationStopSender.type().returnType() != void.class) {
                    throw new ReflectiveOperationException(
                            "Official YSM roulette stop path does not match its semantic contract");
                }
                MethodHandle providerGetter = instanceMethod(
                        mapping.require(YsmSymbols.PLAYER_STATE_ROAMING_PROVIDER_GETTER));
                MethodHandle valueGetter = instanceMethod(
                        mapping.require(YsmSymbols.PLAYER_STATE_ROAMING_VALUE_GETTER),
                        int.class);
                MethodHandle valueSetter = instanceMethod(
                        mapping.require(YsmSymbols.PLAYER_STATE_ROAMING_VALUE_SETTER),
                        int.class, Object.class);
                MethodHandle hasher = staticHasher(
                        mapping.require(YsmSymbols.PLAYER_STATE_ROAMING_NAME_HASHER));
                access = new Access(activeAnimationGetter, animationPlayingGetter,
                        animationStopPacketFactory, animationStopSender,
                        providerGetter, valueGetter, valueSetter,
                        new RoamingVariableLookup(name -> invokeHasher(hasher, name)));
                CompatMod.LOG.info(
                        "YSM-EF Compat: official YSM live animation-variable bridge is ready");
            } catch (Exception exception) {
                CompatMod.LOG.warn(
                        "YSM-EF Compat: official YSM animation variables are unavailable",
                        exception);
            }
            return access;
        }
    }

    private static Object capability(LivingEntity entity) {
        if (!(entity instanceof Player player)) {
            return null;
        }
        WeakReference<Object> reference = CAPABILITIES.get(player.getUUID());
        Object capability = reference == null ? null : reference.get();
        if (capability == null && reference != null) {
            CAPABILITIES.remove(player.getUUID(), reference);
        }
        return capability;
    }

    private static Object invokeValue(MethodHandle getter, Object provider, int hash) {
        try {
            return getter.invoke(provider, hash);
        } catch (Throwable exception) {
            throw new IllegalStateException("Official YSM animation variable lookup failed",
                    exception);
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
                    "Official YSM animation-variable accessor is unexpectedly static");
        }
        method.setAccessible(true);
        return MethodHandles.lookup().unreflect(method);
    }

    private static MethodHandle staticHasher(YsmMethodSymbol symbol)
            throws ReflectiveOperationException, IllegalAccessException {
        Method method = owner(symbol.owner()).getDeclaredMethod(symbol.name(), String.class);
        if (!Modifier.isStatic(method.getModifiers()) || method.getReturnType() != int.class) {
            throw new ReflectiveOperationException(
                    "Official YSM variable hasher does not match its semantic contract");
        }
        method.setAccessible(true);
        return MethodHandles.lookup().unreflect(method);
    }

    private static MethodHandle staticMethod(YsmMethodSymbol symbol)
            throws ReflectiveOperationException, IllegalAccessException {
        ClassLoader loader = OfficialRoamingVariables.class.getClassLoader();
        Class<?> owner = owner(symbol.owner());
        MethodType type = MethodType.fromMethodDescriptorString(symbol.descriptor(), loader);
        Method method = owner.getDeclaredMethod(symbol.name(), type.parameterArray());
        if (!Modifier.isStatic(method.getModifiers())
                || method.getReturnType() != type.returnType()) {
            throw new ReflectiveOperationException(
                    "Official YSM static animation method does not match its mapping");
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
            CompatMod.LOG.warn("YSM-EF Compat: official animation variable could not be read",
                    exception);
        }
    }
}
