package net.okitsu.ysmepicfightcompat.config;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.core.Config;
import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.electronwill.nightconfig.toml.TomlParser;
import com.electronwill.nightconfig.toml.TomlWriter;
import net.minecraftforge.common.ForgeConfigSpec;
import net.okitsu.ysmepicfightcompat.network.ModAnimationPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static net.okitsu.ysmepicfightcompat.animation.ModAnimationType.PARCOOL;
import static net.okitsu.ysmepicfightcompat.animation.ModAnimationType.SWEM;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OptionalModConfigTest {
    private static final String PARCOOL_TOGGLE = "useYsmParCoolAnimations";
    private static final String PARCOOL_RULES = "parcoolAnimationExclusions";
    private static final String SWEM_TOGGLE = "useYsmSwemAnimations";
    private static final String SWEM_RULES = "swemAnimationExclusions";
    private static final List<String> OPTIONAL_KEYS = List.of(
            PARCOOL_TOGGLE, PARCOOL_RULES, SWEM_TOGGLE, SWEM_RULES);
    private static final String MODEL = "wine.fox/モデル";
    private static final Map<String, List<String>> PARCOOL_CUSTOM =
            Map.of(MODEL, List.of("fast_running", "hang"));
    private static final Map<String, List<String>> SWEM_CUSTOM =
            Map.of(MODEL, List.of("gallop", "jump_lv1"));

    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void generatesOnlyInstalledModDefaults(boolean parcool, boolean swem) {
        Fixture fixture = fixture(parcool, swem);
        CommentedConfig config = CommentedConfig.inMemory();

        fixture.spec.correct(config);

        assertEquals(parcool, config.contains(path(PARCOOL_TOGGLE)));
        assertEquals(parcool, config.contains(path(PARCOOL_RULES)));
        assertEquals(swem, config.contains(path(SWEM_TOGGLE)));
        assertEquals(swem, config.contains(path(SWEM_RULES)));
        assertEquals(64, config.<Integer>get(path("normalSetting")));
        assertTrue(fixture.spec.isCorrect(config));
        assertEquals(0, fixture.spec.correct(config));
    }

    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void serializedMissingKeysHaveNoGhostCommentsOrRepeatedCorrections(
            boolean parcool, boolean swem) {
        Fixture fixture = fixture(parcool, swem);
        CommentedConfig config = CommentedConfig.inMemory();
        fixture.spec.correct(config);

        CommentedConfig parsed = roundTrip(config);

        assertTrue(fixture.spec.isCorrect(parsed));
        assertEquals(0, fixture.spec.correct(parsed));
        assertTrue(fixture.spec.isCorrect(roundTrip(parsed)));
        for (String key : OPTIONAL_KEYS) {
            boolean available = key.equals(PARCOOL_TOGGLE) || key.equals(PARCOOL_RULES)
                    ? parcool : swem;
            ForgeConfigSpec.ValueSpec valueSpec =
                    (ForgeConfigSpec.ValueSpec) fixture.spec.getRaw(path(key));
            assertEquals(!available, valueSpec.test(null), key);
            if (!available) {
                assertNull(valueSpec.getComment(), key);
                assertNull(parsed.getComment(path(key)), key);
                assertFalse(parsed.contains(path(key)), key);
            }
        }
    }

    @Test
    void readingMissingOptionalValuesReturnsDefaultsWithoutInsertingKeys() {
        Fixture fixture = fixture(false, false);
        CommentedConfig config = CommentedConfig.inMemory();
        fixture.spec.setConfig(config);

        for (int read = 0; read < 2; read++) {
            assertTrue(fixture.parcoolToggle.get());
            assertTrue(fixture.swemToggle.get());
            assertTrue(fixture.parcoolRules.get().isEmpty());
            assertTrue(fixture.swemRules.get().isEmpty());
            fixture.spec.afterReload();
        }

        assertNoOptionalKeys(config);
        assertNoOptionalKeys(roundTrip(config));
        assertTrue(fixture.spec.isCorrect(config));
    }

    @ParameterizedTest
    @ValueSource(strings = {PARCOOL_TOGGLE, PARCOOL_RULES, SWEM_TOGGLE, SWEM_RULES})
    void preservesAnExistingKeyWithoutGeneratingMissingSiblings(String existingKey) {
        Fixture fixture = fixture(false, false);
        CommentedConfig config = CommentedConfig.inMemory();
        Object customized = switch (existingKey) {
            case PARCOOL_RULES -> ModAnimationPolicy.encodeConfiguration(PARCOOL, PARCOOL_CUSTOM);
            case SWEM_RULES -> ModAnimationPolicy.encodeConfiguration(SWEM, SWEM_CUSTOM);
            default -> false;
        };
        config.set(path(existingKey), customized);

        fixture.spec.setConfig(config);
        CommentedConfig parsed = roundTrip(config);

        assertEquals(customized, parsed.getRaw(path(existingKey)));
        for (String key : OPTIONAL_KEYS) {
            assertEquals(key.equals(existingKey), parsed.contains(path(key)), key);
        }
        assertTrue(fixture.spec.isCorrect(parsed));
    }

    @Test
    void removingModsDoesNotDiscardExistingDefaultSettings() {
        Fixture installed = fixture(true, true);
        CommentedConfig config = CommentedConfig.inMemory();
        installed.spec.correct(config);
        Fixture absent = fixture(false, false);

        absent.spec.setConfig(config);
        CommentedConfig parsed = roundTrip(config);

        for (String key : OPTIONAL_KEYS) {
            assertTrue(parsed.contains(path(key)), key);
        }
        assertEquals(Boolean.TRUE, parsed.getRaw(path(PARCOOL_TOGGLE)));
        assertEquals(Boolean.TRUE, parsed.getRaw(path(SWEM_TOGGLE)));
        assertTrue(parsed.<Config>get(path(PARCOOL_RULES)).isEmpty());
        assertTrue(parsed.<Config>get(path(SWEM_RULES)).isEmpty());
        assertTrue(absent.spec.isCorrect(parsed));
    }

    @Test
    void installedModsStillCorrectInvalidValuesToTheirDefaults() {
        Fixture fixture = fixture(true, true);
        CommentedConfig config = CommentedConfig.inMemory();
        config.set(path(PARCOOL_TOGGLE), 42);
        config.set(path(SWEM_TOGGLE), "not-a-boolean");
        Config parcoolRules = Config.inMemory();
        parcoolRules.set(List.of(MODEL), List.of("gallop"));
        config.set(path(PARCOOL_RULES), parcoolRules);
        Config swemRules = Config.inMemory();
        swemRules.set(List.of(MODEL), List.of("hang"));
        config.set(path(SWEM_RULES), swemRules);

        fixture.spec.setConfig(config);

        assertTrue(fixture.parcoolToggle.get());
        assertTrue(fixture.swemToggle.get());
        assertTrue(fixture.parcoolRules.get().isEmpty());
        assertTrue(fixture.swemRules.get().isEmpty());
        assertTrue(fixture.spec.isCorrect(roundTrip(config)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"true", "false", "TRUE", "False"})
    void retainsStringBooleansAlreadyAcceptedByForge(String existingValue) {
        for (boolean available : List.of(false, true)) {
            Fixture fixture = fixture(available, available);
            CommentedConfig config = CommentedConfig.inMemory();
            config.set(path(PARCOOL_TOGGLE), existingValue);
            config.set(path(SWEM_TOGGLE), existingValue);

            fixture.spec.correct(config);

            assertEquals(existingValue, config.getRaw(path(PARCOOL_TOGGLE)));
            assertEquals(existingValue, config.getRaw(path(SWEM_TOGGLE)));
            assertTrue(fixture.spec.isCorrect(roundTrip(config)));
        }
    }

    @Test
    void savingAnUnrelatedChangeNeverCreatesAbsentModSettings(@TempDir Path directory)
            throws IOException {
        Path file = directory.resolve("client.toml");
        Fixture fixture = fixture(false, false);
        try (CommentedFileConfig config = open(file)) {
            config.load();
            fixture.spec.setConfig(config);
            assertTrue(fixture.parcoolToggle.get());
            assertTrue(fixture.swemRules.get().isEmpty());
            fixture.normalSetting.set(80);
            fixture.spec.save();
        } finally {
            fixture.spec.setConfig(null);
        }

        CommentedConfig saved = new TomlParser().parse(Files.readString(file));
        assertNoOptionalKeys(saved);
        assertEquals(80, saved.<Integer>get(path("normalSetting")));
        assertTrue(fixture.spec.isCorrect(saved));
    }

    @Test
    void customizedSettingsSurviveRemovalReloadAutosaveAndReinstallation(@TempDir Path directory)
            throws IOException {
        Path file = directory.resolve("client.toml");
        Fixture installed = fixture(true, true);
        try (CommentedFileConfig config = open(file)) {
            config.load();
            installed.spec.setConfig(config);
            installed.parcoolToggle.set(false);
            installed.swemToggle.set(false);
            installed.parcoolRules.set(ModAnimationPolicy.encodeConfiguration(PARCOOL, PARCOOL_CUSTOM));
            installed.swemRules.set(ModAnimationPolicy.encodeConfiguration(SWEM, SWEM_CUSTOM));
            installed.spec.save();
        } finally {
            installed.spec.setConfig(null);
        }

        Fixture absent = fixture(false, false);
        try (CommentedFileConfig config = open(file)) {
            config.load();
            absent.spec.setConfig(config);
            assertCustomized(config);
            absent.normalSetting.set(80);
            absent.spec.save();
            config.load();
            absent.spec.correct(config);
            absent.spec.afterReload();
            assertCustomized(config);
            assertFalse(absent.parcoolToggle.get());
            assertFalse(absent.swemToggle.get());
            assertTrue(absent.spec.isCorrect(config));
        } finally {
            absent.spec.setConfig(null);
        }

        CommentedConfig saved = new TomlParser().parse(Files.readString(file));
        assertCustomized(saved);
        assertEquals(80, saved.<Integer>get(path("normalSetting")));
        assertTrue(absent.spec.isCorrect(saved));

        Fixture reinstalled = fixture(true, true);
        try (CommentedFileConfig config = open(file)) {
            config.load();
            reinstalled.spec.setConfig(config);
            assertCustomized(config);
            assertFalse(reinstalled.parcoolToggle.get());
            assertFalse(reinstalled.swemToggle.get());
            assertEquals(PARCOOL_CUSTOM,
                    ModAnimationPolicy.decodeConfiguration(PARCOOL, reinstalled.parcoolRules.get()));
            assertEquals(SWEM_CUSTOM,
                    ModAnimationPolicy.decodeConfiguration(SWEM, reinstalled.swemRules.get()));
            assertTrue(reinstalled.spec.isCorrect(config));
        } finally {
            reinstalled.spec.setConfig(null);
        }
        assertCustomized(new TomlParser().parse(Files.readString(file)));
    }

    private static void assertCustomized(CommentedConfig config) {
        assertEquals(Boolean.FALSE, config.getRaw(path(PARCOOL_TOGGLE)));
        assertEquals(Boolean.FALSE, config.getRaw(path(SWEM_TOGGLE)));
        assertEquals(PARCOOL_CUSTOM,
                ModAnimationPolicy.decodeConfiguration(PARCOOL, config.getRaw(path(PARCOOL_RULES))));
        assertEquals(SWEM_CUSTOM,
                ModAnimationPolicy.decodeConfiguration(SWEM, config.getRaw(path(SWEM_RULES))));
    }

    private static void assertNoOptionalKeys(CommentedConfig config) {
        for (String key : OPTIONAL_KEYS) {
            assertFalse(config.contains(path(key)), key);
        }
    }

    private static CommentedFileConfig open(Path file) {
        return CommentedFileConfig.builder(file).sync().autosave().preserveInsertionOrder().build();
    }

    private static CommentedConfig roundTrip(CommentedConfig config) {
        StringWriter output = new StringWriter();
        new TomlWriter().write(config, output);
        return new TomlParser().parse(output.toString());
    }

    private static List<String> path(String key) {
        return List.of("client", key);
    }

    private static Fixture fixture(boolean parcool, boolean swem) {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
        builder.comment("Client preferences.").push("client");
        ForgeConfigSpec.ConfigValue<Integer> normalSetting = builder
                .comment("An unrelated ordinary preference.").define("normalSetting", 64);
        ForgeConfigSpec.ConfigValue<Boolean> parcoolToggle = OptionalModConfig.defineBoolean(
                builder, PARCOOL_TOGGLE, parcool, "ParCool animations.");
        ForgeConfigSpec.ConfigValue<Config> parcoolRules = OptionalModConfig.defineExclusions(
                builder, PARCOOL_RULES, PARCOOL, parcool, "ParCool animation exclusions.");
        ForgeConfigSpec.ConfigValue<Boolean> swemToggle = OptionalModConfig.defineBoolean(
                builder, SWEM_TOGGLE, swem, "SWEM animations.");
        ForgeConfigSpec.ConfigValue<Config> swemRules = OptionalModConfig.defineExclusions(
                builder, SWEM_RULES, SWEM, swem, "SWEM animation exclusions.");
        builder.pop();
        return new Fixture(builder.build(), normalSetting,
                parcoolToggle, parcoolRules, swemToggle, swemRules);
    }

    private record Fixture(ForgeConfigSpec spec,
                           ForgeConfigSpec.ConfigValue<Integer> normalSetting,
                           ForgeConfigSpec.ConfigValue<Boolean> parcoolToggle,
                           ForgeConfigSpec.ConfigValue<Config> parcoolRules,
                           ForgeConfigSpec.ConfigValue<Boolean> swemToggle,
                           ForgeConfigSpec.ConfigValue<Config> swemRules) {
    }
}
