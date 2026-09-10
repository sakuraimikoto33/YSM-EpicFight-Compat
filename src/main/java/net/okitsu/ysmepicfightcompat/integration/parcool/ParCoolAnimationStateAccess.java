package net.okitsu.ysmepicfightcompat.integration.parcool;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Optional, read-only view of ParCool's native client animator, including remote players.
 *
 * <p>The public attachment getters and removal predicate are preferred. ParCool has no
 * public animator getter or directional getters; the few named members below are from
 * its public 1.21.1 NeoForge implementation, not official-YSM internals:
 * https://github.com/alRex-U/ParCool
 * No animator methods that update the pose, tick, attachment, or event options are called.
 */
public final class ParCoolAnimationStateAccess {
    public record Snapshot(String clipName, double elapsedSeconds, long generation) {
    }

    private static final String ANIMATION = "com.alrex.parcool.common.attachment.client.Animation";
    private static final String PARKOUR = "com.alrex.parcool.common.attachment.common.Parkourability";
    private static final String ANIMATOR = "com.alrex.parcool.client.animation.Animator";
    private static final String ACTION_PACKAGE = "com.alrex.parcool.common.action.impl.";
    private static final Map<Object, Generation> GENERATIONS = new WeakHashMap<>();

    private static final class Generation {
        private WeakReference<Object> animator = new WeakReference<>(null);
        private int tick = -1;
        private long value;
    }

    private static final class RuntimeHolder {
        @Nullable
        private static final RuntimeAccess ACCESS = discoverRuntime();
    }

    private static final ClassValue<ClipAccess> CLIPS = new ClassValue<>() {
        @Override
        protected ClipAccess computeValue(Class<?> type) {
            String name = type.getName();
            if (!name.startsWith(ParCoolAnimationClips.ANIMATOR_PACKAGE)) {
                return ClipAccess.UNSUPPORTED;
            }
            return discoverClip(type,
                    name.substring(ParCoolAnimationClips.ANIMATOR_PACKAGE.length()));
        }
    };

    private ParCoolAnimationStateAccess() {
    }

    /** Null means absence, unsupported API/animator, or a finished native animation. */
    @Nullable
    public static Snapshot sample(@Nullable LivingEntity entity, float partialTick) {
        if (!(entity instanceof Player player)) {
            return null;
        }
        RuntimeAccess access = RuntimeHolder.ACCESS;
        if (access == null) {
            return null;
        }
        try {
            Object animation = access.animationGet.invokeExact((Object) player);
            Object parkour = access.parkourGet.invokeExact((Object) player);
            if (animation == null || parkour == null) {
                return null;
            }
            Object animator = access.animatorGet.invokeExact(animation);
            if (animator == null) {
                return null;
            }
            return readAnimator(player, animator, parkour, player.yBodyRot, partialTick,
                    access, CLIPS.get(animator.getClass()));
        } catch (RuntimeException | LinkageError ignored) {
            return null;
        } catch (Error error) {
            throw error;
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static void release(@Nullable Object entity) {
        synchronized (GENERATIONS) {
            GENERATIONS.remove(entity);
        }
    }

    public static void clear() {
        synchronized (GENERATIONS) {
            GENERATIONS.clear();
        }
    }

    /** Package-private seam exercises native lifetime/clock/reflection without Minecraft. */
    @Nullable
    static Snapshot readAnimator(Object player, Object animator, Object parkour,
                                 float bodyYaw, float partialTick, RuntimeAccess access,
                                 ClipAccess clipAccess) {
        if (clipAccess == ClipAccess.UNSUPPORTED) {
            return null;
        }
        try {
            if ((boolean) access.shouldRemove.invokeExact(animator, player, parkour)) {
                return null;
            }
            int tick = (int) access.tickGet.invokeExact(animator);
            if (tick < 0) {
                return null;
            }
            String clip = clipAccess.read(animator, parkour, bodyYaw, access);
            if (clip == null) {
                return null;
            }
            double fraction = Float.isFinite(partialTick)
                    ? Math.max(0.0D, Math.min(1.0D, partialTick)) : 0.0D;
            return new Snapshot(clip, (tick + fraction) / 20.0D,
                    generation(player, animator, tick));
        } catch (RuntimeException | LinkageError ignored) {
            return null;
        } catch (Error error) {
            throw error;
        } catch (Throwable ignored) {
            return null;
        }
    }

    static final class RuntimeAccess {
        private final MethodHandle animationGet;
        private final MethodHandle parkourGet;
        private final MethodHandle animatorGet;
        private final MethodHandle tickGet;
        private final MethodHandle shouldRemove;
        private final MethodHandle actionGet;

        private RuntimeAccess(MethodHandle animationGet, MethodHandle parkourGet,
                              MethodHandle animatorGet, MethodHandle tickGet,
                              MethodHandle shouldRemove, MethodHandle actionGet) {
            this.animationGet = animationGet;
            this.parkourGet = parkourGet;
            this.animatorGet = animatorGet;
            this.tickGet = tickGet;
            this.shouldRemove = shouldRemove;
            this.actionGet = actionGet;
        }

        /** Also used by fixtures whose public attachment shapes match the optional API. */
        static RuntimeAccess discover(Class<?> animation, Class<?> parkour,
                                      Class<?> animator, Class<?> player)
                throws ReflectiveOperationException {
            MethodHandles.Lookup lookup = MethodHandles.publicLookup();
            Method getAnimation = animation.getMethod("get", player);
            Method getParkour = parkour.getMethod("get", player);
            if (!Modifier.isStatic(getAnimation.getModifiers())
                    || getAnimation.getReturnType() != animation
                    || !Modifier.isStatic(getParkour.getModifiers())
                    || getParkour.getReturnType() != parkour) {
                throw new NoSuchMethodException("ParCool attachment getter shape");
            }
            Method tick = animator.getDeclaredMethod("getTick");
            if (tick.getReturnType() != int.class || Modifier.isStatic(tick.getModifiers())
                    || !tick.trySetAccessible()) {
                throw new NoSuchMethodException("ParCool animator clock shape");
            }
            return new RuntimeAccess(
                    lookup.unreflect(getAnimation).asType(objectMethod(Object.class)),
                    lookup.unreflect(getParkour).asType(objectMethod(Object.class)),
                    fieldGetter(animation, "animator", animator),
                    MethodHandles.lookup().unreflect(tick).asType(objectMethod(int.class)),
                    lookup.unreflect(animator.getMethod("shouldRemoved", player, parkour))
                            .asType(MethodType.methodType(boolean.class,
                                    Object.class, Object.class, Object.class)),
                    lookup.unreflect(parkour.getMethod("get", Class.class)).asType(
                            MethodType.methodType(Object.class, Object.class, Class.class)));
        }
    }

    static final class ClipAccess {
        private static final ClipAccess UNSUPPORTED = new ClipAccess("", null, null, null);
        private final String name;
        @Nullable
        private final String simple;
        @Nullable
        private final MethodHandle variantGet;
        @Nullable
        private final Class<?> actionType;

        private ClipAccess(String name, @Nullable String simple,
                           @Nullable MethodHandle variantGet, @Nullable Class<?> actionType) {
            this.name = name;
            this.simple = simple;
            this.variantGet = variantGet;
            this.actionType = actionType;
        }

        @Nullable
        private String read(Object animator, Object parkour, float bodyYaw,
                            RuntimeAccess access) throws Throwable {
            if (simple != null) {
                return simple;
            }
            Object source = actionType == null ? animator
                    : access.actionGet.invokeExact(parkour, actionType);
            if (source == null || variantGet == null) {
                return null;
            }
            Object variant = variantGet.invokeExact(source);
            String nameValue;
            if (variant instanceof Enum<?> value) {
                nameValue = value.name();
            } else if (variant instanceof Boolean value) {
                nameValue = "HangAnimator".equals(name)
                        ? (value ? "Across" : "Along") : (value ? "Right" : "Left");
            } else if (variant instanceof Vec3 wall && "WallSlideAnimator".equals(name)) {
                nameValue = wallSide(bodyYaw, wall.x, wall.z);
            } else {
                return null;
            }
            return ParCoolAnimationClips.variantClip(name, nameValue);
        }
    }

    @Nullable
    private static RuntimeAccess discoverRuntime() {
        try {
            ClassLoader loader = ParCoolAnimationStateAccess.class.getClassLoader();
            return RuntimeAccess.discover(Class.forName(ANIMATION, false, loader),
                    Class.forName(PARKOUR, false, loader),
                    Class.forName(ANIMATOR, false, loader), Player.class);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    static ClipAccess discoverClip(Class<?> type, String name) {
        try {
            String simple = ParCoolAnimationClips.simpleClip(name);
            if (simple != null) {
                return new ClipAccess(name, simple, null, null);
            }
            return switch (name) {
                case "DodgeAnimator", "RollAnimator", "FlippingAnimator" ->
                        new ClipAccess(name, null, enumFieldGetter(type, "direction"), null);
                case "SpeedVaultAnimator" ->
                        new ClipAccess(name, null, enumFieldGetter(type, "type"), null);
                case "HorizontalWallRunAnimator" -> new ClipAccess(name, null,
                        fieldGetter(type, "wallIsRightSide", boolean.class), null);
                case "WallJumpAnimator" -> new ClipAccess(name, null,
                        fieldGetter(type, "wallRightSide", boolean.class), null);
                case "ClingToCliffAnimator" -> actionClip(type, name,
                        "ClingToCliff", "getFacingDirection", Enum.class);
                case "HangAnimator" -> actionClip(type, name,
                        "HangDown", "isOrthogonalToBar", boolean.class);
                case "WallSlideAnimator" -> actionClip(type, name,
                        "WallSlide", "getLeanedWallDirection", Vec3.class);
                // Crawl has no parcool clip; unknown/new native animators retain Epic Fight.
                default -> ClipAccess.UNSUPPORTED;
            };
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return ClipAccess.UNSUPPORTED;
        }
    }

    private static ClipAccess actionClip(Class<?> animatorType, String name,
                                         String actionName, String getter,
                                         Class<?> expectedReturn)
            throws ReflectiveOperationException {
        Class<?> action = Class.forName(ACTION_PACKAGE + actionName,
                false, animatorType.getClassLoader());
        Method method = action.getMethod(getter);
        if (Modifier.isStatic(method.getModifiers())
                || (expectedReturn == Enum.class ? !method.getReturnType().isEnum()
                : method.getReturnType() != expectedReturn)) {
            throw new NoSuchMethodException(getter);
        }
        return new ClipAccess(name, null, MethodHandles.publicLookup().unreflect(method)
                .asType(objectMethod(Object.class)), action);
    }

    private static MethodHandle enumFieldGetter(Class<?> type, String fieldName)
            throws ReflectiveOperationException {
        Class<?> fieldType = type.getDeclaredField(fieldName).getType();
        if (!fieldType.isEnum()) {
            throw new NoSuchFieldException(fieldName);
        }
        return fieldGetter(type, fieldName, fieldType);
    }

    private static MethodHandle fieldGetter(Class<?> type, String fieldName,
                                            Class<?> fieldType)
            throws ReflectiveOperationException {
        Field field = type.getDeclaredField(fieldName);
        if (Modifier.isStatic(field.getModifiers()) || field.getType() != fieldType
                || !field.trySetAccessible()) {
            throw new NoSuchFieldException(fieldName);
        }
        return MethodHandles.lookup().unreflectGetter(field).asType(objectMethod(Object.class));
    }

    private static MethodType objectMethod(Class<?> returnType) {
        return MethodType.methodType(returnType, Object.class);
    }

    /** Right/left relative to the player's body, using ParCool's public leaned-wall vector. */
    @Nullable
    static String wallSide(float bodyYaw, double wallX, double wallZ) {
        if (!Float.isFinite(bodyYaw) || !Double.isFinite(wallX)
                || !Double.isFinite(wallZ) || Math.hypot(wallX, wallZ) < 1.0E-8D) {
            return null;
        }
        double angle = Math.toRadians(bodyYaw);
        double right = Math.cos(angle) * wallX + Math.sin(angle) * wallZ;
        return right < 0.0D ? "Right" : "Left";
    }

    private static long generation(Object player, Object animator, int tick) {
        synchronized (GENERATIONS) {
            Generation state = GENERATIONS.computeIfAbsent(player, ignored -> new Generation());
            if (state.animator.get() != animator || tick < state.tick) {
                state.animator = new WeakReference<>(animator);
                state.value++;
            }
            state.tick = tick;
            return state.value;
        }
    }
}
