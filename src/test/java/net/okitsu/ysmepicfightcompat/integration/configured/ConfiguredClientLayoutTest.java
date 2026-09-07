package net.okitsu.ysmepicfightcompat.integration.configured;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mrcrayfish.configured.api.IConfigEntry;
import com.mrcrayfish.configured.api.IConfigValue;
import com.mrcrayfish.configured.api.ValueEntry;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfiguredClientLayoutTest {
    private static final List<String> ANIMATION_EXCLUSIONS = List.of(
            "heldItemSwitchAnimationExclusions", "movementAnimationExclusions",
            "parcoolAnimationExclusions", "swemAnimationExclusions");
    private static final List<String> ANIMATION_VALUES = List.of(
            "useYsmHeldItemSwitchAnimations", "useYsmMovementAnimations",
            "useYsmParCoolAnimations", "useYsmSwemAnimations",
            "useNaturalLadderAnimations");
    private static final List<String> MODEL_EXCLUSIONS = List.of(
            "heldItemModelExclusions", "projectileModelExclusions", "vehicleModelExclusions");
    private static final List<String> MODEL_VALUES = List.of(
            "useYsmHeldItemModels", "useYsmProjectileModels", "useYsmVehicleModels");

    @Test
    void arrangesEveryKnownSettingIntoTheRequestedHierarchy() {
        List<IConfigEntry> original = allEntries();
        Collections.reverse(original);

        List<?> grouped = ConfiguredClientLayout.groupClientEntries(original);

        assertEquals(List.of("animations", "models"), names(grouped));
        IConfigEntry animations = entry(grouped, 0);
        IConfigEntry models = entry(grouped, 1);
        assertEquals(withExclusions("animation_exclusions", ANIMATION_VALUES),
                names(animations.getChildren()));
        assertEquals(ANIMATION_EXCLUSIONS,
                names(entry(animations.getChildren(), 0).getChildren()));
        assertEquals(withExclusions("model_exclusions", MODEL_VALUES),
                names(models.getChildren()));
        assertEquals(MODEL_EXCLUSIONS,
                names(entry(models.getChildren(), 0).getChildren()));
    }

    @Test
    void insertsBothGroupsAtTheFirstRecognizedSettingAndKeepsUnrelatedOrder() {
        IConfigEntry cache = folder("cache");
        IConfigEntry overlay = value("showDebugOverlay");
        IConfigEntry warning = value("showCompatibilityWarning");
        IConfigEntry unknown = folder("futureSettings");
        Object foreignEntry = new Object();
        List<Object> original = List.of(cache, value("useYsmVehicleModels"), overlay,
                folder("movementAnimationExclusions"), warning, unknown, foreignEntry);

        List<?> grouped = ConfiguredClientLayout.groupClientEntries(original);

        assertEquals(7, grouped.size());
        assertSame(cache, grouped.get(0));
        assertEquals("animations", entry(grouped, 1).getEntryName());
        assertEquals("models", entry(grouped, 2).getEntryName());
        assertSame(overlay, grouped.get(3));
        assertSame(warning, grouped.get(4));
        assertSame(unknown, grouped.get(5));
        assertSame(foreignEntry, grouped.get(6));
    }

    @Test
    void movesOriginalEntriesWithoutCopyingLosingOrDuplicatingThem() {
        List<IConfigEntry> original = allEntries();
        List<IConfigEntry> snapshot = List.copyOf(original);

        List<?> grouped = ConfiguredClientLayout.groupClientEntries(original);

        assertEquals(snapshot, original, "the incoming Forge entry list is not mutated");
        List<IConfigEntry> descendants = descendants(grouped);
        Set<IConfigEntry> identities = Collections.newSetFromMap(new IdentityHashMap<>());
        for (IConfigEntry existing : original) {
            assertEquals(1, descendants.stream().filter(node -> node == existing).count(),
                    existing.getEntryName());
            identities.add(existing);
        }
        assertEquals(original.size(), identities.size());
        assertEquals(original.size() + 4, descendants.size(),
                "only the four presentation folders are new");
    }

    @Test
    void assignsDistinctTranslationKeysToNonValuePresentationFolders() {
        List<?> grouped = ConfiguredClientLayout.groupClientEntries(allEntries());

        for (IConfigEntry folder : List.of(entry(grouped, 0), entry(grouped, 1),
                entry(entry(grouped, 0).getChildren(), 0),
                entry(entry(grouped, 1).getChildren(), 0))) {
            assertFalse(folder.isRoot());
            assertFalse(folder.isLeaf());
            assertNull(folder.getValue());
            assertEquals("config.ysm_epicfight_compat.configured." + folder.getEntryName(),
                    folder.getTranslationKey());
        }
    }

    @Test
    void configuredAlphabeticSortingKeepsExclusionsFirstAndNaturalLadderLastInBothLanguages()
            throws IOException {
        List<?> grouped = ConfiguredClientLayout.groupClientEntries(allEntries());

        for (String language : List.of("en_us", "ja_jp")) {
            try (InputStream input = getClass().getResourceAsStream(
                    "/assets/ysm_epicfight_compat/lang/" + language + ".json")) {
                assertNotNull(input);
                JsonObject translations = JsonParser.parseReader(
                        new InputStreamReader(input, StandardCharsets.UTF_8)).getAsJsonObject();
                List<IConfigEntry> displayedGroups = configuredSort(grouped, translations);
                assertEquals(List.of("animations", "models"), names(displayedGroups), language);

                List<IConfigEntry> animations = configuredSort(
                        displayedGroups.get(0).getChildren(), translations);
                assertEquals("animation_exclusions", animations.get(0).getEntryName(), language);
                assertEquals("useNaturalLadderAnimations",
                        name(animations.get(animations.size() - 1)), language);
                assertEquals(Set.copyOf(ANIMATION_VALUES),
                        Set.copyOf(names(animations.subList(1, animations.size()))), language);

                List<IConfigEntry> models = configuredSort(
                        displayedGroups.get(1).getChildren(), translations);
                assertEquals("model_exclusions", models.get(0).getEntryName(), language);
                assertEquals(Set.copyOf(MODEL_VALUES),
                        Set.copyOf(names(models.subList(1, models.size()))), language);
            }
        }
    }

    @Test
    void omitsModelsWhenOnlyAnimationsArePresent() {
        IConfigEntry exclusions = folder("swemAnimationExclusions");
        IConfigEntry preference = value("useYsmSwemAnimations");

        List<?> grouped = ConfiguredClientLayout.groupClientEntries(
                List.of(preference, exclusions));

        assertEquals(List.of("animations"), names(grouped));
        assertEquals(List.of("animation_exclusions", "useYsmSwemAnimations"),
                names(entry(grouped, 0).getChildren()));
        assertSame(exclusions, entry(entry(grouped, 0).getChildren(), 0).getChildren().get(0));
        assertSame(preference, entry(grouped, 0).getChildren().get(1));
    }

    @Test
    void omitsAnimationsWhenOnlyModelsArePresent() {
        IConfigEntry exclusions = folder("projectileModelExclusions");
        IConfigEntry preference = value("useYsmProjectileModels");

        List<?> grouped = ConfiguredClientLayout.groupClientEntries(
                List.of(preference, exclusions));

        assertEquals(List.of("models"), names(grouped));
        assertEquals(List.of("model_exclusions", "useYsmProjectileModels"),
                names(entry(grouped, 0).getChildren()));
        assertSame(exclusions, entry(entry(grouped, 0).getChildren(), 0).getChildren().get(0));
        assertSame(preference, entry(grouped, 0).getChildren().get(1));
    }

    @Test
    void booleanOnlyFamiliesDoNotCreateEmptyExclusionFolders() {
        IConfigEntry animation = value("useNaturalLadderAnimations");
        IConfigEntry model = value("useYsmHeldItemModels");

        List<?> grouped = ConfiguredClientLayout.groupClientEntries(List.of(model, animation));

        assertEquals(List.of("animations", "models"), names(grouped));
        assertEquals(List.of(animation), entry(grouped, 0).getChildren());
        assertEquals(List.of(model), entry(grouped, 1).getChildren());
    }

    @Test
    void exclusionOnlyFamiliesStillHaveBothLevelsWithoutInventedValues() {
        IConfigEntry animation = folder("parcoolAnimationExclusions");
        IConfigEntry model = folder("heldItemModelExclusions");

        List<?> grouped = ConfiguredClientLayout.groupClientEntries(List.of(model, animation));

        assertEquals(List.of("animations", "models"), names(grouped));
        assertEquals(List.of("animation_exclusions"), names(entry(grouped, 0).getChildren()));
        assertEquals(List.of("model_exclusions"), names(entry(grouped, 1).getChildren()));
        assertEquals(List.of(animation), entry(entry(grouped, 0).getChildren(), 0).getChildren());
        assertEquals(List.of(model), entry(entry(grouped, 1).getChildren(), 0).getChildren());
    }

    @Test
    void leavesEmptyAndUnrecognizedListsUntouched() {
        assertTrue(ConfiguredClientLayout.groupClientEntries(List.of()).isEmpty());
        IConfigEntry unknown = folder("anotherFolder", value("useYsmSwemAnimations"));
        Object foreignEntry = new Object();
        List<Object> original = List.of(unknown, value("showDebugOverlay"), foreignEntry);

        List<?> grouped = ConfiguredClientLayout.groupClientEntries(original);

        assertEquals(original.size(), grouped.size());
        for (int index = 0; index < original.size(); index++) {
            assertSame(original.get(index), grouped.get(index));
        }
        assertEquals(List.of("useYsmSwemAnimations"), names(unknown.getChildren()),
                "unrelated subtrees are not regrouped recursively");
    }

    @Test
    void repeatedGroupingPreservesTheExistingFoldersAndTheirChildren() {
        List<?> grouped = ConfiguredClientLayout.groupClientEntries(allEntries());
        List<IConfigEntry> originalDescendants = descendants(grouped);

        List<?> repeated = ConfiguredClientLayout.groupClientEntries(grouped);

        assertEquals(grouped.size(), repeated.size());
        assertEquals(originalDescendants, descendants(repeated));
        for (int index = 0; index < grouped.size(); index++) {
            assertSame(grouped.get(index), repeated.get(index));
        }
    }

    @Test
    void preservesEditedValuesDirtyStateRestoreAndCacheOperations() {
        BooleanValue currentValue = new BooleanValue("useYsmSwemAnimations", true);
        ValueEntry original = new ValueEntry(currentValue);
        currentValue.set(false);
        assertTrue(currentValue.isChanged());

        List<?> grouped = ConfiguredClientLayout.groupClientEntries(List.of(original));
        IConfigEntry displayed = entry(entry(grouped, 0).getChildren(), 0);

        assertSame(original, displayed);
        assertSame(currentValue, displayed.getValue());
        assertFalse(currentValue.get());
        assertTrue(displayed.getValue().isChanged());
        assertFalse(displayed.getValue().isDefault());
        assertEquals(0, currentValue.restoreCalls);
        assertEquals(0, currentValue.cleanCacheCalls);

        displayed.getValue().restore();
        assertTrue(currentValue.get());
        assertTrue(currentValue.isDefault());
        assertFalse(currentValue.isChanged());
        assertEquals(1, currentValue.restoreCalls);
        displayed.getValue().cleanCache();
        assertEquals(1, currentValue.cleanCacheCalls);

        currentValue.set(false);
        List<?> repeated = ConfiguredClientLayout.groupClientEntries(grouped);
        assertSame(currentValue, entry(entry(repeated, 0).getChildren(), 0).getValue());
        assertTrue(currentValue.isChanged());
        assertEquals(1, currentValue.restoreCalls);
        assertEquals(1, currentValue.cleanCacheCalls);
    }

    @Test
    void preservesDynamicExclusionFoldersAndTheirChangedDescendants() {
        BooleanValue existingValue = new BooleanValue("example/model", true);
        existingValue.set(false);
        IConfigEntry existingRule = new ValueEntry(existingValue);
        IConfigEntry existingFolder = folder("movementAnimationExclusions", existingRule);

        List<?> grouped = ConfiguredClientLayout.groupClientEntries(List.of(existingFolder));
        IConfigEntry exclusions = entry(entry(grouped, 0).getChildren(), 0);
        IConfigEntry displayedFolder = entry(exclusions.getChildren(), 0);

        assertSame(existingFolder, displayedFolder);
        assertSame(existingRule, displayedFolder.getChildren().get(0));
        assertSame(existingValue, displayedFolder.getChildren().get(0).getValue());
        assertTrue(existingValue.isChanged());
        assertEquals(0, existingValue.restoreCalls);
        assertEquals(0, existingValue.cleanCacheCalls);
        existingValue.restore();
        assertTrue(displayedFolder.getChildren().get(0).getValue().isDefault());
        assertFalse(displayedFolder.getChildren().get(0).getValue().isChanged());
    }

    @Test
    void optionalVisibilityTracksEachModIndependentlyForValuesAndRuleFolders() {
        List<IConfigEntry> original = allEntries();
        List<IConfigEntry> snapshot = List.copyOf(original);
        for (boolean parCoolAvailable : List.of(false, true)) {
            for (boolean swemAvailable : List.of(false, true)) {
                List<?> visible = visibleEntries(original, parCoolAvailable, swemAvailable);
                List<IConfigEntry> displayed = descendants(
                        ConfiguredClientLayout.groupClientEntries(visible));
                for (IConfigEntry entry : original) {
                    String name = name(entry);
                    boolean expected = switch (name) {
                        case "useYsmParCoolAnimations", "parcoolAnimationExclusions" -> parCoolAvailable;
                        case "useYsmSwemAnimations", "swemAnimationExclusions" -> swemAvailable;
                        default -> true;
                    };
                    assertEquals(expected ? 1L : 0L,
                            displayed.stream().filter(candidate -> candidate == entry).count(), name);
                }
                assertEquals(snapshot, original);
            }
        }
    }

    @Test
    void hidesOptionalPlaceholdersBeforeTheirDynamicEditorsAreCreated() {
        for (String name : List.of("parcoolAnimationExclusions", "swemAnimationExclusions")) {
            IConfigEntry placeholder = value(name);
            assertFalse(ConfiguredClientLayout.isVisibleClientEntry(placeholder, false, false));
            assertTrue(ConfiguredClientLayout.isVisibleClientEntry(placeholder, true, true));
        }
    }

    @Test
    void hiddenOnlySettingsDoNotGenerateEmptyPresentationFolders() {
        List<IConfigEntry> optional = List.of(value("useYsmParCoolAnimations"),
                value("useYsmSwemAnimations"), folder("parcoolAnimationExclusions"),
                folder("swemAnimationExclusions"));

        assertTrue(ConfiguredClientLayout.groupClientEntries(
                visibleEntries(optional, false, false)).isEmpty());

        IConfigEntry ladder = value("useNaturalLadderAnimations");
        List<?> grouped = ConfiguredClientLayout.groupClientEntries(visibleEntries(
                List.of(ladder, folder("parcoolAnimationExclusions")), false, false));
        assertEquals(List.of("animations"), names(grouped));
        assertEquals(List.of(ladder), entry(grouped, 0).getChildren());
    }

    @Test
    void hidingChangedSettingsDoesNotResetValuesDirtyStateOrDynamicDescendants() {
        BooleanValue setting = new BooleanValue("useYsmParCoolAnimations", true);
        BooleanValue rule = new BooleanValue("example/model", true);
        setting.set(false);
        rule.set(false);
        IConfigEntry settingEntry = new ValueEntry(setting);
        IConfigEntry ruleEntry = new ValueEntry(rule);
        IConfigEntry ruleFolder = folder("swemAnimationExclusions", ruleEntry);
        List<IConfigEntry> original = List.of(settingEntry, ruleFolder);

        assertTrue(visibleEntries(original, false, false).isEmpty());

        for (BooleanValue hidden : List.of(setting, rule)) {
            assertFalse(hidden.get());
            assertTrue(hidden.isChanged());
            assertEquals(0, hidden.restoreCalls);
            assertEquals(0, hidden.cleanCacheCalls);
        }
        List<?> restoredVisibility = visibleEntries(original, true, true);
        assertSame(settingEntry, restoredVisibility.get(0));
        assertSame(ruleFolder, restoredVisibility.get(1));
        assertSame(ruleEntry, ruleFolder.getChildren().get(0));
    }

    @Test
    void visibilityLeavesUnrelatedEntriesAndSubtreesUnchanged() {
        IConfigEntry unknown = folder("futureSettings", value("useYsmParCoolAnimations"));
        IConfigEntry ordinary = value("useYsmMovementAnimations");
        Object foreign = new Object();
        List<Object> original = List.of(unknown, ordinary, foreign);

        List<?> visible = visibleEntries(original, false, false);

        assertEquals(original.size(), visible.size());
        for (int index = 0; index < original.size(); index++) {
            assertSame(original.get(index), visible.get(index));
        }
        assertEquals(List.of("useYsmParCoolAnimations"), names(unknown.getChildren()));
    }

    private static List<?> visibleEntries(List<?> entries, boolean parCoolAvailable,
                                          boolean swemAvailable) {
        return entries.stream().filter(entry -> ConfiguredClientLayout.isVisibleClientEntry(
                entry, parCoolAvailable, swemAvailable)).toList();
    }

    private static List<IConfigEntry> allEntries() {
        List<IConfigEntry> entries = new ArrayList<>();
        ANIMATION_EXCLUSIONS.forEach(name -> entries.add(folder(name)));
        ANIMATION_VALUES.forEach(name -> entries.add(value(name)));
        MODEL_EXCLUSIONS.forEach(name -> entries.add(folder(name)));
        MODEL_VALUES.forEach(name -> entries.add(value(name)));
        return entries;
    }

    private static List<String> withExclusions(String exclusions, List<String> values) {
        List<String> names = new ArrayList<>();
        names.add(exclusions);
        names.addAll(values);
        return names;
    }

    private static List<String> names(List<?> entries) {
        return entries.stream().map(IConfigEntry.class::cast)
                .map(ConfiguredClientLayoutTest::name).toList();
    }

    private static String name(IConfigEntry entry) {
        return entry.isLeaf() ? entry.getValue().getName() : entry.getEntryName();
    }

    /** Mirrors Configured 2.2.3 ConfigScreen.SORT_ALPHABETICALLY without starting Minecraft. */
    private static List<IConfigEntry> configuredSort(List<?> entries, JsonObject translations) {
        List<IConfigEntry> sorted = new ArrayList<>(
                entries.stream().map(IConfigEntry.class::cast).toList());
        sorted.sort((left, right) -> {
            if (left.isLeaf() != right.isLeaf()) {
                return left.isLeaf() ? 1 : -1;
            }
            String leftKey = left.getTranslationKey();
            String rightKey = right.getTranslationKey();
            assertNotNull(translations.get(leftKey), leftKey);
            assertNotNull(translations.get(rightKey), rightKey);
            return translations.get(leftKey).getAsString()
                    .compareTo(translations.get(rightKey).getAsString());
        });
        return sorted;
    }

    private static IConfigEntry entry(List<?> entries, int index) {
        return (IConfigEntry) entries.get(index);
    }

    private static List<IConfigEntry> descendants(List<?> entries) {
        List<IConfigEntry> result = new ArrayList<>();
        for (Object candidate : entries) {
            if (candidate instanceof IConfigEntry entry) {
                result.add(entry);
                result.addAll(descendants(entry.getChildren()));
            }
        }
        return result;
    }

    private static IConfigEntry value(String name) {
        return new ValueEntry(new BooleanValue(name, true));
    }

    private static IConfigEntry folder(String name, IConfigEntry... children) {
        return new TestFolder(name, List.of(children));
    }

    private record TestFolder(String name, List<IConfigEntry> children) implements IConfigEntry {
        @Override
        public List<IConfigEntry> getChildren() {
            return children;
        }

        @Override
        public boolean isRoot() {
            return false;
        }

        @Override
        public boolean isLeaf() {
            return false;
        }

        @Override
        public IConfigValue<?> getValue() {
            return null;
        }

        @Override
        public String getEntryName() {
            return name;
        }

        @Override
        public Component getTooltip() {
            return null;
        }

        @Override
        public String getTranslationKey() {
            return "config.ysm_epicfight_compat." + name.replace("ParCool", "Parcool")
                    .replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
        }
    }

    private static final class BooleanValue implements IConfigValue<Boolean> {
        private final String name;
        private final boolean initial;
        private boolean current;
        private int restoreCalls;
        private int cleanCacheCalls;

        private BooleanValue(String name, boolean initial) {
            this.name = name;
            this.initial = initial;
            this.current = initial;
        }

        @Override
        public Boolean get() {
            return current;
        }

        @Override
        public Boolean getDefault() {
            return initial;
        }

        @Override
        public void set(Boolean value) {
            if (isValid(value)) {
                current = value;
            }
        }

        @Override
        public boolean isValid(Boolean value) {
            return value != null;
        }

        @Override
        public boolean isDefault() {
            return current == initial;
        }

        @Override
        public boolean isChanged() {
            return current != initial;
        }

        @Override
        public void restore() {
            current = initial;
            restoreCalls++;
        }

        @Override
        public Component getComment() {
            return null;
        }

        @Override
        public String getTranslationKey() {
            return "config.ysm_epicfight_compat." + name.replace("ParCool", "Parcool")
                    .replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
        }

        @Override
        public Component getValidationHint() {
            return null;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public void cleanCache() {
            cleanCacheCalls++;
        }

        @Override
        public boolean requiresWorldRestart() {
            return false;
        }

        @Override
        public boolean requiresGameRestart() {
            return false;
        }
    }
}
