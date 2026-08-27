package net.okitsu.ysmepicfightcompat.config;

import net.minecraftforge.common.ForgeConfigSpec;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        assertTrue(ClientPreferences.CLIENT_SPEC.getRaw(List.of(
                "client", "useYsmHeldItemModelsByDefault"))
                instanceof ForgeConfigSpec.ValueSpec);
        assertTrue(ClientPreferences.CLIENT_SPEC.getRaw(List.of(
                "client", "heldItemModelOverrides"))
                instanceof ForgeConfigSpec.ValueSpec);
        assertTrue(ClientPreferences.CLIENT_SPEC.getRaw(List.of(
                "client", "useYsmHeldItemSwitchAnimationsByDefault"))
                instanceof ForgeConfigSpec.ValueSpec);
        assertTrue(ClientPreferences.CLIENT_SPEC.getRaw(List.of(
                "client", "heldItemSwitchAnimationOverrides"))
                instanceof ForgeConfigSpec.ValueSpec);
        assertTrue(ClientPreferences.CLIENT_SPEC.getRaw(List.of(
                "client", "useYsmMovementAnimationsByDefault"))
                instanceof ForgeConfigSpec.ValueSpec);
        assertTrue(ClientPreferences.CLIENT_SPEC.getRaw(List.of(
                "client", "movementAnimationOverrides"))
                instanceof ForgeConfigSpec.ValueSpec);
        assertFalse(ClientPreferences.USE_YSM_MOVEMENT_ANIMATIONS_BY_DEFAULT
                .getDefault());
        assertTrue(ClientPreferences
                .USE_YSM_HELD_ITEM_SWITCH_ANIMATIONS_BY_DEFAULT.getDefault());
        assertEquals("ysm_epicfight_compat/ysm_epicfight_compat-common.toml",
                ServerPreferences.CONFIG_FILE);
        assertTrue(ServerPreferences.COMMON_SPEC.getRaw(List.of(
                "server", "serverModelDiskCacheEnabled")) instanceof ForgeConfigSpec.ValueSpec);
        assertTrue(ServerPreferences.COMMON_SPEC.getRaw(List.of(
                "server", "serverModelDiskCacheMiB")) instanceof ForgeConfigSpec.ValueSpec);
    }
}
