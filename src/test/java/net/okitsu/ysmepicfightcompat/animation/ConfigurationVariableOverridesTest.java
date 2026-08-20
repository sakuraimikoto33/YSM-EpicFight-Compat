package net.okitsu.ysmepicfightcompat.animation;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigurationVariableOverridesTest {
    @Test
    void mirrorsOrdinaryVariablesButLeavesRoamingPersistenceToOfficialYsm() {
        ConfigurationVariableOverrides overrides = new ConfigurationVariableOverrides();
        Map<String, Double> changes = overrides.evaluate(
                "v.eye=1;v.roaming.jacket=2;", new Fallback());

        ConfigurationVariableOverrides.Lookup eye = overrides.lookup(
                ExpressionEngine.slot("v.eye"));
        assertTrue(eye.present());
        assertEquals(1.0D, eye.value(), 0.0001D);
        assertFalse(overrides.lookup(ExpressionEngine.slot("v.roaming.jacket")).present());
        assertEquals(Map.of("v.eye", 1.0D), changes);
    }

    @Test
    void laterConfigurationExpressionsCanReadThePreviouslyMirroredValue() {
        ConfigurationVariableOverrides overrides = new ConfigurationVariableOverrides();
        Fallback fallback = new Fallback();

        overrides.evaluate("v.eye=1;", fallback);
        overrides.evaluate("v.eye=1-v.eye;", fallback);

        assertEquals(0.0D, overrides.lookup(ExpressionEngine.slot("v.eye")).value(),
                0.0001D);
    }

    @Test
    void abbreviatedAndFullNamesShareConfigurationState() {
        ConfigurationVariableOverrides overrides = new ConfigurationVariableOverrides();

        overrides.evaluate("variable.eye=1;", new Fallback());
        overrides.evaluate("v.eye+=1;", new Fallback());

        assertEquals(2.0D, overrides.lookup(ExpressionEngine.slot("variable.eye")).value(),
                0.0001D);
        assertEquals(2.0D, overrides.lookup(ExpressionEngine.slot("v.eye")).value(),
                0.0001D);
    }

    @Test
    void fallsBackToTheOfficialValueBeforeTheFirstLocalUpdate() {
        ConfigurationVariableOverrides overrides = new ConfigurationVariableOverrides();
        Fallback fallback = new Fallback();
        fallback.put("v.eye", 1.0D);

        overrides.evaluate("v.eye=v.eye+1;", fallback);

        assertEquals(2.0D, overrides.lookup(ExpressionEngine.slot("v.eye")).value(),
                0.0001D);
    }

    @Test
    void replacesTheSnapshotIncludingAnExplicitZero() {
        ConfigurationVariableOverrides overrides = new ConfigurationVariableOverrides();
        overrides.evaluate("v.old=1;", new Fallback());

        overrides.replace(Map.of("v.eye", 0.0D));

        assertFalse(overrides.lookup(ExpressionEngine.slot("v.old")).present());
        ConfigurationVariableOverrides.Lookup eye = overrides.lookup(
                ExpressionEngine.slot("v.eye"));
        assertTrue(eye.present());
        assertEquals(0.0D, eye.value(), 0.0001D);
    }

    private static final class Fallback implements ExpressionEngine.Environment {
        private final Map<Integer, Double> values = new HashMap<>();

        void put(String name, double value) {
            values.put(ExpressionEngine.slot(name), value);
        }

        @Override
        public double readVariable(int slot) {
            return values.getOrDefault(slot, 0.0D);
        }

        @Override
        public boolean hasVariable(int slot) {
            return values.containsKey(slot);
        }

        @Override
        public void writeVariable(int slot, double value) {
            values.put(slot, value);
        }

        @Override
        public double readQuery(int slot) {
            return 0.0D;
        }

        @Override
        public double invoke(String name, double[] arguments) {
            return 0.0D;
        }

        @Override
        public double invokeWithText(String name, String[] arguments) {
            return 0.0D;
        }
    }
}
