package net.okitsu.ysmepicfightcompat.integration.oculus;

import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.okitsu.ysmepicfightcompat.CompatMod;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** Optional LabPBR loader integration without a hard Oculus/Iris dependency. */
public final class OculusPbrBridge {
    private static final List<String> API_ROOTS = List.of(
            "net.irisshaders.iris.texture.pbr.loader",
            "net.coderbot.iris.texture.pbr.loader");
    private static final List<Object> LOADERS = new ArrayList<>();
    private static final AtomicBoolean LOAD_WARNING = new AtomicBoolean();
    private static RegistrationState state = RegistrationState.UNKNOWN;

    private OculusPbrBridge() {
    }

    /** Registers an exact-class loader once and reports whether PBR upload is available. */
    public static synchronized boolean ensureRegistered() {
        if (state != RegistrationState.UNKNOWN) {
            return state == RegistrationState.AVAILABLE;
        }
        boolean apiFound = false;
        Throwable failure = null;
        for (String root : API_ROOTS) {
            try {
                apiFound |= register(root);
                if (apiFound) {
                    state = RegistrationState.AVAILABLE;
                    return true;
                }
            } catch (ClassNotFoundException | NoClassDefFoundError ignored) {
                // Oculus/Iris is optional; try the other namespace before failing open.
            } catch (Throwable exception) {
                apiFound = true;
                failure = exception;
            }
        }
        state = RegistrationState.UNAVAILABLE;
        if (apiFound && LOAD_WARNING.compareAndSet(false, true)) {
            CompatMod.LOG.warn(
                    "YSM-EF Compat: Oculus/Iris PBR API was found but its loader could not be registered; using the base texture",
                    failure);
        }
        return false;
    }

    private static boolean register(String root) throws ReflectiveOperationException {
        ClassLoader classLoader = CompatPbrTexture.class.getClassLoader();
        Class<?> registryType = Class.forName(
                root + ".PBRTextureLoaderRegistry", true, classLoader);
        Class<?> loaderType = Class.forName(root + ".PBRTextureLoader", true, classLoader);
        Class<?> consumerType = Class.forName(
                root + ".PBRTextureLoader$PBRTextureConsumer", true, classLoader);
        Field instanceField = registryType.getField("INSTANCE");
        Object registry = instanceField.get(null);
        Method acceptNormal = consumerType.getMethod(
                "acceptNormalTexture", AbstractTexture.class);
        Method acceptSpecular = consumerType.getMethod(
                "acceptSpecularTexture", AbstractTexture.class);
        Object loader = Proxy.newProxyInstance(loaderType.getClassLoader(),
                new Class<?>[]{loaderType}, (proxy, method, arguments) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        return objectMethod(proxy, method, arguments);
                    }
                    if ("load".equals(method.getName()) && arguments != null
                            && arguments.length == 3
                            && arguments[0] instanceof CompatPbrTexture texture) {
                        load(texture, arguments[2], acceptNormal, acceptSpecular);
                    }
                    return null;
                });
        Method register = registryType.getMethod("register", Class.class, loaderType);
        register.invoke(registry, CompatPbrTexture.class, loader);
        LOADERS.add(loader);
        return true;
    }

    private static void load(CompatPbrTexture texture, Object consumer,
                             Method acceptNormal, Method acceptSpecular) {
        DynamicTexture normal = null;
        DynamicTexture specular = null;
        try {
            normal = texture.createNormalTexture();
            if (normal != null) {
                acceptNormal.invoke(consumer, normal);
                normal = null;
            }
            specular = texture.createSpecularTexture();
            if (specular != null) {
                acceptSpecular.invoke(consumer, specular);
                specular = null;
            }
        } catch (Throwable exception) {
            if (LOAD_WARNING.compareAndSet(false, true)) {
                CompatMod.LOG.warn(
                        "YSM-EF Compat: failed to provide fallback PBR textures to Oculus/Iris",
                        exception);
            }
        } finally {
            close(normal);
            close(specular);
        }
    }

    private static Object objectMethod(Object proxy, Method method, Object[] arguments) {
        return switch (method.getName()) {
            case "equals" -> proxy == (arguments == null ? null : arguments[0]);
            case "hashCode" -> System.identityHashCode(proxy);
            case "toString" -> "YSM-EF fallback PBR loader";
            default -> null;
        };
    }

    private static void close(AbstractTexture texture) {
        if (texture != null) {
            texture.close();
        }
    }

    private enum RegistrationState {
        UNKNOWN,
        AVAILABLE,
        UNAVAILABLE
    }
}
