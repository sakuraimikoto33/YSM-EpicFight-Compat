package net.okitsu.ysmepicfightcompat.integration.oculus;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.okitsu.ysmepicfightcompat.CompatMod;
import org.lwjgl.opengl.GL11;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntPredicate;

/** Optional LabPBR loader integration without a hard Oculus/Iris dependency. */
public final class OculusPbrBridge {
    private static final List<String> API_ROOTS = List.of(
            "net.irisshaders.iris.pbr.loader",
            "net.irisshaders.iris.texture.pbr.loader",
            "net.coderbot.iris.texture.pbr.loader");
    private static final List<Object> LOADERS = new ArrayList<>();
    private static final AtomicBoolean LOAD_WARNING = new AtomicBoolean();
    private static RegistrationState state = RegistrationState.UNKNOWN;
    private static HolderAccess holderAccess;
    private static boolean holderAccessResolved;

    record HolderAccess(Object manager, Method getHolder, Method normal, Method specular) {
        boolean hasInvalidCompanion(int textureId, boolean checkNormal, boolean checkSpecular,
                                    IntPredicate textureExists) throws ReflectiveOperationException {
            Object defaults = getHolder.invoke(manager, -1);
            Object holder = getHolder.invoke(manager, textureId);
            if (holder == null || defaults == null || holder == defaults) {
                return false;
            }
            return checkNormal && isInvalidCompanion(
                    normal.invoke(holder), normal.invoke(defaults), textureExists)
                    || checkSpecular && isInvalidCompanion(
                    specular.invoke(holder), specular.invoke(defaults), textureExists);
        }
    }

    private OculusPbrBridge() {
    }

    /** Observes existing Iris companions without loading, binding, reading, or replacing them. */
    public static boolean hasInvalidCompanion(
            AbstractTexture baseTexture, boolean checkNormal, boolean checkSpecular) {
        if (baseTexture == null || !(checkNormal || checkSpecular)
                || !RenderSystem.isOnRenderThread()) {
            return false;
        }
        HolderAccess access = holderAccess();
        if (access == null) {
            return false;
        }
        try {
            return access.hasInvalidCompanion(
                    baseTexture.getId(), checkNormal, checkSpecular, GL11::glIsTexture);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return false;
        }
    }

    static boolean isInvalidCompanion(
            Object companion, Object defaultCompanion, IntPredicate textureExists) {
        return companion != defaultCompanion && companion instanceof AbstractTexture texture
                && !textureExists.test(texture.getId());
    }

    private static synchronized HolderAccess holderAccess() {
        if (holderAccessResolved) {
            return holderAccess;
        }
        holderAccessResolved = true;
        try {
            ClassLoader loader = OculusPbrBridge.class.getClassLoader();
            Class<?> manager = Class.forName(
                    "net.irisshaders.iris.pbr.texture.PBRTextureManager", true, loader);
            Class<?> holder = Class.forName(
                    "net.irisshaders.iris.pbr.texture.PBRTextureHolder", false, loader);
            holderAccess = new HolderAccess(manager.getField("INSTANCE").get(null),
                    manager.getMethod("getHolder", int.class),
                    holder.getMethod("normalTexture"), holder.getMethod("specularTexture"));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            // Iris is optional. Missing or changed public APIs retain official rendering.
        }
        return holderAccess;
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
