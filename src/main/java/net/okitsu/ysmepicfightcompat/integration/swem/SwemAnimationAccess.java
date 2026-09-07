package net.okitsu.ysmepicfightcompat.integration.swem;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

import javax.annotation.Nullable;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Read-only optional access to SWEM's native rider animation, including remote players.
 *
 * <p>SWEM 1.20.1-1.6.6 selects a PlayerAnimator animation in its {@code swem:animations}
 * associated layer for direct player passengers. Its public rider-animation metadata
 * supplies the names below. PlayerAnimator's public getters provide the active clip
 * and native playback tick; no horse controller is advanced or modified here.
 * The native keyframe player survives ordinary ticks and loop wraps and is replaced
 * when SWEM starts another rider animation, including a repeated clip.
 */
public final class SwemAnimationAccess {
    public record Snapshot(String clipName, double elapsedSeconds, long restartToken) {
    }

    private static final String HORSE_CLASS =
            "com.alaharranhonor.swem.forge.entities.horse.SWEMHorseEntity";
    private static final String CLIENT_PLAYER_CLASS =
            "net.minecraft.client.player.AbstractClientPlayer";
    private static final String ANIMATION_API_CLASS =
            "dev.kosmx.playerAnim.minecraftApi.PlayerAnimationAccess";
    private static final ResourceLocation LAYER_ID =
            ResourceLocation.fromNamespaceAndPath("swem", "animations");
    // PlayerAnimator's KeyframeAnimationPlayer uses Object identity equality.
    private static final Map<Object, Long> RESTART_TOKENS = new WeakHashMap<>();
    private static long nextRestartToken;

    private record NativeAccess(MethodHandle associatedData, MethodHandle layer) {
        private static final NativeAccess UNSUPPORTED = new NativeAccess(null, null);
    }

    private record LayerAccess(MethodHandle animation) {
        private static final LayerAccess UNSUPPORTED = new LayerAccess(null);
    }

    private record AnimationAccess(MethodHandle active, MethodHandle tick,
                                   MethodHandle data, MethodHandle extraData) {
        private static final AnimationAccess UNSUPPORTED =
                new AnimationAccess(null, null, null, null);
    }

    private static final ClassValue<NativeAccess> NATIVE_ACCESS = new ClassValue<>() {
        @Override
        protected NativeAccess computeValue(Class<?> type) {
            return discoverNativeAccess(type);
        }
    };
    private static final ClassValue<LayerAccess> LAYER_ACCESS = new ClassValue<>() {
        @Override
        protected LayerAccess computeValue(Class<?> type) {
            try {
                Method animation = publicObjectGetter(type, "getAnimation");
                return new LayerAccess(objectGetter(animation));
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
                return LayerAccess.UNSUPPORTED;
            }
        }
    };
    private static final ClassValue<AnimationAccess> ANIMATION_ACCESS = new ClassValue<>() {
        @Override
        protected AnimationAccess computeValue(Class<?> type) {
            return discoverAnimationAccess(type);
        }
    };

    private SwemAnimationAccess() {
    }

    @Nullable
    public static Snapshot sample(@Nullable LivingEntity rider) {
        return sample(rider, 0.0F);
    }

    /** Null means no supported, active native rider clip; callers retain their fallback. */
    @Nullable
    public static Snapshot sample(@Nullable LivingEntity rider, float partialTick) {
        if (!(rider instanceof Player) || !rider.isAlive()
                || !(rider.getVehicle() instanceof LivingEntity mount)
                || !mount.isAlive() || !isSwemHorseType(mount.getClass())) {
            return null;
        }
        NativeAccess access = NATIVE_ACCESS.get(rider.getClass());
        if (access == NativeAccess.UNSUPPORTED) {
            return null;
        }
        try {
            Object data = access.associatedData.invokeExact((Object) rider);
            if (data == null) {
                return null;
            }
            Object layer = access.layer.invokeExact(data, LAYER_ID);
            return readLayer(layer, partialTick);
        } catch (RuntimeException | LinkageError ignored) {
            return null;
        } catch (Error error) {
            throw error;
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static void clear() {
        synchronized (RESTART_TOKENS) {
            RESTART_TOKENS.clear();
        }
    }

    /** Public-getter seam, after the live direct-passenger gate. */
    @Nullable
    static Snapshot readLayer(@Nullable Object layer, float partialTick) {
        if (layer == null) {
            return null;
        }
        LayerAccess access = LAYER_ACCESS.get(layer.getClass());
        if (access == LayerAccess.UNSUPPORTED) {
            return null;
        }
        try {
            return readAnimation((Object) access.animation.invokeExact(layer), partialTick);
        } catch (RuntimeException | LinkageError ignored) {
            return null;
        } catch (Error error) {
            throw error;
        } catch (Throwable ignored) {
            return null;
        }
    }

    @Nullable
    static Snapshot readAnimation(@Nullable Object animation, float partialTick) {
        if (animation == null) {
            return null;
        }
        AnimationAccess access = ANIMATION_ACCESS.get(animation.getClass());
        if (access == AnimationAccess.UNSUPPORTED) {
            return null;
        }
        try {
            if (!(boolean) access.active.invokeExact(animation)) {
                return null;
            }
            int tick = (int) access.tick.invokeExact(animation);
            Object data = access.data.invokeExact(animation);
            if (tick < 0 || data == null) {
                return null;
            }
            Map<?, ?> extra = (Map<?, ?>) access.extraData.invokeExact(data);
            Object name = extra == null ? null : extra.get("name");
            String clip = name instanceof String text ? canonicalClip(text) : null;
            if (clip == null) {
                return null;
            }
            float partial = Float.isFinite(partialTick)
                    ? Math.max(0.0F, Math.min(1.0F, partialTick)) : 0.0F;
            return new Snapshot(clip, (tick + (double) partial) / 20.0,
                    restartToken(animation));
        } catch (RuntimeException | LinkageError ignored) {
            return null;
        } catch (Error error) {
            throw error;
        } catch (Throwable ignored) {
            return null;
        }
    }

    @Nullable
    static String canonicalClip(@Nullable String nativeName) {
        if (nativeName == null) {
            return null;
        }
        return switch (nativeName.toLowerCase(Locale.ROOT)) {
            case "standidleplayer" -> "swem:idle";
            case "walkplayer" -> "swem:walk";
            case "trotplayer" -> "swem:trot";
            case "canterplayer" -> "swem:canter";
            case "extendedcanterplayer" -> "swem:canter_ext";
            case "gallopplayer" -> "swem:gallop";
            case "jumplvl1player" -> "swem:jump_lv1";
            case "jumplvl2player" -> "swem:jump_lv2";
            case "jumplvl3player" -> "swem:jump_lv3";
            case "jumplvl4player" -> "swem:jump_lv4";
            case "jumplvl5player" -> "swem:jump_lv5";
            default -> null;
        };
    }

    static boolean isSwemHorseType(@Nullable Class<?> type) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            if (HORSE_CLASS.equals(current.getName())) {
                return true;
            }
        }
        return false;
    }

    private static NativeAccess discoverNativeAccess(Class<?> riderType) {
        try {
            ClassLoader loader = riderType.getClassLoader();
            Class<?> clientPlayer = Class.forName(CLIENT_PLAYER_CLASS, false, loader);
            if (!clientPlayer.isAssignableFrom(riderType)) {
                return NativeAccess.UNSUPPORTED;
            }
            Class<?> api = Class.forName(ANIMATION_API_CLASS, false, loader);
            Method associatedData = api.getMethod("getPlayerAssociatedData", clientPlayer);
            if (!Modifier.isStatic(associatedData.getModifiers())
                    || associatedData.getReturnType().isPrimitive()) {
                return NativeAccess.UNSUPPORTED;
            }
            Method layer = associatedData.getReturnType().getMethod("get", ResourceLocation.class);
            if (Modifier.isStatic(layer.getModifiers()) || layer.getReturnType().isPrimitive()) {
                return NativeAccess.UNSUPPORTED;
            }
            MethodHandles.Lookup lookup = MethodHandles.publicLookup();
            return new NativeAccess(
                    lookup.unreflect(associatedData).asType(
                            MethodType.methodType(Object.class, Object.class)),
                    lookup.unreflect(layer).asType(
                            MethodType.methodType(Object.class, Object.class, ResourceLocation.class)));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return NativeAccess.UNSUPPORTED;
        }
    }

    private static AnimationAccess discoverAnimationAccess(Class<?> type) {
        try {
            Method active = publicGetter(type, "isActive", boolean.class);
            Method tick = publicGetter(type, "getTick", int.class);
            Method data = publicObjectGetter(type, "getData");
            Field extra = data.getReturnType().getField("extraData");
            if (Modifier.isStatic(extra.getModifiers())
                    || !Map.class.isAssignableFrom(extra.getType())) {
                return AnimationAccess.UNSUPPORTED;
            }
            MethodHandles.Lookup lookup = MethodHandles.publicLookup();
            return new AnimationAccess(
                    lookup.unreflect(active).asType(
                            MethodType.methodType(boolean.class, Object.class)),
                    lookup.unreflect(tick).asType(
                            MethodType.methodType(int.class, Object.class)),
                    objectGetter(data), lookup.unreflectGetter(extra).asType(
                            MethodType.methodType(Map.class, Object.class)));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return AnimationAccess.UNSUPPORTED;
        }
    }

    private static Method publicGetter(Class<?> type, String name, Class<?> returnType)
            throws NoSuchMethodException {
        Method method = type.getMethod(name);
        if (Modifier.isStatic(method.getModifiers()) || method.getReturnType() != returnType) {
            throw new NoSuchMethodException(name);
        }
        return method;
    }

    private static Method publicObjectGetter(Class<?> type, String name)
            throws NoSuchMethodException {
        Method method = type.getMethod(name);
        if (Modifier.isStatic(method.getModifiers()) || method.getReturnType().isPrimitive()) {
            throw new NoSuchMethodException(name);
        }
        return method;
    }

    private static MethodHandle objectGetter(Method method) throws IllegalAccessException {
        return MethodHandles.publicLookup().unreflect(method).asType(
                MethodType.methodType(Object.class, Object.class));
    }

    private static long restartToken(Object animation) {
        synchronized (RESTART_TOKENS) {
            return RESTART_TOKENS.computeIfAbsent(animation, ignored -> ++nextRestartToken);
        }
    }
}
