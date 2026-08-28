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

class EntityModelPolicyTest {
    private static final ResourceLocation ARROW = ResourceLocation.fromNamespaceAndPath(
            "minecraft", "arrow");
    private static final ResourceLocation ARROW_TAG = ResourceLocation.fromNamespaceAndPath(
            "minecraft", "arrows");

    @Test
    void appliesEntityAndTagExclusionsOnlyToTheirSelectedModel() {
        EntityModelPolicy policy = EntityModelPolicy.create(true, Map.of(
                "wine_fox/22_elf", List.of(
                        "minecraft:arrow", "#minecraft:arrows")));

        assertFalse(policy.usesYsm("wine_fox/22_elf", ARROW,
                ignored -> false));
        assertFalse(policy.usesYsm(" WINE_FOX/22_ELF ",
                ResourceLocation.fromNamespaceAndPath("minecraft", "spectral_arrow"),
                ARROW_TAG::equals));
        assertTrue(policy.usesYsm("wine_fox/05_magical", ARROW,
                ignored -> false));
    }

    @Test
    void exclusionsNeverEnableAYsmSettingThatIsOff() {
        EntityModelPolicy policy = EntityModelPolicy.create(false, Map.of(
                "wine_fox/22_elf", List.of("minecraft:arrow")));

        assertFalse(policy.usesYsm("wine_fox/22_elf", ARROW,
                ignored -> false));
        assertFalse(policy.usesYsm("wine_fox/05_magical", ARROW,
                ignored -> false));
        assertFalse(policy.usesYsm("wine_fox/05_magical", null,
                ignored -> false));
    }

    @Test
    void validatesEntityIdsAndEntityTypeTags() {
        assertFalse(EntityModelPolicy.isValidSelector("#"));
        assertTrue(EntityModelPolicy.isValidSelector("minecraft:boat"));
        assertTrue(EntityModelPolicy.isValidSelector("#minecraft:boats"));
        assertThrows(IllegalArgumentException.class, () ->
                EntityModelPolicy.create(true, Map.of(
                        "wine_fox/22_elf", List.of("not a valid entity"))));
    }

    @Test
    void encodesModelIdsAsTomlTableKeysAndRestoresThem() {
        Map<String, List<String>> source = Map.of(
                " WINE_FOX/22_ELF ", List.of(
                        " MINECRAFT:ARROW ", "#minecraft:arrows",
                        "minecraft:arrow"));
        Map<String, List<String>> expected = Map.of(
                "wine_fox/22_elf", List.of(
                        "minecraft:arrow", "#minecraft:arrows"));
        Config encoded = EntityModelPolicy.encodeConfiguration(source);

        assertTrue(EntityModelPolicy.isValidConfiguration(encoded));
        assertEquals(expected, EntityModelPolicy.decodeConfiguration(encoded));

        Config root = Config.inMemory();
        Config client = root.createSubConfig();
        root.set(List.of("client"), client);
        client.set(List.of("projectileModelExclusions"), encoded);
        StringWriter output = new StringWriter();
        new TomlWriter().write(root, output);
        String toml = output.toString();

        assertTrue(toml.contains("[client.projectileModelExclusions]"));
        assertTrue(toml.contains("\"wine_fox/22_elf\" = ["));
    }
}
