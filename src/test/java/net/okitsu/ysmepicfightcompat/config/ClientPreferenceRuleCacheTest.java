package net.okitsu.ysmepicfightcompat.config;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.core.Config;
import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.electronwill.nightconfig.core.file.FileNotFoundAction;
import net.minecraftforge.common.ForgeConfigSpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ClientPreferenceRuleCacheTest {
    private static final String MODEL = "author/model.v2";
    private CommentedConfig config;

    @BeforeEach
    void loadClientConfig() {
        config = CommentedConfig.inMemory();
        bind(config);
    }

    @AfterEach
    void unloadClientConfig() {
        clear();
    }

    @Test
    void allSevenTablesObserveDirectListEditsAndInvalidToValidRepairs() {
        for (Table table : tables()) {
            table.setter().accept(Map.of(MODEL, List.of(table.first())));
            Map<String, List<String>> first = table.getter().get();
            assertSame(first, table.getter().get(), table.name());

            List<String> selectors = new ArrayList<>(List.of(table.first()));
            table.value().get().valueMap().put(MODEL, selectors);
            assertSame(first, table.getter().get(), table.name());
            selectors.set(0, table.second());
            Map<String, List<String>> changed = table.getter().get();
            assertNotSame(first, changed, table.name());
            assertEquals(Map.of(MODEL, List.of(table.second())), changed, table.name());
            assertEquals(Map.of(MODEL, List.of(table.first())), first, table.name());

            selectors.set(0, "invalid selector");
            assertEquals(Map.of(), table.getter().get(), table.name());
            selectors.set(0, table.first());
            assertEquals(first, table.getter().get(), table.name());
        }
    }

    @Test
    void guiSettersRefreshEachTableAndEquivalentNormalizedContentKeepsItsIdentity() {
        for (Table table : tables()) {
            table.setter().accept(Map.of(MODEL, List.of(table.first())));
            Map<String, List<String>> first = table.getter().get();
            table.setter().accept(Map.of(" " + MODEL.toUpperCase(Locale.ROOT) + " ",
                    List.of(" " + table.first().toUpperCase(Locale.ROOT) + " ")));
            assertSame(first, table.getter().get(), table.name());

            table.setter().accept(Map.of(MODEL, List.of(table.second())));
            assertEquals(Map.of(MODEL, List.of(table.second())), table.getter().get(), table.name());
        }
    }

    @Test
    void changingOneTableDoesNotInvalidateAnyOtherTable() {
        List<Table> tables = tables();
        for (Table table : tables) {
            table.setter().accept(Map.of(MODEL, List.of(table.first())));
        }
        List<Map<String, List<String>>> before = tables.stream().map(table -> table.getter().get()).toList();
        for (int changed = 0; changed < tables.size(); changed++) {
            Table table = tables.get(changed);
            table.setter().accept(Map.of(MODEL, List.of(table.second())));
            assertNotSame(before.get(changed), table.getter().get(), table.name());
            for (int unchanged = changed + 1; unchanged < tables.size(); unchanged++) {
                assertSame(before.get(unchanged), tables.get(unchanged).getter().get());
            }
        }
    }

    @Test
    void tomlReloadReplacesRawTablesAndSeesChangesWhileReusingEqualDecodedRules(@TempDir Path directory) {
        var builder = CommentedFileConfig.builder(directory.resolve("client.toml"));
        builder.onFileNotFound(FileNotFoundAction.READ_NOTHING);
        try (CommentedFileConfig file = builder.sync().build()) {
            file.load();
            bind(file);
            for (Table table : tables()) {
                table.setter().accept(Map.of(MODEL, List.of(table.first())));
                Map<String, List<String>> first = table.getter().get();

                file.set(table.value().getPath(), rawTable(
                        " " + MODEL.toUpperCase(Locale.ROOT) + " ",
                        " " + table.first().toUpperCase(Locale.ROOT) + " "));
                file.save();
                file.load();
                ClientPreferences.CLIENT_SPEC.afterReload();
                assertSame(first, table.getter().get(), table.name());

                file.set(table.value().getPath(), rawTable(MODEL, table.second()));
                file.save();
                file.load();
                ClientPreferences.CLIENT_SPEC.afterReload();
                assertEquals(Map.of(MODEL, List.of(table.second())), table.getter().get(), table.name());
            }
        }
    }

    @Test
    void optionalFallbackReadsDoNotInsertMissingKeysAndReloadFindsNewRules() {
        for (Table table : tables().subList(5, 7)) {
            config.remove(table.value().getPath());
            ClientPreferences.CLIENT_SPEC.afterReload();
            for (int read = 0; read < 3; read++) {
                assertEquals(Map.of(), table.getter().get());
                assertFalse(config.contains(table.value().getPath()));
            }
            config.set(table.value().getPath(), rawTable(MODEL, table.first()));
            ClientPreferences.CLIENT_SPEC.afterReload();
            assertEquals(Map.of(MODEL, List.of(table.first())), table.getter().get());
        }
    }

    @Test
    void unloadingRetainsLoaderReadinessChecksAndNewLoadedConfigHasNoStaleRules() {
        for (Table table : tables()) {
            table.setter().accept(Map.of(MODEL, List.of(table.first())));
            assertEquals(Map.of(MODEL, List.of(table.first())), table.getter().get());
        }
        clear();
        for (Table table : tables()) {
            boolean rejectsUnloadedReads;
            try {
                table.value().get();
                rejectsUnloadedReads = false;
            } catch (IllegalStateException expected) {
                rejectsUnloadedReads = true;
            }
            if (rejectsUnloadedReads) {
                assertThrows(IllegalStateException.class, () -> table.getter().get());
            } else {
                assertEquals(Map.of(), table.getter().get());
            }
        }
        bind(CommentedConfig.inMemory());
        for (Table table : tables()) {
            assertEquals(Map.of(), table.getter().get());
            table.setter().accept(Map.of(MODEL, List.of(table.second())));
            assertEquals(Map.of(MODEL, List.of(table.second())), table.getter().get());
        }
    }

    private static Config rawTable(String model, String selector) {
        Config result = Config.inMemory();
        result.valueMap().put(model, List.of(selector));
        return result;
    }

    private static void bind(CommentedConfig data) {
        ClientPreferences.CLIENT_SPEC.setConfig(data);
    }

    private static void clear() {
        ClientPreferences.CLIENT_SPEC.setConfig(null);
    }

    private static List<Table> tables() {
        return List.of(
                new Table("held item", ClientPreferences.HELD_ITEM_MODEL_EXCLUSIONS,
                        ClientPreferences::heldItemModelExclusions, ClientPreferences::setHeldItemModelExclusions,
                        "minecraft:stick", "minecraft:bow"),
                new Table("projectile", ClientPreferences.PROJECTILE_MODEL_EXCLUSIONS,
                        ClientPreferences::projectileModelExclusions, ClientPreferences::setProjectileModelExclusions,
                        "minecraft:arrow", "minecraft:snowball"),
                new Table("vehicle", ClientPreferences.VEHICLE_MODEL_EXCLUSIONS,
                        ClientPreferences::vehicleModelExclusions, ClientPreferences::setVehicleModelExclusions,
                        "minecraft:boat", "minecraft:minecart"),
                new Table("held item switch", ClientPreferences.HELD_ITEM_SWITCH_ANIMATION_EXCLUSIONS,
                        ClientPreferences::heldItemSwitchAnimationExclusions,
                        ClientPreferences::setHeldItemSwitchAnimationExclusions, "minecraft:air", "minecraft:stick"),
                new Table("movement", ClientPreferences.MOVEMENT_ANIMATION_EXCLUSIONS,
                        ClientPreferences::movementAnimationExclusions, ClientPreferences::setMovementAnimationExclusions,
                        "run", "walk"),
                new Table("ParCool", ClientPreferences.PARCOOL_ANIMATION_EXCLUSIONS,
                        ClientPreferences::parCoolAnimationExclusions, ClientPreferences::setParCoolAnimationExclusions,
                        "roll_front", "fast_running"),
                new Table("SWEM", ClientPreferences.SWEM_ANIMATION_EXCLUSIONS,
                        ClientPreferences::swemAnimationExclusions, ClientPreferences::setSwemAnimationExclusions,
                        "gallop", "walk"));
    }

    private record Table(String name, ForgeConfigSpec.ConfigValue<Config> value,
                         Supplier<Map<String, List<String>>> getter,
                         Consumer<Map<String, List<String>>> setter,
                         String first, String second) {
    }
}
