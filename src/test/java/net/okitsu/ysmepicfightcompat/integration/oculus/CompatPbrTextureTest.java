package net.okitsu.ysmepicfightcompat.integration.oculus;

import com.mojang.blaze3d.platform.NativeImage;
import org.junit.jupiter.api.Test;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class CompatPbrTextureTest {
    @Test
    void registersOnceWithThePublishedShaderLoaderApi() throws Exception {
        String configuredJar = System.getenv("YSM_EF_SHADER_TEST_JAR");
        assumeTrue(configuredJar != null && !configuredJar.isBlank(),
                "YSM_EF_SHADER_TEST_JAR is not configured");
        Path shaderJar = Path.of(configuredJar).toAbsolutePath().normalize();
        assertTrue(Files.isRegularFile(shaderJar), "Configured shader fixture must exist");

        try (URLClassLoader classes = isolatedBridge(shaderJar)) {
            Class<?> bridge = classes.loadClass(OculusPbrBridge.class.getName());
            var register = bridge.getMethod("ensureRegistered");
            assertEquals(true, register.invoke(null));

            Class<?> registryType = classes.loadClass(
                    "net.irisshaders.iris.pbr.loader.PBRTextureLoaderRegistry");
            Object registry = registryType.getField("INSTANCE").get(null);
            Class<?> textureType = classes.loadClass(CompatPbrTexture.class.getName());
            var getLoader = registryType.getMethod("getLoader", Class.class);
            Object loader = getLoader.invoke(registry, textureType);
            assertNotNull(loader, "The published registry must recognize our exact texture class");
            assertEquals(true, register.invoke(null));
            assertSame(loader, getLoader.invoke(registry, textureType));
        }
    }

    @Test
    void missingShaderApiLeavesBaseTextureRenderingAvailable() throws Exception {
        try (URLClassLoader classes = isolatedBridge(null)) {
            Class<?> bridge = classes.loadClass(OculusPbrBridge.class.getName());
            var register = bridge.getMethod("ensureRegistered");
            assertFalse((boolean) register.invoke(null));
            assertFalse((boolean) register.invoke(null));
        }
    }

    /** Loads the real bridge and optional shader JAR without a game or GL context. */
    private static URLClassLoader isolatedBridge(Path shaderJar) throws Exception {
        URL bridgeClasses = OculusPbrBridge.class.getProtectionDomain()
                .getCodeSource().getLocation();
        URL[] urls = shaderJar == null ? new URL[]{bridgeClasses}
                : new URL[]{bridgeClasses, shaderJar.toUri().toURL()};
        return new URLClassLoader(urls, CompatPbrTextureTest.class.getClassLoader()) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve)
                    throws ClassNotFoundException {
                synchronized (getClassLoadingLock(name)) {
                    boolean owned = name.startsWith("net.okitsu.ysmepicfightcompat.integration.oculus.")
                            || name.startsWith("net.irisshaders.iris.")
                            || name.startsWith("net.coderbot.iris.");
                    if (!owned) {
                        return super.loadClass(name, resolve);
                    }
                    Class<?> loaded = findLoadedClass(name);
                    if (loaded == null) {
                        loaded = findClass(name);
                    }
                    if (resolve) {
                        resolveClass(loaded);
                    }
                    return loaded;
                }
            }
        };
    }

    @Test
    void companionSnapshotCanBeRecreatedAfterAResourceReload() {
        CompatPbrTexture.ImageData snapshot;
        try (NativeImage source = new NativeImage(
                NativeImage.Format.RGBA, 2, 1, true)) {
            source.setPixelRGBA(0, 0, 0x10203040);
            source.setPixelRGBA(1, 0, 0x50607080);
            snapshot = CompatPbrTexture.ImageData.copyOf(source);
        }

        assertPixels(snapshot);
        assertPixels(snapshot);
    }

    private static void assertPixels(CompatPbrTexture.ImageData snapshot) {
        try (NativeImage restored = snapshot.createImage()) {
            assertEquals(2, restored.getWidth());
            assertEquals(1, restored.getHeight());
            assertEquals(0x10203040, restored.getPixelRGBA(0, 0));
            assertEquals(0x50607080, restored.getPixelRGBA(1, 0));
        }
    }
}
