package net.okitsu.ysmepicfightcompat.network;

import com.electronwill.nightconfig.core.Config;
import com.electronwill.nightconfig.toml.TomlWriter;
import net.okitsu.ysmepicfightcompat.animation.MovementAnimationType;
import org.junit.jupiter.api.Test;

import java.io.StringWriter;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MovementAnimationPolicyTest {
    private static final String SAINT = "wine_fox/21_saint";
    private static final String MAGICAL = "wine_fox/05_magical";

    @Test
    void exclusionsNeverEnableAYsmSettingThatIsOff() {
        MovementAnimationPolicy policy = MovementAnimationPolicy.create(false,
                Map.of(SAINT, List.of("run", "creative_flight")));

        assertFalse(policy.usesYsm(SAINT, MovementAnimationType.RUN));
        assertFalse(policy.usesYsm(" WINE_FOX/21_SAINT ",
                MovementAnimationType.CREATIVE_FLIGHT));
        assertFalse(policy.usesYsm(SAINT, MovementAnimationType.WALK));
        assertFalse(policy.usesYsm(MAGICAL, MovementAnimationType.RUN));
        assertFalse(policy.usesYsm(SAINT, null));
    }

    @Test
    void exclusionsDisableOnlyListedModelStatesWhenEnabled() {
        MovementAnimationPolicy policy = MovementAnimationPolicy.create(true,
                Map.of(SAINT, List.of("elytra_flight")));

        assertFalse(policy.usesYsm(SAINT, MovementAnimationType.ELYTRA_FLIGHT));
        assertTrue(policy.usesYsm(SAINT, MovementAnimationType.RUN));
        assertTrue(policy.usesYsm(MAGICAL, MovementAnimationType.ELYTRA_FLIGHT));
    }

    @Test
    void acceptsOnlyTheFiniteSemanticMovementVocabulary() {
        assertEquals(MovementAnimationType.values().length,
                MovementAnimationPolicy.MAX_SELECTORS_PER_MODEL);
        for (MovementAnimationType movement : MovementAnimationType.values()) {
            assertEquals(movement,
                    MovementAnimationType.fromConfigKey(movement.configKey()));
            assertEquals(movement, MovementAnimationType.fromConfigKey(
                    "  " + movement.configKey().toUpperCase() + "  "));
            assertTrue(MovementAnimationPolicy.isValidSelector(
                    movement.configKey()));
        }
        assertNull(MovementAnimationType.fromConfigKey("idle"));
        assertFalse(MovementAnimationPolicy.isValidSelector("fly"));
        assertFalse(MovementAnimationPolicy.isValidSelector(1));
        assertThrows(IllegalArgumentException.class, () ->
                MovementAnimationPolicy.create(false,
                        Map.of(SAINT, List.of("run", "unknown"))));
    }

    @Test
    void rejectsDuplicateNormalizedIdsAndUnsafeIds() {
        assertThrows(IllegalArgumentException.class, () ->
                MovementAnimationPolicy.create(false, Map.of(
                        SAINT, List.of("run"),
                        " WINE_FOX/21_SAINT ", List.of("walk"))));
        assertThrows(IllegalArgumentException.class, () ->
                MovementAnimationPolicy.create(false,
                        Map.of("bad\nmodel", List.of("run"))));
        assertThrows(IllegalArgumentException.class, () ->
                MovementAnimationPolicy.create(false,
                        Map.of("x".repeat(
                                MovementAnimationPolicy.MAX_MODEL_ID_LENGTH + 1),
                                List.of("run"))));
    }

    @Test
    void encodesModelIdsAsTomlTableKeysAndRestoresNormalizedRules() {
        Map<String, List<String>> source = Map.of(
                " WINE_FOX/21_SAINT ",
                List.of(" RUN ", "creative_flight", "run"));
        Map<String, List<String>> expected = Map.of(SAINT,
                List.of("run", "creative_flight"));
        Config encoded = MovementAnimationPolicy.encodeConfiguration(source);

        assertTrue(MovementAnimationPolicy.isValidConfiguration(encoded));
        assertEquals(expected,
                MovementAnimationPolicy.decodeConfiguration(encoded));

        Config root = Config.inMemory();
        Config client = root.createSubConfig();
        root.set(List.of("client"), client);
        client.set(List.of("movementAnimationExclusions"), encoded);
        StringWriter output = new StringWriter();
        new TomlWriter().write(root, output);
        String toml = output.toString();

        assertTrue(toml.contains("[client.movementAnimationExclusions]"));
        assertTrue(toml.contains("\"wine_fox/21_saint\" = ["));
    }

    @Test
    void everyConfiguredMovementKeyIsUnique() {
        long uniqueKeys = Arrays.stream(MovementAnimationType.values())
                .map(MovementAnimationType::configKey)
                .distinct().count();
        assertEquals(MovementAnimationType.values().length, uniqueKeys);
    }
}
