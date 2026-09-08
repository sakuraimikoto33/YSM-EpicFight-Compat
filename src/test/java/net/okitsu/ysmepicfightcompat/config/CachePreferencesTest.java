package net.okitsu.ysmepicfightcompat.config;

import com.electronwill.nightconfig.core.Config;
import com.electronwill.nightconfig.core.CommentedConfig;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraftforge.common.ForgeConfigSpec;
import net.okitsu.ysmepicfightcompat.animation.ModAnimationType;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CachePreferencesTest {
    @Test
    void usesTheChosenMemoryTargetNameWithoutALegacyAlias() {
        List<String> path = List.of("client", "cache", "clientModelMemoryCacheTargetCount");
        assertEquals(path, ClientPreferences.CLIENT_MODEL_MEMORY_CACHE_TARGET_COUNT.getPath());
        assertEquals(64, ClientPreferences.CLIENT_MODEL_MEMORY_CACHE_TARGET_COUNT.getDefault().intValue());
        ForgeConfigSpec.ValueSpec value =
                (ForgeConfigSpec.ValueSpec) ClientPreferences.CLIENT_SPEC.getRaw(path);
        assertEquals("config.ysm_epicfight_compat.client_model_memory_cache_target_count",
                value.getTranslationKey());
        assertTrue(value.getComment().startsWith("Target number"));
        assertTrue(value.getComment().contains("may exceed this target"));
        assertNull(ClientPreferences.CLIENT_SPEC.getRaw(List.of("client", "clientModelMemoryCacheSize")));
    }

    @Test
    void oldMemoryCacheValuesAreNotMigratedToTheRenamedTarget() {
        CommentedConfig config = CommentedConfig.inMemory();
        config.set(List.of("client", "clientModelMemoryCacheSize"), 128);

        ClientPreferences.CLIENT_SPEC.correct(config);

        assertEquals(64, ((Number) config.getRaw(
                List.of("client", "cache", "clientModelMemoryCacheTargetCount"))).intValue());
        assertNull(config.getRaw(List.of("client", "clientModelMemoryCacheSize")));
    }

    @Test
    void memoryTargetUiNamesAndTooltipsUseTheRenamedTranslationKey() throws IOException {
        String key = "config.ysm_epicfight_compat.client_model_memory_cache_target_count";
        String legacy = "config.ysm_epicfight_compat.client_model_memory_cache_size";
        for (var entry : Map.of("en_us", "Client model memory cache target count",
                "ja_jp", "クライアントモデルのメモリ保持目標数").entrySet()) {
            String resource = "assets/ysm_epicfight_compat/lang/" + entry.getKey() + ".json";
            try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
                assertNotNull(input, resource);
                JsonObject translations = JsonParser.parseReader(
                        new InputStreamReader(input, StandardCharsets.UTF_8)).getAsJsonObject();
                assertEquals(entry.getValue(), translations.get(key).getAsString());
                assertFalse(translations.get(key + ".tooltip").getAsString().isBlank());
                assertFalse(translations.has(legacy));
                assertFalse(translations.has(legacy + ".tooltip"));
            }
        }
    }

    @Test
    void exposesNamedIndependentCacheLimits() {
        assertEquals("ysm_epicfight_compat/ysm_epicfight_compat-client.toml",
                ClientPreferences.CONFIG_FILE);
        assertTrue(ClientPreferences.CLIENT_SPEC.getRaw(List.of(
                "client", "cache", "clientModelMemoryCacheTargetCount")) instanceof ForgeConfigSpec.ValueSpec);
        assertTrue(ClientPreferences.CLIENT_SPEC.getRaw(List.of(
                "client", "cache", "clientModelDiskCacheMiB")) instanceof ForgeConfigSpec.ValueSpec);
        assertTrue(ClientPreferences.CLIENT_SPEC.getRaw(List.of(
                "client", "cache", "remoteModelDiskCacheMiB")) instanceof ForgeConfigSpec.ValueSpec);
        assertTrue(ClientPreferences.CLIENT_SPEC.getRaw(List.of(
                "common", "models", "useYsmHeldItemModels"))
                instanceof ForgeConfigSpec.ValueSpec);
        assertTrue(ClientPreferences.CLIENT_SPEC.getRaw(List.of(
                "common", "models", "exclusions", "heldItemModelExclusions"))
                instanceof ForgeConfigSpec.ValueSpec);
        assertTrue(ClientPreferences.CLIENT_SPEC.getRaw(List.of(
                "common", "models", "useYsmProjectileModels"))
                instanceof ForgeConfigSpec.ValueSpec);
        assertTrue(ClientPreferences.CLIENT_SPEC.getRaw(List.of(
                "common", "models", "exclusions", "projectileModelExclusions"))
                instanceof ForgeConfigSpec.ValueSpec);
        assertTrue(ClientPreferences.CLIENT_SPEC.getRaw(List.of(
                "common", "models", "useYsmVehicleModels"))
                instanceof ForgeConfigSpec.ValueSpec);
        assertTrue(ClientPreferences.CLIENT_SPEC.getRaw(List.of(
                "common", "models", "exclusions", "vehicleModelExclusions"))
                instanceof ForgeConfigSpec.ValueSpec);
        assertTrue(ClientPreferences.CLIENT_SPEC.getRaw(List.of(
                "common", "animations", "useYsmHeldItemSwitchAnimations"))
                instanceof ForgeConfigSpec.ValueSpec);
        assertTrue(ClientPreferences.CLIENT_SPEC.getRaw(List.of(
                "common", "animations", "exclusions", "heldItemSwitchAnimationExclusions"))
                instanceof ForgeConfigSpec.ValueSpec);
        assertTrue(ClientPreferences.CLIENT_SPEC.getRaw(List.of(
                "common", "animations", "useYsmMovementAnimations"))
                instanceof ForgeConfigSpec.ValueSpec);
        assertTrue(ClientPreferences.CLIENT_SPEC.getRaw(List.of(
                "common", "animations", "useNaturalLadderAnimations"))
                instanceof ForgeConfigSpec.ValueSpec);
        assertTrue(ClientPreferences.CLIENT_SPEC.getRaw(List.of(
                "common", "animations", "exclusions", "movementAnimationExclusions"))
                instanceof ForgeConfigSpec.ValueSpec);
        assertTrue(ClientPreferences.CLIENT_SPEC.getRaw(List.of(
                "common", "animations", "useYsmParCoolAnimations"))
                instanceof ForgeConfigSpec.ValueSpec);
        assertTrue(ClientPreferences.CLIENT_SPEC.getRaw(List.of(
                "common", "animations", "useYsmSwemAnimations"))
                instanceof ForgeConfigSpec.ValueSpec);
        assertTrue(ClientPreferences.CLIENT_SPEC.getRaw(List.of(
                "common", "animations", "exclusions", "parcoolAnimationExclusions"))
                instanceof ForgeConfigSpec.ValueSpec);
        assertTrue(ClientPreferences.CLIENT_SPEC.getRaw(List.of(
                "common", "animations", "exclusions", "swemAnimationExclusions"))
                instanceof ForgeConfigSpec.ValueSpec);
        assertTrue(ClientPreferences.USE_YSM_MOVEMENT_ANIMATIONS
                .getDefault());
        assertTrue(ClientPreferences.USE_NATURAL_LADDER_ANIMATIONS
                .getDefault());
        assertTrue(ClientPreferences.USE_YSM_PARCOOL_ANIMATIONS.getDefault());
        assertTrue(ClientPreferences.USE_YSM_SWEM_ANIMATIONS.getDefault());
        assertTrue(ClientPreferences.PARCOOL_ANIMATION_EXCLUSIONS.getDefault().isEmpty());
        assertTrue(ClientPreferences.SWEM_ANIMATION_EXCLUSIONS.getDefault().isEmpty());
        assertTrue(ClientPreferences
                .USE_YSM_HELD_ITEM_SWITCH_ANIMATIONS.getDefault());
        assertTrue(ClientPreferences.USE_YSM_PROJECTILE_MODELS.getDefault());
        assertTrue(ClientPreferences.USE_YSM_VEHICLE_MODELS.getDefault());
        assertEquals("ysm_epicfight_compat/ysm_epicfight_compat-common.toml",
                ServerPreferences.CONFIG_FILE);
        assertTrue(ServerPreferences.COMMON_SPEC.getRaw(List.of(
                "server", "cache", "serverModelDiskCacheEnabled")) instanceof ForgeConfigSpec.ValueSpec);
        assertTrue(ServerPreferences.COMMON_SPEC.getRaw(List.of(
                "server", "cache", "serverModelDiskCacheMiB")) instanceof ForgeConfigSpec.ValueSpec);
    }

    @Test
    void writesMetadataInDescriptionSampleOrRangeDefaultOrder() {
        assertCommentTail(ClientPreferences.CLIENT_SPEC,
                List.of("client", "cache", "clientModelMemoryCacheTargetCount"),
                "Range: 8 ~ 512", "Default: 64");
        assertCommentTail(ClientPreferences.CLIENT_SPEC,
                List.of("client", "cache", "clientModelDiskCacheMiB"),
                "Range: 0 ~ 4096", "Default: 64");
        assertCommentTail(ClientPreferences.CLIENT_SPEC,
                List.of("client", "cache", "remoteModelDiskCacheMiB"),
                "Range: 0 ~ 4096", "Default: 64");
        assertCommentTail(ServerPreferences.COMMON_SPEC,
                List.of("server", "cache", "serverModelDiskCacheMiB"),
                "Range: 0 ~ 4096", "Default: 256");

        assertCommentTail(ClientPreferences.CLIENT_SPEC,
                List.of("common", "models", "exclusions", "heldItemModelExclusions"),
                "Example: \"wine_fox/21_saint\" = [\"minecraft:diamond_sword\", \"#forge:tools/bows\"].",
                "Default: {}");
        assertCommentTail(ClientPreferences.CLIENT_SPEC,
                List.of("common", "models", "exclusions", "projectileModelExclusions"),
                "Example: \"wine_fox/22_elf\" = [\"minecraft:arrow\", \"#minecraft:arrows\"].",
                "Default: {}");
        assertCommentTail(ClientPreferences.CLIENT_SPEC,
                List.of("common", "models", "exclusions", "vehicleModelExclusions"),
                "Example: \"wine_fox/01_taisho_maid\" = [\"minecraft:boat\", \"#minecraft:boats\"].",
                "Default: {}");
        assertCommentTail(ClientPreferences.CLIENT_SPEC,
                List.of("common", "animations", "exclusions", "heldItemSwitchAnimationExclusions"),
                "Example: \"wine_fox/05_magical\" = [\"minecraft:diamond_pickaxe\", \"minecraft:air\", \"#forge:tools/pickaxes\"].",
                "Default: {}");
        assertCommentTail(ClientPreferences.CLIENT_SPEC,
                List.of("common", "animations", "exclusions", "movementAnimationExclusions"),
                "Example: \"wine_fox/21_saint\" = [\"run\", \"creative_flight\"].",
                "Default: {}");
        assertOptionalCommentTail(ModAnimationType.PARCOOL,
                "parcoolAnimationExclusions",
                "Example: \"wine_fox/21_saint\" = [\"fast_running\", \"hang\"].",
                "Default: {}");
        assertOptionalCommentTail(ModAnimationType.SWEM,
                "swemAnimationExclusions",
                "Example: \"wine_fox/21_saint\" = [\"gallop\", \"jump_lv1\"].",
                "Default: {}");

        assertCommentTail(ClientPreferences.CLIENT_SPEC,
                List.of("client", "suppressBattleModeOverlay"),
                "Default: true");
        assertCommentTail(ClientPreferences.CLIENT_SPEC,
                List.of("common", "models", "useYsmHeldItemModels"),
                "Default: true");
        assertCommentTail(ClientPreferences.CLIENT_SPEC,
                List.of("common", "models", "useYsmProjectileModels"),
                "Default: true");
        assertCommentTail(ClientPreferences.CLIENT_SPEC,
                List.of("common", "models", "useYsmVehicleModels"),
                "Default: true");
        assertCommentTail(ClientPreferences.CLIENT_SPEC,
                List.of("common", "animations", "useYsmHeldItemSwitchAnimations"),
                "Default: true");
        assertCommentTail(ClientPreferences.CLIENT_SPEC,
                List.of("common", "animations", "useYsmMovementAnimations"),
                "Default: true");
        assertCommentTail(ClientPreferences.CLIENT_SPEC,
                List.of("common", "animations", "useNaturalLadderAnimations"),
                "Default: true");
        assertOptionalCommentTail(ModAnimationType.PARCOOL,
                "useYsmParCoolAnimations",
                "Default: true");
        assertOptionalCommentTail(ModAnimationType.SWEM,
                "useYsmSwemAnimations",
                "Default: true");
        assertCommentTail(ClientPreferences.CLIENT_SPEC,
                List.of("client", "epicFightCompatibilityWarningShown"),
                "Default: false");
        assertCommentTail(ServerPreferences.COMMON_SPEC,
                List.of("server", "cache", "serverModelDiskCacheEnabled"),
                "Default: true");
    }

    @Test
    void keepsNumericRangeValidationWithoutForgeAppendingCommentLines() {
        assertNumericRange(ClientPreferences.CLIENT_SPEC,
                List.of("client", "cache", "clientModelMemoryCacheTargetCount"), 8, 512);
        assertNumericRange(ClientPreferences.CLIENT_SPEC,
                List.of("client", "cache", "clientModelDiskCacheMiB"), 0, 4096);
        assertNumericRange(ClientPreferences.CLIENT_SPEC,
                List.of("client", "cache", "remoteModelDiskCacheMiB"), 0, 4096);
        assertNumericRange(ServerPreferences.COMMON_SPEC,
                List.of("server", "cache", "serverModelDiskCacheMiB"), 0, 4096);
    }

    @Test
    void modAnimationExclusionTablesValidateOnlyTheirOwnShortNames() {
        ForgeConfigSpec.ValueSpec parcool = (ForgeConfigSpec.ValueSpec)
                ClientPreferences.CLIENT_SPEC.getRaw(List.of("common", "animations", "exclusions", "parcoolAnimationExclusions"));
        ForgeConfigSpec.ValueSpec swem = (ForgeConfigSpec.ValueSpec)
                ClientPreferences.CLIENT_SPEC.getRaw(List.of("common", "animations", "exclusions", "swemAnimationExclusions"));
        Config rules = Config.inMemory();
        assertTrue(parcool.test(rules));
        assertTrue(swem.test(rules));

        rules.set(List.of("wine_fox/21_saint"), List.of("fast_running", "hang"));
        assertTrue(parcool.test(rules));
        assertFalse(swem.test(rules));
        rules.set(List.of("wine_fox/21_saint"), List.of("gallop", "jump_lv1"));
        assertFalse(parcool.test(rules));
        assertTrue(swem.test(rules));
        rules.set(List.of("wine_fox/21_saint"), List.of("parcool:hang"));
        assertFalse(parcool.test(rules));
        assertFalse(swem.test(rules));
        rules.set(List.of("wine_fox/21_saint"), List.of("*"));
        assertFalse(parcool.test(rules));
        assertFalse(swem.test(rules));
    }

    private static void assertOptionalCommentTail(
            ModAnimationType family, String key, String... expectedTail) {
        List<String> path = key.endsWith("Exclusions")
                ? List.of("common", "animations", "exclusions", key)
                : List.of("common", "animations", key);
        if (ClientPreferences.isOptionalAnimationAvailable(family)) {
            assertCommentTail(ClientPreferences.CLIENT_SPEC, path, expectedTail);
        } else {
            ForgeConfigSpec.ValueSpec value =
                    (ForgeConfigSpec.ValueSpec) ClientPreferences.CLIENT_SPEC.getRaw(path);
            assertNull(value.getComment(), () -> "Orphan comment for absent mod: " + path);
        }
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
