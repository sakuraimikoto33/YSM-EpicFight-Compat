package net.okitsu.ysmepicfightcompat.config;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.electronwill.nightconfig.core.file.CommentedFileConfigBuilder;
import com.electronwill.nightconfig.core.file.FileConfig;
import com.electronwill.nightconfig.core.file.FileNotFoundAction;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.IConfigSpec;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforgespi.language.IModInfo;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.concurrent.locks.ReentrantLock;

/** Binds real NeoForge loaded configs without starting ModLauncher or a Minecraft client. */
public final class ConfigTestSupport {
    static {
        // NeoForge's real save path initializes ConfigTracker, which resolves defaultconfigs
        // against GAMEDIR even when the fixture itself already has an absolute file path.
        if (FMLPaths.GAMEDIR.get() == null) {
            FMLPaths.loadAbsolutePaths(Path.of("build", "test-game"));
        }
    }

    private ConfigTestSupport() {
    }

    public static CommentedFileConfigBuilder fileConfigBuilder(Path path) {
        // NightConfig's CREATE_EMPTY initializer leaks its writer on Windows.
        // Let the normal save operation create a missing fixture file instead.
        var builder = CommentedFileConfig.builder(path);
        builder.onFileNotFound(FileNotFoundAction.READ_NOTHING);
        return builder;
    }

    public static ModConfig bind(ModConfigSpec spec, CommentedConfig data) {
        try {
            IModInfo info = (IModInfo) Proxy.newProxyInstance(IModInfo.class.getClassLoader(),
                    new Class<?>[]{IModInfo.class}, (proxy, method, arguments) -> {
                        if (method.getName().equals("getModId") || method.getName().equals("toString")) {
                            return "ysm_epicfight_compat";
                        }
                        throw new UnsupportedOperationException(method.getName());
                    });
            ModContainer container = new ModContainer(info) {
                @Override
                public IEventBus getEventBus() {
                    return null;
                }
            };
            var configConstructor = ModConfig.class.getDeclaredConstructor(
                    ModConfig.Type.class, IConfigSpec.class, ModContainer.class,
                    String.class, ReentrantLock.class);
            configConstructor.setAccessible(true);
            ModConfig config = configConstructor.newInstance(
                    ModConfig.Type.CLIENT, spec, container, "fixture.toml", new ReentrantLock());
            Class<?> loadedType = Class.forName("net.neoforged.fml.config.LoadedConfig");
            var loadedConstructor = loadedType.getDeclaredConstructor(
                    CommentedConfig.class, Path.class, ModConfig.class);
            loadedConstructor.setAccessible(true);
            Path path = data instanceof FileConfig file ? file.getNioPath() : null;
            IConfigSpec.ILoadedConfig loaded = (IConfigSpec.ILoadedConfig)
                    loadedConstructor.newInstance(data, path, config);
            var loadedField = ModConfig.class.getDeclaredField("loadedConfig");
            loadedField.setAccessible(true);
            loadedField.set(config, loaded);
            spec.acceptConfig(loaded);
            return config;
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("Could not bind NeoForge's loaded-config fixture", exception);
        }
    }

    public static void clear(ModConfigSpec spec) {
        spec.acceptConfig(null);
    }
}
