package net.okitsu.ysmepicfightcompat.integration.tlm;

import net.minecraft.world.entity.LivingEntity;

import javax.annotation.Nullable;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.WeakHashMap;

/** Optional read-only access to the roulette state synchronized by Touhou Little Maid. */
public final class TouhouMaidAnimationStateAccess {
    public record RouletteState(String animationName, boolean playing,
                                long generation) {
        private static final RouletteState NONE = new RouletteState("", false, 0L);

        public RouletteState {
            animationName = animationName == null ? "" : animationName;
        }

        static RouletteState none() {
            return NONE;
        }
    }

    private record Accessor(MethodHandle roulettePlaying,
                            MethodHandle rouletteAnimation) {
        RouletteState read(Object source) {
            try {
                boolean playing = (boolean) roulettePlaying.invokeExact(source);
                String animation = (String) rouletteAnimation.invokeExact(source);
                return new RouletteState(animation, playing, generation(source));
            } catch (RuntimeException | LinkageError ignored) {
                return RouletteState.none();
            } catch (Error error) {
                throw error;
            } catch (Throwable ignored) {
                return RouletteState.none();
            }
        }
    }

    private static final Accessor UNSUPPORTED = new Accessor(null, null);
    private static final ClassValue<Accessor> ACCESSORS = new ClassValue<>() {
        @Override
        protected Accessor computeValue(Class<?> type) {
            return discover(type);
        }
    };
    private static final Map<Object, Long> GENERATIONS = new WeakHashMap<>();

    private TouhouMaidAnimationStateAccess() {
    }

    public static RouletteState rouletteState(@Nullable LivingEntity entity) {
        if (TouhouMaidSelectionAccess.resolve(entity) == null) {
            return RouletteState.none();
        }
        return read(entity);
    }

    /** Records a TLM play request without mutating TLM or official-YSM state. */
    public static void animationStarted(@Nullable Object source,
                                        @Nullable String animationName) {
        if (source == null || animationName == null || animationName.isBlank()) {
            return;
        }
        nextGeneration(source);
    }

    public static void release(@Nullable Object source) {
        if (source != null) {
            synchronized (GENERATIONS) {
                GENERATIONS.remove(source);
            }
        }
    }

    public static void clear() {
        synchronized (GENERATIONS) {
            GENERATIONS.clear();
        }
    }

    /** Field-only seam for tests after the entity-type gate. */
    static RouletteState readUnchecked(@Nullable Object source) {
        return source == null ? RouletteState.none() : read(source);
    }

    private static RouletteState read(Object source) {
        Accessor accessor = ACCESSORS.get(source.getClass());
        return accessor == UNSUPPORTED ? RouletteState.none()
                : accessor.read(source);
    }

    private static Accessor discover(Class<?> type) {
        try {
            Field playingField = publicInstanceField(type,
                    "rouletteAnimPlaying", boolean.class);
            Field animationField = publicInstanceField(type,
                    "rouletteAnim", String.class);
            MethodHandles.Lookup lookup = MethodHandles.publicLookup();
            return new Accessor(
                    lookup.unreflectGetter(playingField).asType(
                            MethodType.methodType(boolean.class, Object.class)),
                    lookup.unreflectGetter(animationField).asType(
                            MethodType.methodType(String.class, Object.class)));
        } catch (ReflectiveOperationException | SecurityException
                 | LinkageError ignored) {
            return UNSUPPORTED;
        }
    }

    private static Field publicInstanceField(Class<?> type, String name,
                                             Class<?> fieldType)
            throws NoSuchFieldException {
        Field field = type.getField(name);
        if (Modifier.isStatic(field.getModifiers())
                || field.getType() != fieldType) {
            throw new NoSuchFieldException(name);
        }
        return field;
    }

    private static long generation(Object source) {
        synchronized (GENERATIONS) {
            return GENERATIONS.getOrDefault(source, 0L);
        }
    }

    private static long nextGeneration(Object source) {
        synchronized (GENERATIONS) {
            long next = GENERATIONS.getOrDefault(source, 0L) + 1L;
            GENERATIONS.put(source, next);
            return next;
        }
    }
}
