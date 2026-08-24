package net.okitsu.ysmepicfightcompat.config;

import net.minecraftforge.common.ForgeConfigSpec;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CachePreferencesTest {
    @Test
    void exposesNamedIndependentCacheLimits() {
        assertEquals("ysm_epicfight_compat/ysm_epicfight_compat-client.toml",
                ClientPreferences.CONFIG_FILE);
        assertTrue(ClientPreferences.CLIENT_SPEC.getRaw(List.of(
                "client", "clientModelMemoryCacheSize")) instanceof ForgeConfigSpec.ValueSpec);
        assertTrue(ClientPreferences.CLIENT_SPEC.getRaw(List.of(
                "client", "clientModelDiskCacheMiB")) instanceof ForgeConfigSpec.ValueSpec);
        assertTrue(ClientPreferences.CLIENT_SPEC.getRaw(List.of(
                "client", "remoteModelDiskCacheMiB")) instanceof ForgeConfigSpec.ValueSpec);
        assertEquals("ysm_epicfight_compat/ysm_epicfight_compat-common.toml",
                ServerPreferences.CONFIG_FILE);
        assertTrue(ServerPreferences.COMMON_SPEC.getRaw(List.of(
                "server", "serverModelDiskCacheEnabled")) instanceof ForgeConfigSpec.ValueSpec);
        assertTrue(ServerPreferences.COMMON_SPEC.getRaw(List.of(
                "server", "serverModelDiskCacheMiB")) instanceof ForgeConfigSpec.ValueSpec);
    }
}
