package net.okitsu.ysmepicfightcompat.animation;

import net.okitsu.ysmmapping.api.YsmMethodSymbol;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Optional;

/** Reads current selection fields through exact semantic mappings, without serializing a player. */
final class MappedClientModelSelection {
    private final MethodHandle modelId;
    private final MethodHandle disabled;

    private MappedClientModelSelection(MethodHandle modelId, MethodHandle disabled) {
        this.modelId = modelId;
        this.disabled = disabled;
    }

    static Optional<MappedClientModelSelection> resolve(YsmMethodSymbol modelIdSymbol,
            YsmMethodSymbol disabledSymbol, ClassLoader loader) {
        if (modelIdSymbol == null || disabledSymbol == null) return Optional.empty();
        try {
            MethodHandle modelId = getter(modelIdSymbol, String.class, loader);
            MethodHandle disabled = getter(disabledSymbol, boolean.class, loader);
            Class<?> modelOwner = modelId.type().parameterType(0);
            Class<?> disabledOwner = disabled.type().parameterType(0);
            if (!modelOwner.isAssignableFrom(disabledOwner)
                    && !disabledOwner.isAssignableFrom(modelOwner)) return Optional.empty();
            return Optional.of(new MappedClientModelSelection(modelId, disabled));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            // Runtime mapping errors may contain private identifiers.
            return Optional.empty();
        }
    }

    /** Never caches the selected ID: the same context changes when the user selects another model. */
    String modelId(Object context) {
        if (context == null || !modelId.type().parameterType(0).isInstance(context)
                || !disabled.type().parameterType(0).isInstance(context)) return null;
        try {
            if ((boolean) disabled.invoke(context)) return null;
            String selected = (String) modelId.invoke(context);
            return selected == null || selected.isBlank() ? null : selected;
        } catch (Throwable unavailable) {
            return null;
        }
    }

    private static MethodHandle getter(YsmMethodSymbol symbol, Class<?> result, ClassLoader loader)
            throws ReflectiveOperationException {
        MethodType type = MethodType.fromMethodDescriptorString(symbol.descriptor(), loader);
        if (type.parameterCount() != 0 || type.returnType() != result) {
            throw new ReflectiveOperationException("Unsupported selection getter shape");
        }
        Class<?> owner = Class.forName(symbol.binaryOwner(), false, loader);
        Method method = owner.getDeclaredMethod(symbol.name());
        if (Modifier.isStatic(method.getModifiers()) || Modifier.isAbstract(method.getModifiers())
                || Modifier.isNative(method.getModifiers()) || method.getReturnType() != result
                || !method.trySetAccessible()) {
            throw new ReflectiveOperationException("Unsupported selection getter");
        }
        return MethodHandles.lookup().unreflect(method);
    }
}
