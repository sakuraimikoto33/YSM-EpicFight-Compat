package net.okitsu.ysmepicfightcompat.integration.configured;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mrcrayfish.configured.api.ActionResult;
import com.mrcrayfish.configured.api.IConfigValue;
import net.okitsu.ysmepicfightcompat.animation.ModAnimationClips;
import net.okitsu.ysmepicfightcompat.animation.ModAnimationType;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfiguredHeldItemRulesTest {
    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void failedConfiguredSaveKeepsDynamicRuleEditsPending() throws ReflectiveOperationException {
        Class<?> kindType = Class.forName(ConfiguredHeldItemRules.class.getName() + "$RuleKind");
        Object kind = Enum.valueOf((Class) kindType, "PARCOOL");
        Class<?> folderType = Class.forName(ConfiguredHeldItemRules.class.getName() + "$RulesFolder");
        var constructor = folderType.getDeclaredConstructor(kindType, Map.class, String.class);
        constructor.setAccessible(true);
        Object folder = constructor.newInstance(kind,
                Map.of("example/model", List.of("roll_front")), "example/model");
        var valuesField = folderType.getDeclaredField("values");
        valuesField.setAccessible(true);
        IConfigValue<List<String>> value = (IConfigValue<List<String>>)
                ((Map<?, ?>) valuesField.get(folder)).get("example/model");
        value.set(List.of("hang"));

        ConfiguredHeldItemRules.finishSave(folder, ActionResult.fail());

        assertTrue(value.isChanged());
        assertEquals(List.of("hang"), value.get());
        ConfiguredHeldItemRules.finishSave(folder, ActionResult.success());
        assertFalse(value.isChanged());
        assertEquals(List.of("hang"), value.get());
    }

    @Test
    void recognizesEveryDynamicClientRuleTable() {
        assertTrue(ConfiguredHeldItemRules.recognizesRuleEntry(
                "heldItemModelExclusions"));
        assertTrue(ConfiguredHeldItemRules.recognizesRuleEntry(
                "projectileModelExclusions"));
        assertTrue(ConfiguredHeldItemRules.recognizesRuleEntry(
                "vehicleModelExclusions"));
        assertTrue(ConfiguredHeldItemRules.recognizesRuleEntry(
                "heldItemSwitchAnimationExclusions"));
        assertTrue(ConfiguredHeldItemRules.recognizesRuleEntry(
                "movementAnimationExclusions"));
        assertTrue(ConfiguredHeldItemRules.recognizesRuleEntry(
                "parcoolAnimationExclusions"));
        assertTrue(ConfiguredHeldItemRules.recognizesRuleEntry(
                "swemAnimationExclusions"));
        assertFalse(ConfiguredHeldItemRules.recognizesRuleEntry(
                "unknownDynamicRules"));
    }

    @Test
    void optionalAnimationListsAcceptOnlyTheirOwnShortNames() {
        assertTrue(ConfiguredHeldItemRules.acceptsRuleSelectors(
                "parcoolAnimationExclusions", new ArrayList<>(
                        ModAnimationClips.selectors(ModAnimationType.PARCOOL))));
        assertTrue(ConfiguredHeldItemRules.acceptsRuleSelectors(
                "swemAnimationExclusions", new ArrayList<>(
                        ModAnimationClips.selectors(ModAnimationType.SWEM))));
        assertTrue(ConfiguredHeldItemRules.acceptsRuleSelectors(
                "parcoolAnimationExclusions", List.of(" ROLL_FRONT ", "hang")));
        assertTrue(ConfiguredHeldItemRules.acceptsRuleSelectors(
                "swemAnimationExclusions", List.of(" CANTER_EXT ", "jump_lv5")));
        assertFalse(ConfiguredHeldItemRules.acceptsRuleSelectors(
                "parcoolAnimationExclusions", List.of("walk")));
        assertFalse(ConfiguredHeldItemRules.acceptsRuleSelectors(
                "swemAnimationExclusions", List.of("hang")));
        assertFalse(ConfiguredHeldItemRules.acceptsRuleSelectors(
                "movementAnimationExclusions", List.of("roll_front", "canter_ext")));
    }

    @Test
    void optionalAnimationListsRejectPrefixesWildcardsAndUnknownValues() {
        for (String entry : List.of("parcoolAnimationExclusions", "swemAnimationExclusions")) {
            for (Object invalid : List.of("*", "parcool:hang", "swem:walk", "unknown", "", 12)) {
                assertFalse(ConfiguredHeldItemRules.acceptsRuleSelectors(entry, List.of(invalid)));
            }
            assertFalse(ConfiguredHeldItemRules.acceptsRuleSelectors(entry, null));
            assertFalse(ConfiguredHeldItemRules.acceptsRuleSelectors(
                    entry, Collections.singletonList(null)));
            assertTrue(ConfiguredHeldItemRules.acceptsRuleSelectors(entry, List.of()));
        }
        assertFalse(ConfiguredHeldItemRules.acceptsRuleSelectors("unknownDynamicRules", List.of()));
    }

    @Test
    void optionalAnimationListsUseTheirIndependentVocabularyBounds() {
        assertTrue(ConfiguredHeldItemRules.acceptsRuleSelectors(
                "parcoolAnimationExclusions", Collections.nCopies(38, "roll_front")));
        assertFalse(ConfiguredHeldItemRules.acceptsRuleSelectors(
                "parcoolAnimationExclusions", Collections.nCopies(39, "roll_front")));
        assertTrue(ConfiguredHeldItemRules.acceptsRuleSelectors(
                "swemAnimationExclusions", Collections.nCopies(11, "walk")));
        assertFalse(ConfiguredHeldItemRules.acceptsRuleSelectors(
                "swemAnimationExclusions", Collections.nCopies(12, "walk")));
    }

    @Test
    void bothLanguagesListEverySupportedFamilySelectorExactlyOnce() throws IOException {
        for (String language : List.of("en_us", "ja_jp")) {
            try (InputStream input = getClass().getResourceAsStream(
                    "/assets/ysm_epicfight_compat/lang/" + language + ".json")) {
                assertNotNull(input);
                JsonObject translations = JsonParser.parseReader(
                        new InputStreamReader(input, StandardCharsets.UTF_8)).getAsJsonObject();
                assertSelectorHint(translations, "parcool", ModAnimationType.PARCOOL);
                assertSelectorHint(translations, "swem", ModAnimationType.SWEM);
            }
        }
    }

    private static void assertSelectorHint(
            JsonObject translations, String family, ModAnimationType type) {
        String hint = translations.get("config.ysm_epicfight_compat."
                + family + "_animation_entry.invalid").getAsString();
        String choices = hint.substring(hint.indexOf(':') + 1).trim();
        assertTrue(choices.endsWith("."));
        List<String> listed = Arrays.asList(choices.substring(0, choices.length() - 1).split(",\\s*"));
        assertEquals(ModAnimationClips.selectors(type), Set.copyOf(listed));
        assertEquals(listed.size(), Set.copyOf(listed).size());
    }
}
