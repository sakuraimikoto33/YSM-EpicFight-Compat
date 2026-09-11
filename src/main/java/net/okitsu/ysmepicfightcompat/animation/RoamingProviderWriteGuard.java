package net.okitsu.ysmepicfightcompat.animation;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/** Restricts animation-owned writes without changing the synchronized backing provider. */
final class RoamingProviderWriteGuard {
    private RoamingProviderWriteGuard() { }

    static Object wrap(Class<?> providerType, Object delegate, Method valueSetter,
                       BooleanSupplier suppressWrites) {
        Objects.requireNonNull(providerType, "providerType");
        Objects.requireNonNull(delegate, "delegate");
        Objects.requireNonNull(valueSetter, "valueSetter");
        Objects.requireNonNull(suppressWrites, "suppressWrites");
        if (!providerType.isInterface() || !Modifier.isPublic(providerType.getModifiers())
                || !providerType.isInstance(delegate)
                || !valueSetter.getDeclaringClass().isAssignableFrom(providerType)
                || !Modifier.isPublic(valueSetter.getModifiers())
                || Modifier.isStatic(valueSetter.getModifiers())
                || valueSetter.getReturnType() != void.class
                || valueSetter.getParameterCount() != 2
                || valueSetter.getParameterTypes()[0] != int.class
                || valueSetter.getParameterTypes()[1] != Object.class) {
            throw new IllegalArgumentException("Roaming provider does not match its write contract");
        }
        return Proxy.newProxyInstance(providerType.getClassLoader(), new Class<?>[]{providerType},
                (proxy, method, arguments) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        return switch (method.getName()) {
                            case "equals" -> proxy == arguments[0];
                            case "hashCode" -> System.identityHashCode(proxy);
                            case "toString" -> "RoamingProviderWriteGuard";
                            default -> throw new IllegalStateException("Unexpected Object method");
                        };
                    }
                    if (method.equals(valueSetter) && suppressWrites.getAsBoolean()) return null;
                    Object result;
                    try {
                        result = method.invoke(delegate, arguments);
                    } catch (InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                    // The native copy method returns an independent container, not this proxy.
                    return result;
                });
    }

}
