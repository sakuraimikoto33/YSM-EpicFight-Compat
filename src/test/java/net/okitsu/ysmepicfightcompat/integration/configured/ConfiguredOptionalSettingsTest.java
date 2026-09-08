package net.okitsu.ysmepicfightcompat.integration.configured;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.core.Config;
import com.electronwill.nightconfig.core.UnmodifiableConfig;
import com.mrcrayfish.configured.impl.forge.ForgeConfig;
import net.minecraftforge.common.ForgeConfigSpec;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfiguredOptionalSettingsTest {
    private static final List<String> PARCOOL_TOGGLE =
            List.of("common", "animations", "useYsmParCoolAnimations");
    private static final List<String> PARCOOL_RULES =
            List.of("common", "animations", "exclusions", "parcoolAnimationExclusions");
    private static final List<String> SWEM_TOGGLE =
            List.of("common", "animations", "useYsmSwemAnimations");
    private static final List<String> SWEM_RULES =
            List.of("common", "animations", "exclusions", "swemAnimationExclusions");
    private static final List<String> MOVEMENT_TOGGLE =
            List.of("common", "animations", "useYsmMovementAnimations");

    @Test
    void filtersConfiguredsRealForgeValueRecordsForEveryAvailabilityCombination() {
        Fixture fixture = fixture();
        List<?> original = ForgeValues.entries(fixture.spec());
        List<?> snapshot = List.copyOf(original);

        for (boolean parCoolAvailable : List.of(false, true)) {
            for (boolean swemAvailable : List.of(false, true)) {
                List<?> filtered = ConfiguredOptionalSettings.visibleForgeValues(
                        original, parCoolAvailable, swemAvailable);
                for (Object entry : original) {
                    List<String> path = ForgeValues.path(entry);
                    boolean expected = path.equals(PARCOOL_TOGGLE) || path.equals(PARCOOL_RULES)
                            ? parCoolAvailable : path.equals(SWEM_TOGGLE) || path.equals(SWEM_RULES)
                            ? swemAvailable : true;
                    assertEquals(expected ? 1L : 0L,
                            filtered.stream().filter(candidate -> candidate == entry).count(),
                            path.toString());
                }
                assertEquals(snapshot, original);
            }
        }
    }

    @Test
    void keepsUnknownRecordsAndSameNamedSettingsOutsideTheExactCommonPaths() {
        Fixture fixture = fixture();
        List<Object> original = new ArrayList<>(ForgeValues.entries(fixture.spec()));
        Object foreign = new Object();
        original.add(foreign);

        List<?> filtered = ConfiguredOptionalSettings.visibleForgeValues(original, false, false);

        assertSame(foreign, filtered.get(filtered.size() - 1));
        assertTrue(filtered.stream().filter(entry -> entry != foreign).map(ForgeValues::path)
                .toList().containsAll(List.of(MOVEMENT_TOGGLE,
                        List.of("common", "animations", "futureAnimations"),
                        List.of("common", "animations", "nested", "useYsmParCoolAnimations"),
                        List.of("common", "useYsmParCoolAnimations"),
                        List.of("client", "useYsmParCoolAnimations"),
                        List.of("client", "animations", "useYsmParCoolAnimations"),
                        List.of("client", "animations", "exclusions", "parcoolAnimationExclusions"),
                        List.of("common", "models", "useYsmSwemAnimations"),
                        List.of("common", "models", "exclusions", "parcoolAnimationExclusions"),
                        List.of("server", "animations", "useYsmSwemAnimations"),
                        List.of("other", "useYsmSwemAnimations"))));
    }

    @Test
    void configuredGlobalResetSequencePreservesEveryHiddenChangedSetting() {
        Fixture fixture = fixture();
        fixture.data().set(PARCOOL_TOGGLE, false);
        fixture.data().set(SWEM_TOGGLE, false);
        fixture.data().set(MOVEMENT_TOGGLE, false);
        Config parCoolRules = rules("hang");
        Config swemRules = rules("canter");
        fixture.data().set(PARCOOL_RULES, parCoolRules);
        fixture.data().set(SWEM_RULES, swemRules);
        fixture.data().setComment(PARCOOL_RULES, "Keep my saved ParCool exclusions");
        fixture.spec().afterReload();
        List<?> filtered = ConfiguredOptionalSettings.visibleForgeValues(
                ForgeValues.entries(fixture.spec()), false, false);

        ForgeValues.restoreDefaults(fixture.data(), filtered);

        assertEquals(false, fixture.data().get(PARCOOL_TOGGLE));
        assertEquals(false, fixture.data().get(SWEM_TOGGLE));
        assertSame(parCoolRules, fixture.data().get(PARCOOL_RULES));
        assertSame(swemRules, fixture.data().get(SWEM_RULES));
        assertEquals(List.of("hang"), parCoolRules.get(List.of("example/model")));
        assertEquals(List.of("canter"), swemRules.get(List.of("example/model")));
        assertEquals("Keep my saved ParCool exclusions", fixture.data().getComment(PARCOOL_RULES));
        assertEquals(true, fixture.data().get(MOVEMENT_TOGGLE));
    }

    @Test
    void globalResetStillResetsTheInstalledFamilyAndOrdinarySettings() {
        Fixture fixture = fixture();
        fixture.data().set(PARCOOL_TOGGLE, false);
        fixture.data().set(SWEM_TOGGLE, false);
        fixture.data().set(MOVEMENT_TOGGLE, false);
        fixture.data().set(PARCOOL_RULES, rules("hang"));
        Config swemRules = rules("canter");
        fixture.data().set(SWEM_RULES, swemRules);
        fixture.spec().afterReload();

        ForgeValues.restoreDefaults(fixture.data(), ConfiguredOptionalSettings.visibleForgeValues(
                ForgeValues.entries(fixture.spec()), true, false));

        assertEquals(true, fixture.data().get(PARCOOL_TOGGLE));
        assertTrue(((UnmodifiableConfig) fixture.data().get(PARCOOL_RULES)).isEmpty());
        assertEquals(false, fixture.data().get(SWEM_TOGGLE));
        assertSame(swemRules, fixture.data().get(SWEM_RULES));
        assertEquals(true, fixture.data().get(MOVEMENT_TOGGLE));
    }

    @Test
    void hiddenStoredPreferencesDoNotMarkConfiguredsGlobalResetListChanged() {
        Fixture fixture = fixture();
        fixture.data().set(PARCOOL_TOGGLE, false);
        fixture.data().set(SWEM_RULES, rules("canter"));
        fixture.spec().afterReload();
        List<?> all = ForgeValues.entries(fixture.spec());

        assertTrue(ForgeValues.isChanged(all));
        assertFalse(ForgeValues.isChanged(
                ConfiguredOptionalSettings.visibleForgeValues(all, false, false)));
        assertTrue(ForgeValues.isChanged(
                ConfiguredOptionalSettings.visibleForgeValues(all, true, false)));
        assertTrue(ForgeValues.isChanged(
                ConfiguredOptionalSettings.visibleForgeValues(all, false, true)));
    }

    private static Fixture fixture() {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
        builder.push("common").push("animations");
        builder.define("useYsmParCoolAnimations", true);
        builder.define("useYsmSwemAnimations", true);
        builder.define("useYsmMovementAnimations", true);
        builder.define("futureAnimations", true);
        builder.push("exclusions");
        builder.define("parcoolAnimationExclusions", Config::inMemory,
                value -> value instanceof UnmodifiableConfig);
        builder.define("swemAnimationExclusions", Config::inMemory,
                value -> value instanceof UnmodifiableConfig);
        builder.pop();
        builder.push("nested").define("useYsmParCoolAnimations", true);
        builder.pop(2);
        builder.define("useYsmParCoolAnimations", true);
        builder.push("models").define("useYsmSwemAnimations", true);
        builder.push("exclusions").define("parcoolAnimationExclusions", Config::inMemory,
                value -> value instanceof UnmodifiableConfig);
        builder.pop(3);
        builder.push("client").define("useYsmParCoolAnimations", true);
        builder.push("animations").define("useYsmParCoolAnimations", true);
        builder.push("exclusions").define("parcoolAnimationExclusions", Config::inMemory,
                value -> value instanceof UnmodifiableConfig);
        builder.pop(3);
        builder.push("server").push("animations").define("useYsmSwemAnimations", true);
        builder.pop(2);
        builder.push("other").define("useYsmSwemAnimations", true);
        builder.pop();
        ForgeConfigSpec spec = builder.build();
        CommentedConfig data = CommentedConfig.inMemory();
        spec.correct(data);
        spec.acceptConfig(data);
        return new Fixture(spec, data);
    }

    private static Config rules(String selector) {
        Config config = Config.inMemory();
        config.set(List.of("example/model"), List.of(selector));
        return config;
    }

    private record Fixture(ForgeConfigSpec spec, CommentedConfig data) {
    }

    /** Read Configured's actual protected record type; no Minecraft client is constructed. */
    private abstract static class ForgeValues extends ForgeConfig {
        private ForgeValues(ForgeConfigSpec spec) {
            super(null, spec);
        }

        private static List<?> entries(ForgeConfigSpec spec) {
            try {
                Constructor<ForgeValueEntry> constructor = ForgeValueEntry.class.getDeclaredConstructor(
                        ForgeConfigSpec.ConfigValue.class, ForgeConfigSpec.ValueSpec.class);
                constructor.setAccessible(true);
                List<ForgeValueEntry> entries = new ArrayList<>();
                collectEntries(spec.getValues(), spec, constructor, entries);
                return List.copyOf(entries);
            } catch (ReflectiveOperationException exception) {
                throw new AssertionError("Could not create Configured's Forge-value record fixture", exception);
            }
        }

        /** Avoid ForgeConfigHelper, whose static reflection requires a running ModLauncher. */
        private static void collectEntries(UnmodifiableConfig values, ForgeConfigSpec spec,
                                           Constructor<ForgeValueEntry> constructor,
                                           List<ForgeValueEntry> entries)
                throws ReflectiveOperationException {
            for (Object candidate : values.valueMap().values()) {
                if (candidate instanceof ForgeConfigSpec.ConfigValue<?> value) {
                    entries.add(constructor.newInstance(value, spec.getRaw(value.getPath())));
                } else if (candidate instanceof UnmodifiableConfig nested) {
                    collectEntries(nested, spec, constructor, entries);
                }
            }
        }

        private static List<String> path(Object entry) {
            return ((ForgeValueEntry) entry).value().getPath();
        }

        /** Mirrors ForgeConfig.restoreDefaults's copy/patch/putAll/cache-clear sequence. */
        private static void restoreDefaults(CommentedConfig data, List<?> entries) {
            CommentedConfig copy = CommentedConfig.copy(data);
            for (Object entry : entries) {
                ForgeValueEntry value = (ForgeValueEntry) entry;
                copy.set(value.value().getPath(), value.spec().getDefault());
            }
            data.putAll(copy);
            for (Object entry : entries) {
                ((ForgeValueEntry) entry).value().clearCache();
            }
        }

        /** Mirrors ForgeConfig.isChanged against the same filtered Forge-value list. */
        private static boolean isChanged(List<?> entries) {
            return entries.stream().map(ForgeValueEntry.class::cast).anyMatch(entry ->
                    !Objects.equals(entry.value().get(), entry.spec().getDefault()));
        }
    }
}
