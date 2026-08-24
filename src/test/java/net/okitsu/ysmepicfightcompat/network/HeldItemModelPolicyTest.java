package net.okitsu.ysmepicfightcompat.network;

import com.electronwill.nightconfig.core.Config;
import com.electronwill.nightconfig.toml.TomlWriter;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.io.StringWriter;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HeldItemModelPolicyTest {
    private static final ResourceLocation SWORD = ResourceLocation.fromNamespaceAndPath(
            "minecraft", "diamond_sword");
    private static final ResourceLocation BOW_TAG = ResourceLocation.fromNamespaceAndPath(
            "forge", "tools/bows");

    @Test
    void appliesExceptionsOnlyToTheirSelectedModel() {
        HeldItemModelPolicy policy = HeldItemModelPolicy.create(true, Map.of(
                "wine_fox/21_saint", List.of(
                        "minecraft:diamond_sword", "#forge:tools/bows")));

        assertFalse(policy.usesYsm("wine_fox/21_saint", SWORD,
                ignored -> false));
        assertFalse(policy.usesYsm("WINE_FOX/21_SAINT",
                ResourceLocation.fromNamespaceAndPath("minecraft", "bow"),
                BOW_TAG::equals));
        assertTrue(policy.usesYsm("wine_fox/05_magical", SWORD,
                ignored -> false));
    }

    @Test
    void exceptionsInvertAnEpicFightDefault() {
        HeldItemModelPolicy policy = HeldItemModelPolicy.create(false, Map.of(
                "wine_fox/21_saint", List.of("minecraft:diamond_sword")));

        assertTrue(policy.usesYsm("wine_fox/21_saint", SWORD,
                ignored -> false));
        assertFalse(policy.usesYsm("wine_fox/05_magical", SWORD,
                ignored -> false));
    }

    @Test
    void validatesSelectorsInsideEachModelTableEntry() {
        assertFalse(HeldItemModelPolicy.isValidSelector("#"));
        assertTrue(HeldItemModelPolicy.isValidSelector("minecraft:diamond_sword"));
        assertTrue(HeldItemModelPolicy.isValidSelector("#forge:tools/bows"));
        assertThrows(IllegalArgumentException.class, () ->
                HeldItemModelPolicy.create(true, Map.of(
                        "wine_fox/21_saint", List.of("not a valid item"))));
    }

    @Test
    void encodesModelIdsAsTomlTableKeysAndRestoresThem() {
        Map<String, List<String>> expected = Map.of(
                "wine_fox/21_saint", List.of(
                        "minecraft:diamond_sword", "#forge:tools/bows"));
        Config encoded = HeldItemModelPolicy.encodeConfiguration(expected);

        assertTrue(HeldItemModelPolicy.isValidConfiguration(encoded));
        assertEquals(expected, HeldItemModelPolicy.decodeConfiguration(encoded));

        Config root = Config.inMemory();
        Config client = root.createSubConfig();
        root.set(List.of("client"), client);
        client.set(List.of("heldItemModelOverrides"), encoded);
        StringWriter output = new StringWriter();
        new TomlWriter().write(root, output);
        String toml = output.toString();

        assertTrue(toml.contains("[client.heldItemModelOverrides]"));
        assertTrue(toml.contains("\"wine_fox/21_saint\" = ["));
        assertFalse(toml.contains("wine_fox/21_saint="));
    }
}
