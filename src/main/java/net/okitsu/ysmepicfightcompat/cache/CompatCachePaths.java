package net.okitsu.ysmepicfightcompat.cache;

import net.minecraftforge.fml.loading.FMLPaths;

import java.nio.file.Path;

/** Resolves the three compatibility-owned disk cache roots. */
public final class CompatCachePaths {
    private CompatCachePaths() {
    }

    public static Path client() {
        return root().resolve("client");
    }

    public static Path remote() {
        return root().resolve("remote");
    }

    public static Path server() {
        return root().resolve("server");
    }

    private static Path root() {
        return FMLPaths.CONFIGDIR.get().resolve("ysm_epicfight_compat").resolve("cache");
    }
}
