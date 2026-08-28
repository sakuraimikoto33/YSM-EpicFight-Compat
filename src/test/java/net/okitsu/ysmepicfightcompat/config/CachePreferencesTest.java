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
                "client", "useYsmHeldItemModels"))
                instanceof ForgeConfigSpec.ValueSpec);
        assertTrue(ClientPreferences.CLIENT_SPEC.getRaw(List.of(
                "client", "heldItemModelExclusions"))
                instanceof ForgeConfigSpec.ValueSpec);
        assertTrue(ClientPreferences.CLIENT_SPEC.getRaw(List.of(
                "client", "useYsmProjectileModels"))
                instanceof ForgeConfigSpec.ValueSpec);
        assertTrue(ClientPreferences.CLIENT_SPEC.getRaw(List.of(
                "client", "projectileModelExclusions"))
                instanceof ForgeConfigSpec.ValueSpec);
        assertTrue(ClientPreferences.CLIENT_SPEC.getRaw(List.of(
                "client", "useYsmVehicleModels"))
                instanceof ForgeConfigSpec.ValueSpec);
        assertTrue(ClientPreferences.CLIENT_SPEC.getRaw(List.of(
                "client", "vehicleModelExclusions"))
                instanceof ForgeConfigSpec.ValueSpec);
        assertTrue(ClientPreferences.CLIENT_SPEC.getRaw(List.of(
                "client", "useYsmHeldItemSwitchAnimations"))
                instanceof ForgeConfigSpec.ValueSpec);
        assertTrue(ClientPreferences.CLIENT_SPEC.getRaw(List.of(
                "client", "heldItemSwitchAnimationExclusions"))
                instanceof ForgeConfigSpec.ValueSpec);
        assertTrue(ClientPreferences.CLIENT_SPEC.getRaw(List.of(
                "client", "useYsmMovementAnimations"))
                instanceof ForgeConfigSpec.ValueSpec);
        assertTrue(ClientPreferences.CLIENT_SPEC.getRaw(List.of(
                "client", "movementAnimationExclusions"))
                instanceof ForgeConfigSpec.ValueSpec);
        assertTrue(ClientPreferences.USE_YSM_MOVEMENT_ANIMATIONS
                .getDefault());
        assertTrue(ClientPreferences
                .USE_YSM_HELD_ITEM_SWITCH_ANIMATIONS.getDefault());
        assertTrue(ClientPreferences.USE_YSM_PROJECTILE_MODELS.getDefault());
        assertTrue(ClientPreferences.USE_YSM_VEHICLE_MODELS.getDefault());
        assertEquals("ysm_epicfight_compat/ysm_epicfight_compat-common.toml",
                ServerPreferences.CONFIG_FILE);
        assertTrue(ServerPreferences.COMMON_SPEC.getRaw(List.of(
                "server", "serverModelDiskCacheEnabled")) instanceof ForgeConfigSpec.ValueSpec);
        assertTrue(ServerPreferences.COMMON_SPEC.getRaw(List.of(
                "server", "serverModelDiskCacheMiB")) instanceof ForgeConfigSpec.ValueSpec);
    }

    @Test
    void writesMetadataInDescriptionSampleOrRangeDefaultOrder() {
        assertCommentTail(ClientPreferences.CLIENT_SPEC,
                List.of("client", "clientModelMemoryCacheSize"),
                "Range: 8 ~ 512", "Default: 64");
        assertCommentTail(ClientPreferences.CLIENT_SPEC,
                List.of("client", "clientModelDiskCacheMiB"),
                "Range: 0 ~ 4096", "Default: 64");
        assertCommentTail(ClientPreferences.CLIENT_SPEC,
                List.of("client", "remoteModelDiskCacheMiB"),
                "Range: 0 ~ 4096", "Default: 64");
        assertCommentTail(ServerPreferences.COMMON_SPEC,
                List.of("server", "serverModelDiskCacheMiB"),
                "Range: 0 ~ 4096", "Default: 256");

        assertCommentTail(ClientPreferences.CLIENT_SPEC,
                List.of("client", "heldItemModelExclusions"),
                "Example: \"wine_fox/21_saint\" = [\"minecraft:diamond_sword\", \"#forge:tools/bows\"].",
                "Default: {}");
        assertCommentTail(ClientPreferences.CLIENT_SPEC,
                List.of("client", "projectileModelExclusions"),
                "Example: \"wine_fox/22_elf\" = [\"minecraft:arrow\", \"#minecraft:arrows\"].",
                "Default: {}");
        assertCommentTail(ClientPreferences.CLIENT_SPEC,
                List.of("client", "vehicleModelExclusions"),
                "Example: \"wine_fox/01_taisho_maid\" = [\"minecraft:boat\", \"#minecraft:boats\"].",
                "Default: {}");
        assertCommentTail(ClientPreferences.CLIENT_SPEC,
                List.of("client", "heldItemSwitchAnimationExclusions"),
                "Example: \"wine_fox/05_magical\" = [\"minecraft:diamond_pickaxe\", \"minecraft:air\", \"#forge:tools/pickaxes\"].",
                "Default: {}");
        assertCommentTail(ClientPreferences.CLIENT_SPEC,
                List.of("client", "movementAnimationExclusions"),
                "Example: \"wine_fox/21_saint\" = [\"run\", \"creative_flight\"].",
                "Default: {}");

        assertCommentTail(ClientPreferences.CLIENT_SPEC,
                List.of("client", "suppressBattleModeOverlay"),
                "Default: true");
        assertCommentTail(ClientPreferences.CLIENT_SPEC,
                List.of("client", "useYsmHeldItemModels"),
                "Default: true");
        assertCommentTail(ClientPreferences.CLIENT_SPEC,
                List.of("client", "useYsmProjectileModels"),
                "Default: true");
        assertCommentTail(ClientPreferences.CLIENT_SPEC,
                List.of("client", "useYsmVehicleModels"),
                "Default: true");
        assertCommentTail(ClientPreferences.CLIENT_SPEC,
                List.of("client", "useYsmHeldItemSwitchAnimations"),
                "Default: true");
        assertCommentTail(ClientPreferences.CLIENT_SPEC,
                List.of("client", "useYsmMovementAnimations"),
                "Default: true");
        assertCommentTail(ClientPreferences.CLIENT_SPEC,
                List.of("client", "epicFightCompatibilityWarningShown"),
                "Default: false");
        assertCommentTail(ServerPreferences.COMMON_SPEC,
                List.of("server", "serverModelDiskCacheEnabled"),
                "Default: true");
    }

    @Test
    void keepsNumericRangeValidationWithoutForgeAppendingCommentLines() {
        assertNumericRange(ClientPreferences.CLIENT_SPEC,
                List.of("client", "clientModelMemoryCacheSize"), 8, 512);
        assertNumericRange(ClientPreferences.CLIENT_SPEC,
                List.of("client", "clientModelDiskCacheMiB"), 0, 4096);
        assertNumericRange(ClientPreferences.CLIENT_SPEC,
                List.of("client", "remoteModelDiskCacheMiB"), 0, 4096);
        assertNumericRange(ServerPreferences.COMMON_SPEC,
                List.of("server", "serverModelDiskCacheMiB"), 0, 4096);
    }

    private static void assertCommentTail(
            ForgeConfigSpec spec, List<String> path, String... expectedTail) {
        ForgeConfigSpec.ValueSpec value =
                (ForgeConfigSpec.ValueSpec) spec.getRaw(path);
        String[] lines = value.getComment().split("\\R");
        assertTrue(lines.length > expectedTail.length,
                () -> "Missing description for " + path);
        int offset = lines.length - expectedTail.length;
        for (int index = 0; index < expectedTail.length; index++) {
            assertEquals(expectedTail[index], lines[offset + index],
                    () -> "Unexpected comment order for " + path);
        }
    }

    private static void assertNumericRange(
            ForgeConfigSpec spec, List<String> path, int minimum, int maximum) {
        ForgeConfigSpec.ValueSpec value =
                (ForgeConfigSpec.ValueSpec) spec.getRaw(path);
        assertTrue(value.test(minimum), () -> "Minimum rejected for " + path);
        assertTrue(value.test(maximum), () -> "Maximum rejected for " + path);
        assertFalse(value.test(minimum - 1),
                () -> "Value below minimum accepted for " + path);
        assertFalse(value.test(maximum + 1),
                () -> "Value above maximum accepted for " + path);
    }
}
