package net.okitsu.ysmepicfightcompat.config;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.core.Config;
import com.electronwill.nightconfig.core.InMemoryCommentedFormat;
import com.electronwill.nightconfig.core.UnmodifiableConfig;
import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.electronwill.nightconfig.toml.TomlParser;
import com.electronwill.nightconfig.toml.TomlWriter;
import net.minecraftforge.common.ForgeConfigSpec;
import net.okitsu.ysmepicfightcompat.animation.ModAnimationType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static net.okitsu.ysmepicfightcompat.animation.ModAnimationType.PARCOOL;
import static net.okitsu.ysmepicfightcompat.animation.ModAnimationType.SWEM;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exercises the real TOML representation without binding the global specs to test configs. */
class ConfigTomlLayoutTest {
    private static final String MODEL = "wine.fox/モデル";
    private static final List<List<String>> CLIENT_CATEGORIES = List.of(
            path("client"),
            path("client", "cache"),
            path("common"),
            path("common", "models"),
            path("common", "models", "exclusions"),
            path("common", "animations"),
            path("common", "animations", "exclusions"));
    private static final List<List<String>> SERVER_CATEGORIES = List.of(path("server", "cache"));

    @Test
    void clientDefaultsUseEveryExpectedNestedPathWithoutChangingLeafNamesOrDefaults() {
        List<Setting> settings = clientSettings();
        Written written = defaults(ClientPreferences.CLIENT_SPEC);

        assertEquals(21, settings.size());
        assertInventory(ClientPreferences.CLIENT_SPEC, settings);
        assertDefaults(written.parsed(), settings);
        assertNoFlatKeys(written.parsed(), settings);
        assertEquals("ysm_epicfight_compat/ysm_epicfight_compat-client.toml",
                ClientPreferences.CONFIG_FILE);
        assertEquals(Set.of("client", "common"), written.parsed().valueMap().keySet());
        UnmodifiableConfig client = assertInstanceOf(UnmodifiableConfig.class,
                written.parsed().getRaw(path("client")));
        assertEquals(Set.of("cache", "suppressBattleModeOverlay", "animationEvaluationRateLimitHz",
                        "epicFightCompatibilityWarningShown"),
                client.valueMap().keySet());
        UnmodifiableConfig common = assertInstanceOf(UnmodifiableConfig.class,
                written.parsed().getRaw(path("common")));
        assertEquals(Set.of("models", "animations"), common.valueMap().keySet());
        assertStable(ClientPreferences.CLIENT_SPEC, written.parsed());
    }

    @Test
    void serverDefaultsUseTheNestedCacheTableWithoutChangingTheirValues() {
        List<Setting> settings = serverSettings();
        Written written = defaults(ServerPreferences.COMMON_SPEC);

        assertEquals(2, settings.size());
        assertInventory(ServerPreferences.COMMON_SPEC, settings);
        assertDefaults(written.parsed(), settings);
        assertNoFlatKeys(written.parsed(), settings);
        assertStable(ServerPreferences.COMMON_SPEC, written.parsed());
    }

    @Test
    void categoryHeadersHaveHierarchicalTranslationsCommentsAndReadableTabIndentation() {
        assertCategories(ClientPreferences.CLIENT_SPEC, CLIENT_CATEGORIES);
        assertCategories(ServerPreferences.COMMON_SPEC, SERVER_CATEGORIES);
    }

    @Test
    void leafCommentsRemainDirectlyAboveTheirIndentedValuesAfterSerialization() {
        assertLeafComments(ClientPreferences.CLIENT_SPEC, clientSettings());
        assertLeafComments(ServerPreferences.COMMON_SPEC, serverSettings());
    }

    @Test
    void allCustomizedClientValuesSurviveFileSaveReloadAndRepeatedCorrection(@TempDir Path directory)
            throws IOException {
        ForgeConfigSpec spec = ClientPreferences.CLIENT_SPEC;
        List<Setting> settings = clientSettings();
        Path file = directory.resolve("client.toml");
        try (CommentedFileConfig config = CommentedFileConfig.builder(file)
                .sync().autosave().preserveInsertionOrder().build()) {
            config.load();
            spec.correct(config);
            settings.forEach(setting -> config.set(setting.path(), setting.customValue()));
            spec.correct(config);
            assertCustomValues(config, settings);
            config.save();

            for (int reload = 0; reload < 2; reload++) {
                config.load();
                assertCustomValues(config, settings);
                assertStable(spec, config);
                config.save();
            }
        }

        CommentedConfig parsed = new TomlParser().parse(Files.readString(file));
        assertCustomValues(parsed, settings);
        assertNoFlatKeys(parsed, settings);
        assertStable(spec, parsed);
    }

    @Test
    void customizedServerValuesSurviveRepeatedTomlRoundTrips() {
        ForgeConfigSpec spec = ServerPreferences.COMMON_SPEC;
        List<Setting> settings = serverSettings();
        CommentedConfig config = orderedConfig();
        for (Setting setting : settings) {
            config.set(setting.path(), setting.customValue());
        }
        spec.correct(config);

        for (int reload = 0; reload < 2; reload++) {
            config = write(config).parsed();
            assertCustomValues(config, settings);
            assertNoFlatKeys(config, settings);
            assertStable(spec, config);
        }
    }

    @Test
    void everyExclusionTableKeepsDottedSlashedAndUnicodeModelIdsAsOneQuotedKey() {
        CommentedConfig config = orderedConfig();
        List<Setting> settings = clientSettings();
        settings.forEach(setting -> config.set(setting.path(), setting.customValue()));
        ClientPreferences.CLIENT_SPEC.correct(config);

        Written written = write(config);
        List<Setting> exclusions = settings.stream()
                .filter(setting -> setting.customValue() instanceof Config).toList();
        assertEquals(7, exclusions.size());
        for (Setting setting : exclusions) {
            String tableLine = indent(setting.path()) + "[" + dotted(setting.path()) + "]";
            assertTrue(written.toml().lines().anyMatch(tableLine::equals), setting.path().toString());
            UnmodifiableConfig restored = assertInstanceOf(UnmodifiableConfig.class,
                    written.parsed().getRaw(setting.path()));
            assertEquals(Set.of(MODEL), restored.valueMap().keySet(), setting.path().toString());
            assertFalse(restored.contains(List.of("wine")), setting.path().toString());
            assertEquals(plain(setting.customValue()), plain(restored), setting.path().toString());
            String quotedKey = "\t".repeat(setting.path().size()) + "\"" + MODEL + "\" = [";
            String body = written.toml().substring(written.toml().indexOf(tableLine) + tableLine.length())
                    .lines().takeWhile(line -> !line.stripLeading().startsWith("["))
                    .collect(Collectors.joining("\n"));
            assertTrue(body.contains(quotedKey), "Missing quoted model key in " + setting.path());
        }
        assertStable(ClientPreferences.CLIENT_SPEC, written.parsed());
    }

    @Test
    void absentOptionalModKeysAreNotGeneratedByAnUnrelatedNestedSave() {
        ForgeConfigSpec spec = absentOptionalSpec();
        CommentedConfig config = orderedConfig();
        spec.correct(config);
        config.set(path("client", "cache", "clientModelDiskCacheMiB"), 128);

        Written written = write(config);

        for (Setting setting : optionalSettings()) {
            assertFalse(written.parsed().contains(setting.path()), setting.path().toString());
            assertNull(written.parsed().getComment(setting.path()), setting.path().toString());
            assertFalse(written.toml().contains(setting.leaf()), setting.path().toString());
        }
        assertEquals(128, written.parsed().<Integer>get(
                path("client", "cache", "clientModelDiskCacheMiB")));
        assertStable(spec, written.parsed());
    }

    @ParameterizedTest
    @ValueSource(strings = {"useYsmParCoolAnimations", "parcoolAnimationExclusions",
            "useYsmSwemAnimations", "swemAnimationExclusions"})
    void absentOptionalModsPreserveExistingNestedValuesWithoutCreatingTheirSiblings(String leaf) {
        ForgeConfigSpec spec = absentOptionalSpec();
        List<Setting> optional = optionalSettings();
        Setting existing = optional.stream().filter(setting -> setting.leaf().equals(leaf))
                .findFirst().orElseThrow();
        CommentedConfig config = orderedConfig();
        config.set(existing.path(), existing.customValue());
        spec.correct(config);
        config.set(path("client", "cache", "clientModelDiskCacheMiB"), 128);

        for (int reload = 0; reload < 2; reload++) {
            config = write(config).parsed();
            for (Setting setting : optional) {
                assertEquals(setting == existing, config.contains(setting.path()),
                        setting.path().toString());
            }
            assertEquals(plain(existing.customValue()), plain(config.getRaw(existing.path())));
            assertStable(spec, config);
        }
    }

    @Test
    void oldFlatClientValuesAreNotMigratedWhileUnmovedRootKeysKeepTheirValues() {
        assertNoMigration(ClientPreferences.CLIENT_SPEC, clientSettings());
    }

    @Test
    void previousClientModelAndAnimationCategoriesAreNotMigratedIntoCommon() {
        List<Setting> settings = clientSettings().stream()
                .filter(setting -> setting.path().get(0).equals("common")).toList();
        assertEquals(15, settings.size());

        assertNoCategoryMigration(settings);
    }

    @Test
    void previousRenderingAndNotificationsCategoriesAreNotMigratedToClientRootKeys() {
        List<Setting> settings = clientSettings().stream()
                .filter(setting -> setting.path().size() == 2
                        && !setting.previousCategoryPath().equals(setting.path())).toList();
        assertEquals(2, settings.size());

        assertNoCategoryMigration(settings);
    }

    @Test
    void oldFlatServerValuesAreRemovedWithoutMigratingThemIntoTheNewCacheTable() {
        assertNoMigration(ServerPreferences.COMMON_SPEC, serverSettings());
    }

    private static void assertInventory(ForgeConfigSpec spec, List<Setting> settings) {
        Set<List<String>> actualPaths = new LinkedHashSet<>();
        collectValuePaths(spec.getValues(), List.of(), actualPaths);
        assertEquals(settings.stream().map(Setting::path).collect(Collectors.toSet()), actualPaths);
        for (Setting setting : settings) {
            assertEquals(setting.path(), setting.value().getPath());
            assertEquals(setting.defaultValue(), plain(setting.value().getDefault()),
                    setting.path().toString());
            assertInstanceOf(ForgeConfigSpec.ValueSpec.class, spec.getRaw(setting.path()));
            if (!setting.flatPath().equals(setting.path())) {
                assertNull(spec.getRaw(setting.flatPath()), setting.flatPath().toString());
            }
            if (!setting.previousCategoryPath().equals(setting.path())) {
                assertNull(spec.getRaw(setting.previousCategoryPath()),
                        setting.previousCategoryPath().toString());
            }
        }
    }

    private static void collectValuePaths(UnmodifiableConfig config, List<String> parent,
                                         Set<List<String>> result) {
        config.valueMap().forEach((key, value) -> {
            List<String> path = new ArrayList<>(parent);
            path.add(key);
            if (value instanceof UnmodifiableConfig nested) {
                collectValuePaths(nested, path, result);
            } else {
                assertInstanceOf(ForgeConfigSpec.ConfigValue.class, value);
                result.add(List.copyOf(path));
            }
        });
    }

    private static void assertCategories(ForgeConfigSpec spec, List<List<String>> categories) {
        Written written = defaults(spec);
        int previousHeader = -1;
        for (List<String> path : categories) {
            String header = indent(path) + "[" + dotted(path) + "]";
            assertTrue(written.toml().lines().anyMatch(header::equals), header);
            int index = written.toml().indexOf(header);
            assertTrue(index > previousHeader, "Category order changed at " + path);
            previousHeader = index;
            assertEquals("config.ysm_epicfight_compat." + dotted(path),
                    spec.getLevelTranslationKey(path));
            String comment = spec.getLevelComment(path);
            assertNotNull(comment, path.toString());
            assertFalse(comment.isBlank(), path.toString());
            assertEquals(normalizeNewlines(comment), normalizeNewlines(written.parsed().getComment(path)));
            assertTrue(written.toml().contains(commentBlock(comment, indent(path)) + header),
                    "Category comment is not directly above " + path);
        }
        assertStable(spec, written.parsed());
    }

    private static void assertLeafComments(ForgeConfigSpec spec, List<Setting> settings) {
        Written written = defaults(spec);
        for (Setting setting : settings) {
            ForgeConfigSpec.ValueSpec value = assertInstanceOf(ForgeConfigSpec.ValueSpec.class,
                    spec.getRaw(setting.path()));
            String comment = value.getComment();
            if (!setting.generatedByDefault()) {
                assertNull(comment, setting.path().toString());
                assertNull(written.parsed().getComment(setting.path()), setting.path().toString());
                continue;
            }
            assertNotNull(comment, setting.path().toString());
            assertEquals(normalizeNewlines(comment),
                    normalizeNewlines(written.parsed().getComment(setting.path())));
            String indent = indent(setting.path());
            assertTrue(written.toml().contains(commentBlock(comment, indent)
                            + indent + setting.leaf() + " = "),
                    "Comment/indentation changed for " + setting.path());
        }
    }

    private static void assertDefaults(UnmodifiableConfig config, List<Setting> settings) {
        for (Setting setting : settings) {
            assertEquals(setting.generatedByDefault(), config.contains(setting.path()),
                    setting.path().toString());
            if (setting.generatedByDefault()) {
                assertEquals(setting.defaultValue(), plain(config.getRaw(setting.path())),
                        setting.path().toString());
            }
        }
    }

    private static void assertCustomValues(UnmodifiableConfig config, List<Setting> settings) {
        for (Setting setting : settings) {
            assertEquals(plain(setting.customValue()), plain(config.getRaw(setting.path())),
                    setting.path().toString());
        }
    }

    private static void assertNoFlatKeys(UnmodifiableConfig config, List<Setting> settings) {
        for (Setting setting : settings) {
            if (!setting.flatPath().equals(setting.path())) {
                assertFalse(config.contains(setting.flatPath()), setting.flatPath().toString());
            }
        }
    }

    private static void assertNoMigration(ForgeConfigSpec spec, List<Setting> settings) {
        CommentedConfig config = orderedConfig();
        for (Setting setting : settings) {
            config.set(setting.flatPath(), setting.customValue());
        }
        // Parse the old representation too, so the input is an actual flat TOML layout.
        config = write(config).parsed();
        spec.correct(config);

        Written written = write(config);

        assertDefaults(written.parsed(), settings.stream()
                .filter(setting -> !setting.flatPath().equals(setting.path())).toList());
        assertCustomValues(written.parsed(), settings.stream()
                .filter(setting -> setting.flatPath().equals(setting.path())).toList());
        assertNoFlatKeys(written.parsed(), settings);
        assertStable(spec, written.parsed());
    }

    private static void assertNoCategoryMigration(List<Setting> settings) {
        CommentedConfig config = orderedConfig();
        for (Setting setting : settings) {
            assertFalse(setting.previousCategoryPath().equals(setting.path()));
            config.set(setting.previousCategoryPath(), setting.customValue());
        }
        config = write(config).parsed();
        ClientPreferences.CLIENT_SPEC.correct(config);

        Written written = write(config);

        assertDefaults(written.parsed(), clientSettings());
        for (Setting setting : settings) {
            assertFalse(written.parsed().contains(setting.previousCategoryPath()),
                    setting.previousCategoryPath().toString());
            assertNull(ClientPreferences.CLIENT_SPEC.getRaw(setting.previousCategoryPath()),
                    setting.previousCategoryPath().toString());
        }
        for (String category : List.of("models", "animations", "rendering", "notifications")) {
            assertFalse(written.parsed().contains(path("client", category)), category);
        }
        assertStable(ClientPreferences.CLIENT_SPEC, written.parsed());
    }

    private static void assertStable(ForgeConfigSpec spec, CommentedConfig config) {
        assertTrue(spec.isCorrect(config), "Serialized config requires another value/comment correction");
        assertEquals(0, spec.correct(config));
    }

    private static Written defaults(ForgeConfigSpec spec) {
        CommentedConfig config = orderedConfig();
        spec.correct(config);
        return write(config);
    }

    private static Written write(UnmodifiableConfig config) {
        StringWriter output = new StringWriter();
        TomlWriter writer = new TomlWriter();
        writer.setNewline("\n");
        writer.write(config, output);
        String toml = output.toString();
        return new Written(toml, new TomlParser().parse(toml));
    }

    private static CommentedConfig orderedConfig() {
        return CommentedConfig.of(LinkedHashMap::new, InMemoryCommentedFormat.defaultInstance());
    }

    private static Object plain(Object value) {
        if (value instanceof UnmodifiableConfig config) {
            Map<String, Object> result = new LinkedHashMap<>();
            config.valueMap().forEach((key, child) -> result.put(key, plain(child)));
            return result;
        }
        return value;
    }

    private static String commentBlock(String comment, String indent) {
        return normalizeNewlines(comment).lines()
                .map(line -> indent + "#" + line + "\n").collect(Collectors.joining());
    }

    private static String normalizeNewlines(String value) {
        return value == null ? null : value.replace("\r\n", "\n");
    }

    private static String indent(List<String> path) {
        return "\t".repeat(path.size() - 1);
    }

    private static String dotted(List<String> path) {
        return String.join(".", path);
    }

    private static List<String> path(String... parts) {
        return List.of(parts);
    }

    private static Config rules(String... selectors) {
        Config config = Config.inMemory();
        config.set(List.of(MODEL), List.of(selectors));
        return config;
    }

    private static List<Setting> clientSettings() {
        return List.of(
                setting(ClientPreferences.CLIENT_MODEL_MEMORY_CACHE_TARGET_COUNT,
                        path("client", "cache", "clientModelMemoryCacheTargetCount"), 64, 96),
                setting(ClientPreferences.CLIENT_MODEL_DISK_CACHE_MIB,
                        path("client", "cache", "clientModelDiskCacheMiB"), 64, 128),
                setting(ClientPreferences.REMOTE_MODEL_DISK_CACHE_MIB,
                        path("client", "cache", "remoteModelDiskCacheMiB"), 64, 192),
                setting(ClientPreferences.SUPPRESS_BATTLE_MODE_OVERLAY,
                        path("client", "suppressBattleModeOverlay"), true, false),
                setting(ClientPreferences.ANIMATION_EVALUATION_RATE_LIMIT_HZ,
                        path("client", "animationEvaluationRateLimitHz"), 60, 0),
                setting(ClientPreferences.USE_YSM_HELD_ITEM_MODELS,
                        path("common", "models", "useYsmHeldItemModels"), true, false),
                setting(ClientPreferences.USE_YSM_PROJECTILE_MODELS,
                        path("common", "models", "useYsmProjectileModels"), true, false),
                setting(ClientPreferences.USE_YSM_VEHICLE_MODELS,
                        path("common", "models", "useYsmVehicleModels"), true, false),
                setting(ClientPreferences.HELD_ITEM_MODEL_EXCLUSIONS,
                        path("common", "models", "exclusions", "heldItemModelExclusions"),
                        Map.of(), rules("minecraft:diamond_sword", "#forge:tools/bows")),
                setting(ClientPreferences.PROJECTILE_MODEL_EXCLUSIONS,
                        path("common", "models", "exclusions", "projectileModelExclusions"),
                        Map.of(), rules("minecraft:arrow", "#minecraft:arrows")),
                setting(ClientPreferences.VEHICLE_MODEL_EXCLUSIONS,
                        path("common", "models", "exclusions", "vehicleModelExclusions"),
                        Map.of(), rules("minecraft:boat", "#minecraft:boats")),
                setting(ClientPreferences.USE_YSM_HELD_ITEM_SWITCH_ANIMATIONS,
                        path("common", "animations", "useYsmHeldItemSwitchAnimations"), true, false),
                setting(ClientPreferences.USE_YSM_MOVEMENT_ANIMATIONS,
                        path("common", "animations", "useYsmMovementAnimations"), true, false),
                setting(ClientPreferences.USE_NATURAL_LADDER_ANIMATIONS,
                        path("common", "animations", "useNaturalLadderAnimations"), true, false),
                new Setting(ClientPreferences.USE_YSM_PARCOOL_ANIMATIONS,
                        path("common", "animations", "useYsmParCoolAnimations"), true, false, PARCOOL),
                new Setting(ClientPreferences.USE_YSM_SWEM_ANIMATIONS,
                        path("common", "animations", "useYsmSwemAnimations"), true, false, SWEM),
                setting(ClientPreferences.HELD_ITEM_SWITCH_ANIMATION_EXCLUSIONS,
                        path("common", "animations", "exclusions", "heldItemSwitchAnimationExclusions"),
                        Map.of(), rules("minecraft:air", "#forge:tools/pickaxes")),
                setting(ClientPreferences.MOVEMENT_ANIMATION_EXCLUSIONS,
                        path("common", "animations", "exclusions", "movementAnimationExclusions"),
                        Map.of(), rules("run", "creative_flight")),
                new Setting(ClientPreferences.PARCOOL_ANIMATION_EXCLUSIONS,
                        path("common", "animations", "exclusions", "parcoolAnimationExclusions"),
                        Map.of(), rules("fast_running", "hang"), PARCOOL),
                new Setting(ClientPreferences.SWEM_ANIMATION_EXCLUSIONS,
                        path("common", "animations", "exclusions", "swemAnimationExclusions"),
                        Map.of(), rules("gallop", "jump_lv1"), SWEM),
                setting(ClientPreferences.YSM_WARNING_ACKNOWLEDGED,
                        path("client", "epicFightCompatibilityWarningShown"), false, true));
    }

    private static List<Setting> serverSettings() {
        return List.of(
                setting(ServerPreferences.SERVER_MODEL_DISK_CACHE_ENABLED,
                        path("server", "cache", "serverModelDiskCacheEnabled"), true, false),
                setting(ServerPreferences.SERVER_MODEL_DISK_CACHE_MIB,
                        path("server", "cache", "serverModelDiskCacheMiB"), 256, 384));
    }

    private static List<Setting> optionalSettings() {
        return clientSettings().stream().filter(setting -> setting.optionalFamily() != null).toList();
    }

    private static ForgeConfigSpec absentOptionalSpec() {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
        builder.push("client").push("cache");
        builder.define("clientModelDiskCacheMiB", 64);
        builder.pop().pop().push("common").push("animations");
        OptionalModConfig.defineBoolean(builder, "useYsmParCoolAnimations", false, "ParCool.");
        OptionalModConfig.defineBoolean(builder, "useYsmSwemAnimations", false, "SWEM.");
        builder.push("exclusions");
        OptionalModConfig.defineExclusions(builder, "parcoolAnimationExclusions", PARCOOL,
                false, "ParCool exclusions.");
        OptionalModConfig.defineExclusions(builder, "swemAnimationExclusions", SWEM,
                false, "SWEM exclusions.");
        builder.pop().pop().pop();
        return builder.build();
    }

    private static Setting setting(ForgeConfigSpec.ConfigValue<?> value, List<String> path,
                                   Object defaultValue, Object customValue) {
        return new Setting(value, path, defaultValue, customValue, null);
    }

    private record Setting(ForgeConfigSpec.ConfigValue<?> value, List<String> path,
                           Object defaultValue, Object customValue, ModAnimationType optionalFamily) {
        private String leaf() {
            return path.get(path.size() - 1);
        }

        private List<String> flatPath() {
            return List.of(path.get(0).equals("common") ? "client" : path.get(0), leaf());
        }

        private List<String> previousCategoryPath() {
            if (path.get(0).equals("common")) {
                List<String> previous = new ArrayList<>(path);
                previous.set(0, "client");
                return List.copyOf(previous);
            }
            return switch (leaf()) {
                case "suppressBattleModeOverlay" -> List.of("client", "rendering", leaf());
                case "epicFightCompatibilityWarningShown" -> List.of("client", "notifications", leaf());
                default -> path;
            };
        }

        private boolean generatedByDefault() {
            return optionalFamily == null || ClientPreferences.isOptionalAnimationAvailable(optionalFamily);
        }
    }

    private record Written(String toml, CommentedConfig parsed) {
    }
}
