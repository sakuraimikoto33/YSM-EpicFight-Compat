package net.okitsu.ysmepicfightcompat.network;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ConfigurationVariableValuesTest {
    @Test
    void mergesExplicitZeroWithoutDroppingPreviousValues() {
        assertEquals(Map.of("v.eye", 0.0D, "v.hat", 1.0D),
                ConfigurationVariableValues.merge(Map.of("v.hat", 1.0D),
                        Map.of("v.eye", 0.0D)));
    }

    @Test
    void rejectsRoamingAndNonFiniteValues() {
        assertThrows(IllegalArgumentException.class, () ->
                ConfigurationVariableValues.validate(Map.of("v.roaming.hat", 1.0D)));
        assertThrows(IllegalArgumentException.class, () ->
                ConfigurationVariableValues.validate(Map.of("v.hat", Double.NaN)));
    }

    @Test
    void canonicalizesTheFullVariableNamespace() {
        assertEquals(Map.of("v.eye", 1.0D),
                ConfigurationVariableValues.validate(Map.of("variable.eye", 1.0D)));
        assertThrows(IllegalArgumentException.class, () ->
                ConfigurationVariableValues.validate(
                        Map.of("v.eye", 1.0D, "variable.eye", 1.0D)));
    }
}
