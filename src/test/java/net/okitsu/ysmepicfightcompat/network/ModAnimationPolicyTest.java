package net.okitsu.ysmepicfightcompat.network;

import com.electronwill.nightconfig.core.Config;
import com.electronwill.nightconfig.toml.TomlParser;
import com.electronwill.nightconfig.toml.TomlWriter;
import net.okitsu.ysmepicfightcompat.animation.ModAnimationClips;
import net.okitsu.ysmepicfightcompat.animation.ModAnimationType;
import org.junit.jupiter.api.Test;

import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static net.okitsu.ysmepicfightcompat.animation.ModAnimationType.PARCOOL;
import static net.okitsu.ysmepicfightcompat.animation.ModAnimationType.SWEM;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModAnimationPolicyTest {
    private static final String MODEL = "wine_fox/21_saint";
    private static final String OTHER_MODEL = "wine_fox/05_magical";

    @Test
    void exposesOnlyTheImmutableOfficialFamilyVocabularies() {
        assertEquals(38, ModAnimationPolicy.maxSelectorsPerModel(PARCOOL));
        assertEquals(11, ModAnimationPolicy.maxSelectorsPerModel(SWEM));
        assertEquals(0, ModAnimationPolicy.maxSelectorsPerModel(null));
        assertThrows(UnsupportedOperationException.class,
                () -> ModAnimationClips.selectors(PARCOOL).add("invented"));
        assertThrows(UnsupportedOperationException.class,
                () -> ModAnimationClips.selectors(SWEM).add("invented"));
        for (ModAnimationType type : ModAnimationType.values()) {
            ModAnimationPolicy enabled = ModAnimationPolicy.create(type, true, Map.of());
            for (String selector : ModAnimationClips.selectors(type)) {
                assertTrue(ModAnimationPolicy.isValidSelector(type,
                        "  " + selector.toUpperCase(Locale.ROOT) + "  "));
                assertTrue(enabled.usesYsm(MODEL, canonical(type, selector)));
            }
        }
    }

    @Test
    void exclusionsNeverEnableAFamilyWhoseMasterSwitchIsOff() {
        for (ModAnimationType type : ModAnimationType.values()) {
            ModAnimationPolicy disabled = ModAnimationPolicy.create(type, false,
                    Map.of(MODEL, List.of(firstSelector(type))));
            for (String selector : ModAnimationClips.selectors(type)) {
                assertFalse(disabled.usesYsm(MODEL, canonical(type, selector)));
                assertFalse(disabled.usesYsm(OTHER_MODEL, canonical(type, selector)));
            }
        }
    }

    @Test
    void exclusionsApplyOnlyToTheSelectedModelClipAndFamily() {
        ModAnimationPolicy parcool = ModAnimationPolicy.create(PARCOOL, true,
                Map.of(" WINE_FOX/21_SAINT ", List.of(" FAST_RUNNING ", "hang")));
        ModAnimationPolicy swem = ModAnimationPolicy.create(SWEM, true,
                Map.of(MODEL, List.of(" GALLOP ", "jump_lv1")));

        assertFalse(parcool.usesYsm(MODEL, "parcool:fast_running"));
        assertFalse(parcool.usesYsm(" WINE_FOX/21_SAINT ", "parcool:hang"));
        assertTrue(parcool.usesYsm(MODEL, "parcool:roll_front"));
        assertTrue(parcool.usesYsm(OTHER_MODEL, "parcool:fast_running"));
        assertFalse(swem.usesYsm(MODEL, "swem:gallop"));
        assertFalse(swem.usesYsm(MODEL, "swem:jump_lv1"));
        assertTrue(swem.usesYsm(MODEL, "swem:jump_lv2"));
        assertTrue(swem.usesYsm(OTHER_MODEL, "swem:gallop"));
        assertFalse(parcool.usesYsm(MODEL, "swem:gallop"));
        assertFalse(swem.usesYsm(MODEL, "parcool:fast_running"));
    }

    @Test
    void runtimeQueriesRequireKnownCanonicalClips() {
        ModAnimationPolicy policy = ModAnimationPolicy.create(PARCOOL, true, Map.of());
        for (String clip : new String[]{null, "", "hang", "PARCOOL:hang", " parcool:hang ",
                "parcool:invented", "parcool:gallop", "swem:gallop", "other:hang"}) {
            assertFalse(policy.usesYsm(MODEL, clip), String.valueOf(clip));
        }
    }

    @Test
    void invalidRuntimeModelIdsNeverEnableYsm() {
        ModAnimationPolicy policy = ModAnimationPolicy.create(PARCOOL, true, Map.of());
        for (String modelId : new String[]{null, "", "  ", "bad\nmodel",
                "x".repeat(ModAnimationPolicy.MAX_MODEL_ID_LENGTH + 1)}) {
            assertFalse(policy.usesYsm(modelId, "parcool:hang"));
        }
        assertTrue(policy.usesYsm(" WINE_FOX/21_SAINT ", "parcool:hang"));
    }

    @Test
    void roundTripsNormalizedRulesForEachFamily() {
        for (ModAnimationType type : ModAnimationType.values()) {
            String selector = firstSelector(type);
            Map<String, List<String>> source = Map.of(" WINE_FOX/21_SAINT ",
                    List.of(" " + selector.toUpperCase(Locale.ROOT) + " ", selector));
            Config encoded = ModAnimationPolicy.encodeConfiguration(type, source);
            assertTrue(ModAnimationPolicy.isValidConfiguration(type, encoded));
            assertEquals(Map.of(MODEL, List.of(selector)),
                    ModAnimationPolicy.decodeConfiguration(type, encoded));
        }
    }

    @Test
    void tomlRoundTripKeepsDotsSlashesAndUnicodeInOneModelId() {
        Map<String, List<String>> source = Map.of("Wine.Fox/モデル", List.of("fast_running", "hang"));
        Config encoded = ModAnimationPolicy.encodeConfiguration(PARCOOL, source);
        Config root = Config.inMemory();
        root.set(List.of("common", "animations", "exclusions", "parcoolAnimationExclusions"), encoded);
        StringWriter output = new StringWriter();
        new TomlWriter().write(root, output);
        String toml = output.toString();
        assertTrue(toml.contains("[common.animations.exclusions.parcoolAnimationExclusions]"));
        assertTrue(toml.contains("\"wine.fox/モデル\" = ["));

        Config decoded = new TomlParser().parse(toml);
        assertEquals(Map.of("wine.fox/モデル", List.of("fast_running", "hang")),
                ModAnimationPolicy.decodeConfiguration(PARCOOL,
                        decoded.get(List.of("common", "animations", "exclusions", "parcoolAnimationExclusions"))));
    }

    @Test
    void policyAndDecodedRulesAreImmutableSnapshots() {
        List<String> selectors = new ArrayList<>(List.of("hang"));
        Map<String, List<String>> source = new LinkedHashMap<>();
        source.put(MODEL, selectors);
        ModAnimationPolicy policy = ModAnimationPolicy.create(PARCOOL, true, source);
        Config encoded = ModAnimationPolicy.encodeConfiguration(PARCOOL, source);
        Map<String, List<String>> decoded = ModAnimationPolicy.decodeConfiguration(PARCOOL, encoded);
        selectors.clear();
        source.clear();
        encoded.clear();
        assertFalse(policy.usesYsm(MODEL, "parcool:hang"));
        assertEquals(Map.of(MODEL, List.of("hang")), decoded);
        assertThrows(UnsupportedOperationException.class, () -> decoded.put(OTHER_MODEL, List.of()));
        assertThrows(UnsupportedOperationException.class, () -> decoded.get(MODEL).add("tap"));
    }

    @Test
    void rejectsNonConfigurationContainers() {
        for (Object value : new Object[]{null, 1, "hang", List.of("hang"), Map.of(MODEL, List.of("hang"))}) {
            assertFalse(ModAnimationPolicy.isValidConfiguration(PARCOOL, value));
            assertEquals(Map.of(), ModAnimationPolicy.decodeConfiguration(PARCOOL, value));
        }
    }

    @Test
    void rejectsWrongSelectorTypesAndNestedConfigurationInsteadOfPartialRules() {
        for (Object value : new Object[]{null, "hang", 1, true, List.of(1), List.of("hang", false),
                Set.of("hang"), Map.of("nested", "hang"), Config.inMemory(), Arrays.asList("hang", null)}) {
            Config configured = configWith(MODEL, List.of("hang"));
            configured.set(List.of(OTHER_MODEL), value);
            assertFalse(ModAnimationPolicy.isValidConfiguration(PARCOOL, configured));
            assertEquals(Map.of(), ModAnimationPolicy.decodeConfiguration(PARCOOL, configured));
        }
        assertThrows(IllegalArgumentException.class,
                () -> ModAnimationPolicy.create(PARCOOL, true, Map.of(MODEL, List.of(1))));
        Map<String, Collection<?>> nullValues = new LinkedHashMap<>();
        nullValues.put(MODEL, null);
        assertThrows(IllegalArgumentException.class,
                () -> ModAnimationPolicy.create(PARCOOL, true, nullValues));
    }

    @Test
    void acceptsOnlyShortSameFamilySelectorsAcrossAllConfigurationEntrypoints() {
        for (ModAnimationType type : ModAnimationType.values()) {
            String otherFamilySelector = type == PARCOOL ? "gallop" : "hang";
            for (String selector : new String[]{"", " ", "*", "invented", otherFamilySelector,
                    canonical(type, firstSelector(type)), "animation." + canonical(type, firstSelector(type))}) {
                assertFalse(ModAnimationPolicy.isValidSelector(type, selector));
                Map<String, List<String>> source = Map.of(MODEL, List.of(firstSelector(type), selector));
                assertThrows(IllegalArgumentException.class,
                        () -> ModAnimationPolicy.create(type, false, source));
                assertThrows(IllegalArgumentException.class,
                        () -> ModAnimationPolicy.encodeConfiguration(type, source));
                assertFalse(ModAnimationPolicy.isValidConfiguration(type, configWith(MODEL, source.get(MODEL))));
            }
            assertFalse(ModAnimationPolicy.isValidSelector(type, null));
            assertFalse(ModAnimationPolicy.isValidSelector(type, 1));
            assertFalse(ModAnimationPolicy.isValidSelector(type, true));
        }
    }

    @Test
    void rejectsNormalizedModelIdCollisionsBeforeEncodingCanOverwriteThem() {
        Map<String, List<String>> source = Map.of(
                MODEL, List.of("hang"), " WINE_FOX/21_SAINT ", List.of("fast_running"));
        assertThrows(IllegalArgumentException.class,
                () -> ModAnimationPolicy.create(PARCOOL, true, source));
        assertThrows(IllegalArgumentException.class,
                () -> ModAnimationPolicy.encodeConfiguration(PARCOOL, source));
        Config configured = configWith(MODEL, List.of("hang"));
        configured.set(List.of(" WINE_FOX/21_SAINT "), List.of("fast_running"));
        assertFalse(ModAnimationPolicy.isValidConfiguration(PARCOOL, configured));
        assertEquals(Map.of(), ModAnimationPolicy.decodeConfiguration(PARCOOL, configured));
    }

    @Test
    void rejectsUnsafeEmptyAndOverlongConfiguredModelIds() {
        for (String modelId : new String[]{"", "  ", "bad\nmodel", "bad\u0000model",
                "x".repeat(ModAnimationPolicy.MAX_MODEL_ID_LENGTH + 1)}) {
            Map<String, List<String>> source = Map.of(modelId, List.of("hang"));
            assertThrows(IllegalArgumentException.class,
                    () -> ModAnimationPolicy.create(PARCOOL, true, source));
            assertThrows(IllegalArgumentException.class,
                    () -> ModAnimationPolicy.encodeConfiguration(PARCOOL, source));
            assertFalse(ModAnimationPolicy.isValidConfiguration(PARCOOL, configWith(modelId, List.of("hang"))));
        }
        assertFalse(ModAnimationPolicy.isValidModelId(null));
    }

    @Test
    void enforcesModelCountLimitOnEveryConfigurationEntrypoint() {
        Map<String, List<String>> source = new LinkedHashMap<>();
        Config configured = Config.inMemory();
        for (int index = 0; index <= ModAnimationPolicy.MAX_MODELS; index++) {
            source.put("model/" + index, List.of("hang"));
            configured.set(List.of("model/" + index), List.of("hang"));
        }
        assertThrows(IllegalArgumentException.class,
                () -> ModAnimationPolicy.create(PARCOOL, true, source));
        assertThrows(IllegalArgumentException.class,
                () -> ModAnimationPolicy.encodeConfiguration(PARCOOL, source));
        assertFalse(ModAnimationPolicy.isValidConfiguration(PARCOOL, configured));
        assertEquals(Map.of(), ModAnimationPolicy.decodeConfiguration(PARCOOL, configured));
    }

    @Test
    void enforcesFamilySelectorLimitsBeforeRemovingDuplicates() {
        for (ModAnimationType type : ModAnimationType.values()) {
            List<String> selectors = Collections.nCopies(
                    ModAnimationPolicy.maxSelectorsPerModel(type) + 1, firstSelector(type));
            Map<String, List<String>> source = Map.of(MODEL, selectors);
            assertThrows(IllegalArgumentException.class,
                    () -> ModAnimationPolicy.create(type, true, source));
            assertThrows(IllegalArgumentException.class,
                    () -> ModAnimationPolicy.encodeConfiguration(type, source));
            assertFalse(ModAnimationPolicy.isValidConfiguration(type, configWith(MODEL, selectors)));
        }
    }

    @Test
    void acceptsExactModelAndSelectorBoundaries() {
        for (ModAnimationType type : ModAnimationType.values()) {
            Map<String, List<String>> source = new LinkedHashMap<>();
            List<String> selectors = List.copyOf(ModAnimationClips.selectors(type));
            String longestId = "x".repeat(ModAnimationPolicy.MAX_MODEL_ID_LENGTH);
            source.put(longestId, selectors);
            for (int index = 1; index < ModAnimationPolicy.MAX_MODELS; index++) {
                source.put("model/" + index, selectors);
            }
            Config configured = ModAnimationPolicy.encodeConfiguration(type, source);
            assertTrue(ModAnimationPolicy.isValidConfiguration(type, configured));
            assertEquals(source, ModAnimationPolicy.decodeConfiguration(type, configured));
            ModAnimationPolicy policy = ModAnimationPolicy.create(type, true, source);
            assertFalse(policy.usesYsm(longestId, canonical(type, firstSelector(type))));
        }
    }

    @Test
    void nullRuleMapsMeanEmptyExclusionsWithoutChangingTheMasterSwitch() {
        for (ModAnimationType type : ModAnimationType.values()) {
            assertTrue(ModAnimationPolicy.create(type, true, null)
                    .usesYsm(MODEL, canonical(type, firstSelector(type))));
            assertFalse(ModAnimationPolicy.create(type, false, null)
                    .usesYsm(MODEL, canonical(type, firstSelector(type))));
            Config encoded = ModAnimationPolicy.encodeConfiguration(type, null);
            assertTrue(ModAnimationPolicy.isValidConfiguration(type, encoded));
            assertEquals(Map.of(), ModAnimationPolicy.decodeConfiguration(type, encoded));
        }
    }

    @Test
    void missingFamilyNeverAcceptsConfigurationOrSelectors() {
        assertFalse(ModAnimationPolicy.isValidSelector(null, "hang"));
        assertFalse(ModAnimationPolicy.isValidConfiguration(null, Config.inMemory()));
        assertEquals(Map.of(), ModAnimationPolicy.decodeConfiguration(null, Config.inMemory()));
        assertThrows(IllegalArgumentException.class,
                () -> ModAnimationPolicy.create(null, true, Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> ModAnimationPolicy.encodeConfiguration(null, Map.of()));
    }

    private static Config configWith(String modelId, Object selectors) {
        Config configured = Config.inMemory();
        configured.set(List.of(modelId), selectors);
        return configured;
    }

    private static String firstSelector(ModAnimationType type) {
        return type == PARCOOL ? "hang" : "gallop";
    }

    private static String canonical(ModAnimationType type, String selector) {
        return type.name().toLowerCase(Locale.ROOT) + ":" + selector;
    }
}
