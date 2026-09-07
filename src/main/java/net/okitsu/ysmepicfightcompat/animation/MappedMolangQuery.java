package net.okitsu.ysmepicfightcompat.animation;

import net.okitsu.ysmmapping.api.YsmMethodSymbol;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.Optional;
import java.util.OptionalDouble;

/** Invokes an exact mapped query against an existing official context, without creating state. */
final class MappedMolangQuery {
    private final MethodHandle query;
    private final Class<?> inputType;
    private final Method contextAccessor;
    private final Class<?> contextType;

    private MappedMolangQuery(MethodHandle query, Class<?> inputType, Method contextAccessor) {
        this.query = query;
        this.inputType = inputType;
        this.contextAccessor = contextAccessor;
        this.contextType = contextAccessor.getReturnType();
    }

    /** Missing mappings, inaccessible members, and unsupported contracts all produce no binding. */
    static Optional<MappedMolangQuery> resolve(YsmMethodSymbol querySymbol,
            YsmMethodSymbol contextAccessorSymbol, ClassLoader loader) {
        if (querySymbol == null || contextAccessorSymbol == null) {
            return Optional.empty();
        }
        try {
            Class<?> queryOwner = Class.forName(querySymbol.binaryOwner(), false, loader);
            MethodType queryType = MethodType.fromMethodDescriptorString(
                    querySymbol.descriptor(), loader);
            if (queryOwner.isInterface() || queryType.parameterCount() != 1
                    || !queryType.parameterType(0).isInterface()
                    || queryType.returnType() != float.class) {
                return Optional.empty();
            }
            Method queryMethod = queryOwner.getDeclaredMethod(
                    querySymbol.name(), queryType.parameterArray());
            int queryModifiers = queryMethod.getModifiers();
            if (!Modifier.isStatic(queryModifiers) || Modifier.isAbstract(queryModifiers)
                    || Modifier.isNative(queryModifiers)
                    || queryMethod.getReturnType() != queryType.returnType()) {
                return Optional.empty();
            }

            Class<?> inputType = queryType.parameterType(0);
            Class<?> accessorOwner = Class.forName(
                    contextAccessorSymbol.binaryOwner(), false, loader);
            MethodType accessorType = MethodType.fromMethodDescriptorString(
                    contextAccessorSymbol.descriptor(), loader);
            Class<?> contextType = accessorType.returnType();
            if (!accessorOwner.isInterface() || !accessorOwner.isAssignableFrom(inputType)
                    || accessorType.parameterCount() != 0 || contextType.isPrimitive()
                    || contextType.isArray() || contextType.isInterface()) {
                return Optional.empty();
            }
            Method contextAccessor = accessorOwner.getDeclaredMethod(
                    contextAccessorSymbol.name(), accessorType.parameterArray());
            int accessorModifiers = contextAccessor.getModifiers();
            if (Modifier.isStatic(accessorModifiers) || !Modifier.isPublic(accessorModifiers)
                    || !Modifier.isAbstract(accessorModifiers)
                    || contextAccessor.getReturnType() != contextType
                    || contextAccessor.getName().equals("toString")) {
                return Optional.empty();
            }
            if (!queryMethod.trySetAccessible()) {
                return Optional.empty();
            }
            MethodHandle query = MethodHandles.lookup().unreflect(queryMethod);
            if (!query.type().equals(queryType)) {
                return Optional.empty();
            }
            return Optional.of(new MappedMolangQuery(query, inputType, contextAccessor));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            // Do not expose names, descriptors, or exception text from the runtime mapping.
            return Optional.empty();
        }
    }

    /** The returned immutable binding retains the supplied context and reuses one proxy. */
    Optional<Bound> bind(Object context) {
        if (context == null || !contextType.isInstance(context)) {
            return Optional.empty();
        }
        try {
            InvocationHandler handler = (proxy, method, arguments) -> {
                if (method.getDeclaringClass() == Object.class) {
                    return switch (method.getName()) {
                        case "equals" -> proxy == arguments[0];
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "toString" -> "MappedMolangQuery.Proxy@"
                                + Integer.toHexString(System.identityHashCode(proxy));
                        default -> throw unsupportedInputOperation();
                    };
                }
                if (isContextAccessor(method)) {
                    return context;
                }
                throw unsupportedInputOperation();
            };
            Object proxy = Proxy.newProxyInstance(inputType.getClassLoader(),
                    new Class<?>[]{inputType}, handler);
            return Optional.of(new Bound(query.bindTo(proxy)));
        } catch (RuntimeException | LinkageError unavailable) {
            return Optional.empty();
        }
    }

    private boolean isContextAccessor(Method method) {
        // A sub-interface may redeclare the same exact mapped getter. No member search occurs:
        // only the Method delivered by Proxy is checked against the supplied semantic symbol.
        Class<?> owner = method.getDeclaringClass();
        return method.getName().equals(contextAccessor.getName())
                && method.getParameterCount() == 0 && method.getReturnType() == contextType
                && owner.isInterface() && owner.isAssignableFrom(inputType)
                && contextAccessor.getDeclaringClass().isAssignableFrom(owner);
    }

    private static UnsupportedOperationException unsupportedInputOperation() {
        return new UnsupportedOperationException("Unsupported mapped Molang query input operation");
    }

    static final class Bound {
        private final MethodHandle invocation;

        private Bound(MethodHandle invocation) {
            this.invocation = invocation;
        }

        /** Missing is distinct from a legitimate finite zero or negative official result. */
        OptionalDouble sample() {
            try {
                float value = (float) invocation.invokeExact();
                return Float.isFinite(value) ? OptionalDouble.of(value) : OptionalDouble.empty();
            } catch (VirtualMachineError | ThreadDeath fatal) {
                throw fatal;
            } catch (Throwable unavailable) {
                return OptionalDouble.empty();
            }
        }
    }
}
