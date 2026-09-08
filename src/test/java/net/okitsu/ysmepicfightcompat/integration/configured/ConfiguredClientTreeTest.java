package net.okitsu.ysmepicfightcompat.integration.configured;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.core.Config;
import com.electronwill.nightconfig.core.UnmodifiableConfig;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mrcrayfish.configured.api.IConfigEntry;
import com.mrcrayfish.configured.api.IConfigValue;
import com.mrcrayfish.configured.impl.forge.ForgeFolderEntry;
import net.minecraft.network.chat.Component;
import net.minecraftforge.common.ForgeConfigSpec;
import net.okitsu.ysmepicfightcompat.config.ClientPreferences;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfiguredClientTreeTest {
    private static final List<String> ANIMATIONS = List.of("common", "animations");
    private static final List<String> ANIMATION_RULES = List.of("common", "animations", "exclusions");
    private static final List<String> MODEL_RULES = List.of("common", "models", "exclusions");
    private static final List<String> ANIMATION_KEYS = List.of(
            "heldItemSwitchAnimationExclusions", "movementAnimationExclusions",
            "parcoolAnimationExclusions", "swemAnimationExclusions");
    private static final List<String> MODEL_KEYS = List.of(
            "heldItemModelExclusions", "projectileModelExclusions", "vehicleModelExclusions");

    @Test
    void configuredUsesTheActualForgeCategoriesAndTheirLocalizedMetadata() throws IOException {
        ForgeConfigSpec spec = ClientPreferences.CLIENT_SPEC;
        // Root and common contain only categories; do not load the client root's scalar values.
        IConfigEntry root = new ForgeFolderEntry(spec.getValues(), spec);
        assertEquals(Set.of("client", "common"),
                Set.copyOf(root.getChildren().stream().map(IConfigEntry::getEntryName).toList()));
        assertInstanceOf(ForgeFolderEntry.class, child(root, "client"));
        UnmodifiableConfig clientValues = spec.getValues().getRaw(List.of("client"));
        assertEquals(Set.of("cache", "suppressBattleModeOverlay", "epicFightCompatibilityWarningShown"),
                clientValues.valueMap().keySet());
        assertInstanceOf(ForgeConfigSpec.ConfigValue.class, clientValues.getRaw("suppressBattleModeOverlay"));
        assertInstanceOf(ForgeConfigSpec.ConfigValue.class, clientValues.getRaw("epicFightCompatibilityWarningShown"));
        List<IConfigEntry> categories = child(root, "common").getChildren();
        assertEquals(Set.of("models", "animations"),
                Set.copyOf(categories.stream().map(IConfigEntry::getEntryName).toList()));
        for (IConfigEntry category : categories) {
            assertInstanceOf(ForgeFolderEntry.class, category);
            assertEquals("config.ysm_epicfight_compat.common." + category.getEntryName(),
                    category.getTranslationKey());
        }
        List<String> cachePath = List.of("client", "cache");
        for (List<String> path : List.of(ANIMATION_RULES, MODEL_RULES, cachePath)) {
            assertInstanceOf(UnmodifiableConfig.class, spec.getValues().getRaw(path));
            assertEquals("config.ysm_epicfight_compat." + String.join(".", path),
                    spec.getLevelTranslationKey(path));
        }
        for (String language : List.of("en_us", "ja_jp")) {
            try (InputStream input = getClass().getResourceAsStream(
                    "/assets/ysm_epicfight_compat/lang/" + language + ".json")) {
                assertNotNull(input);
                JsonObject translations = JsonParser.parseReader(
                        new InputStreamReader(input, StandardCharsets.UTF_8)).getAsJsonObject();
                List<String> keys = new ArrayList<>(categories.stream()
                        .map(IConfigEntry::getTranslationKey).toList());
                keys.add(spec.getLevelTranslationKey(ANIMATION_RULES));
                keys.add(spec.getLevelTranslationKey(MODEL_RULES));
                keys.add(spec.getLevelTranslationKey(cachePath));
                for (String key : keys) {
                    assertTrue(translations.has(key), language + ": " + key);
                    assertFalse(translations.get(key).getAsString().isBlank(), key);
                    assertTrue(translations.has(key + ".tooltip"), key);
                }
                assertTrue(translations.keySet().stream().noneMatch(
                        key -> key.startsWith("config.ysm_epicfight_compat.configured.")),
                        "Presentation-only folder translations are no longer needed");
            }
        }
    }

    @Test
    void realNestedForgeFoldersExposeEveryDynamicTableAsAReplaceableLeaf() {
        Fixture fixture = fixture();
        for (List<String> parent : List.of(ANIMATION_RULES, MODEL_RULES)) {
            IConfigEntry category = entry(fixture.root(), parent);
            assertInstanceOf(ForgeFolderEntry.class, category);
            List<IConfigEntry> placeholders = category.getChildren();
            assertEquals(Set.copyOf(parent.equals(ANIMATION_RULES) ? ANIMATION_KEYS : MODEL_KEYS),
                    Set.copyOf(placeholders.stream().map(ConfiguredClientTreeTest::name).toList()));
            assertTrue(ConfiguredHeldItemRules.containsRuleEntries(parent));
            for (IConfigEntry placeholder : placeholders) {
                assertTrue(placeholder.isLeaf(),
                        "Forge's Config-valued leaf still needs a dynamic model/list editor");
                assertTrue(ConfiguredHeldItemRules.isPlaceholder(parent, placeholder));
                assertEquals(name(placeholder), ConfiguredHeldItemRules.placeholderKey(placeholder));
            }
        }
    }

    @Test
    void sameNamedRuleTablesOutsideTheirExactFamilyPathAreNotReplaced() {
        Fixture fixture = fixture();
        for (List<String> parent : List.of(ANIMATION_RULES, MODEL_RULES)) {
            for (IConfigEntry placeholder : entry(fixture.root(), parent).getChildren()) {
                for (List<String> other : List.of(List.<String>of(), List.of("client"), List.of("common"),
                        List.of("other", parent.get(1), "exclusions"),
                        List.of("client", parent.get(1), "exclusions"),
                        List.of("common", parent.get(1), "nested", "exclusions"),
                        parent.equals(ANIMATION_RULES) ? MODEL_RULES : ANIMATION_RULES)) {
                    assertFalse(ConfiguredHeldItemRules.isPlaceholder(other, placeholder),
                            other + ": " + name(placeholder));
                }
            }
        }
        assertFalse(ConfiguredHeldItemRules.containsRuleEntries(ANIMATIONS));
        assertFalse(ConfiguredHeldItemRules.containsRuleEntries(List.of("client")));
        assertFalse(ConfiguredHeldItemRules.containsRuleEntries(List.of("client", "models", "exclusions")));
        assertFalse(ConfiguredHeldItemRules.containsRuleEntries(List.of("client", "animations", "exclusions")));
        assertFalse(ConfiguredHeldItemRules.containsRuleEntries(null));
        assertFalse(ConfiguredHeldItemRules.isPlaceholder(ANIMATION_RULES, new Object()));
        assertFalse(ConfiguredHeldItemRules.isPlaceholder(ANIMATION_RULES,
                entry(fixture.root(), ANIMATIONS)));
    }

    @Test
    void optionalVisibilityTracksEachModForNestedTogglesPlaceholdersAndDynamicFolders() {
        Fixture fixture = fixture();
        for (boolean parCoolAvailable : List.of(false, true)) {
            for (boolean swemAvailable : List.of(false, true)) {
                for (List<String> parent : List.of(ANIMATIONS, ANIMATION_RULES)) {
                    List<IConfigEntry> original = entry(fixture.root(), parent).getChildren();
                    List<IConfigEntry> snapshot = List.copyOf(original);
                    for (IConfigEntry candidate : original) {
                        boolean expected = switch (name(candidate)) {
                            case "useYsmParCoolAnimations", "parcoolAnimationExclusions" -> parCoolAvailable;
                            case "useYsmSwemAnimations", "swemAnimationExclusions" -> swemAvailable;
                            default -> true;
                        };
                        assertEquals(expected, ConfiguredOptionalSettings.isVisibleClientEntry(
                                parent, candidate, parCoolAvailable, swemAvailable));
                        if (ANIMATION_KEYS.contains(name(candidate))) {
                            assertEquals(expected, ConfiguredOptionalSettings.isVisibleClientEntry(
                                    parent, new Folder(name(candidate), List.of()),
                                    parCoolAvailable, swemAvailable));
                        }
                    }
                    assertEquals(snapshot, original);
                }
            }
        }
    }

    @Test
    void hidingEditedEntriesDoesNotResetTheirValuesOrDirtyState() {
        Fixture fixture = fixture();
        IConfigEntry setting = child(entry(fixture.root(), ANIMATIONS), "useYsmParCoolAnimations");
        setBoolean(setting.getValue(), false);
        IConfigEntry rule = child(entry(fixture.root(), ANIMATIONS), "useYsmSwemAnimations");
        setBoolean(rule.getValue(), false);
        Folder rulesFolder = new Folder("swemAnimationExclusions", List.of(rule));

        assertFalse(ConfiguredOptionalSettings.isVisibleClientEntry(ANIMATIONS, setting, false, false));
        assertFalse(ConfiguredOptionalSettings.isVisibleClientEntry(ANIMATION_RULES, rulesFolder, false, false));

        assertEquals(false, setting.getValue().get());
        assertEquals(false, rule.getValue().get());
        assertTrue(setting.getValue().isChanged());
        assertTrue(rule.getValue().isChanged());
        assertSame(rule, rulesFolder.getChildren().get(0));
        assertTrue(ConfiguredOptionalSettings.isVisibleClientEntry(ANIMATIONS, setting, true, true));
        assertTrue(ConfiguredOptionalSettings.isVisibleClientEntry(ANIMATION_RULES, rulesFolder, true, true));
        assertEquals(true, fixture.data().get(List.of("common", "animations", "useYsmParCoolAnimations")));
        assertEquals(true, fixture.data().get(List.of("common", "animations", "useYsmSwemAnimations")));
    }

    @Test
    void visibilityNeverHidesSameNamedEntriesFromUnrelatedPathsOrTraversesTheirChildren() {
        Fixture fixture = fixture();
        IConfigEntry toggle = child(entry(fixture.root(), ANIMATIONS), "useYsmParCoolAnimations");
        IConfigEntry rules = child(entry(fixture.root(), ANIMATION_RULES), "swemAnimationExclusions");
        for (List<String> unrelated : List.of(List.<String>of(), List.of("client"), List.of("common"),
                List.of("server", "animations"), List.of("common", "models"), MODEL_RULES,
                List.of("client", "animations"), List.of("client", "animations", "exclusions"),
                List.of("common", "animations", "nested"))) {
            assertFalse(ConfiguredOptionalSettings.containsOptionalEntries(unrelated));
            assertTrue(ConfiguredOptionalSettings.isVisibleClientEntry(unrelated, toggle, false, false));
            assertTrue(ConfiguredOptionalSettings.isVisibleClientEntry(unrelated, rules, false, false));
        }
        // A correct category with the wrong kind of optional leaf must also remain unchanged.
        assertTrue(ConfiguredOptionalSettings.isVisibleClientEntry(ANIMATION_RULES, toggle, false, false));
        assertTrue(ConfiguredOptionalSettings.isVisibleClientEntry(ANIMATIONS, rules, false, false));
        Folder unknown = new Folder("futureSettings", List.of(toggle));
        assertTrue(ConfiguredOptionalSettings.isVisibleClientEntry(ANIMATIONS, unknown, false, false));
        assertSame(toggle, unknown.getChildren().get(0));
        assertTrue(ConfiguredOptionalSettings.isVisibleClientEntry(ANIMATIONS, new Object(), false, false));
        assertFalse(ConfiguredOptionalSettings.containsOptionalEntries(null));
    }

    private static Fixture fixture() {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
        builder.push("common").push("models").push("exclusions");
        MODEL_KEYS.forEach(key -> builder.define(key, Config::inMemory,
                value -> value instanceof UnmodifiableConfig));
        builder.pop(2).push("animations");
        for (String key : List.of("useYsmHeldItemSwitchAnimations", "useYsmMovementAnimations",
                "useYsmParCoolAnimations", "useYsmSwemAnimations", "useNaturalLadderAnimations")) {
            builder.define(key, true);
        }
        builder.push("exclusions");
        ANIMATION_KEYS.forEach(key -> builder.define(key, Config::inMemory,
                value -> value instanceof UnmodifiableConfig));
        builder.pop(3);
        ForgeConfigSpec spec = builder.build();
        CommentedConfig data = CommentedConfig.inMemory();
        spec.correct(data);
        spec.acceptConfig(data);
        return new Fixture(new ForgeFolderEntry(spec.getValues(), spec), data);
    }

    private static IConfigEntry entry(IConfigEntry root, List<String> path) {
        IConfigEntry current = root;
        for (String part : path) {
            current = child(current, part);
        }
        return current;
    }

    private static IConfigEntry child(IConfigEntry parent, String name) {
        return parent.getChildren().stream().filter(entry -> name.equals(name(entry)))
                .findFirst().orElseThrow(() -> new AssertionError("Missing entry " + name));
    }

    private static String name(IConfigEntry entry) {
        return entry.isLeaf() ? entry.getValue().getName() : entry.getEntryName();
    }

    @SuppressWarnings("unchecked")
    private static void setBoolean(IConfigValue<?> value, boolean next) {
        ((IConfigValue<Boolean>) value).set(next);
    }

    private record Fixture(IConfigEntry root, CommentedConfig data) {
    }

    private record Folder(String name, List<IConfigEntry> children) implements IConfigEntry {
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
            return null;
        }
    }
}
