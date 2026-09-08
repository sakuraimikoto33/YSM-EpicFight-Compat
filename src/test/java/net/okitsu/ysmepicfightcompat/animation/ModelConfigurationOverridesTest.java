package net.okitsu.ysmepicfightcompat.animation;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelConfigurationOverridesTest {
    private static final String MODEL_A = "test:model_a";
    private static final String MODEL_B = "test:model_b";

    @Test
    void lookingUpTheOldRenderSelectionDoesNotDiscardANewerSnapshot() {
        ModelConfigurationOverrides overrides = new ModelConfigurationOverrides();
        overrides.accept(MODEL_A, Map.of("v.eye", 1.0D));
        overrides.accept(MODEL_B, Map.of("v.eye", 0.0D, "v.hat", 2.0D));

        assertFalse(overrides.lookup(MODEL_A, slot("v.eye")).present());
        assertFalse(overrides.lookup(MODEL_A, slot("v.hat")).present());
        assertFalse(overrides.lookup(null, slot("v.eye")).present());

        assertValue(overrides, MODEL_B, "v.eye", 0.0D);
        assertValue(overrides, MODEL_B, "v.hat", 2.0D);
    }

    @Test
    void evaluatingANewModelStartsWithItsFallbackInsteadOfTheOldModelsValues() {
        ModelConfigurationOverrides overrides = new ModelConfigurationOverrides();
        overrides.accept(MODEL_A, Map.of("v.eye", 40.0D, "v.old_only", 9.0D));
        Fallback fallback = new Fallback(Map.of("v.eye", 2.0D));

        Map<String, Double> changes = overrides.evaluate(MODEL_B,
                "v.eye+=1;v.new_only=v.old_only+4;", fallback);

        assertEquals(Map.of("v.eye", 3.0D, "v.new_only", 4.0D), changes);
        assertValue(overrides, MODEL_B, "v.eye", 3.0D);
        assertFalse(overrides.lookup(MODEL_B, slot("v.old_only")).present());
        assertFalse(overrides.lookup(MODEL_A, slot("v.eye")).present());
        assertEquals(2.0D, fallback.readVariable(slot("v.eye")), 0.0001D);
        assertFalse(fallback.hasVariable(slot("v.new_only")));
    }

    @Test
    void sameModelExpressionsMergeWithTheReceivedSnapshotIncludingExplicitZero() {
        ModelConfigurationOverrides overrides = new ModelConfigurationOverrides();
        overrides.accept(MODEL_B, Map.of("v.eye", 1.0D, "v.hat", 3.0D));
        Fallback fallback = new Fallback(Map.of("v.eye", 20.0D));

        assertEquals(Map.of("v.eye", 0.0D), overrides.evaluate(
                MODEL_B, "variable.eye=1-variable.eye;", fallback));
        assertValue(overrides, MODEL_B, "v.eye", 0.0D);
        assertValue(overrides, MODEL_B, "v.hat", 3.0D);
        assertEquals(Map.of("v.eye", 2.0D), overrides.evaluate(
                MODEL_B, "v.eye+=2;", fallback));
        assertValue(overrides, MODEL_B, "variable.eye", 2.0D);
        assertValue(overrides, MODEL_B, "v.hat", 3.0D);
    }

    @Test
    void acceptingTheSameModelReplacesRatherThanMergesItsSnapshot() {
        ModelConfigurationOverrides overrides = new ModelConfigurationOverrides();
        overrides.accept(MODEL_B, Map.of("v.eye", 1.0D, "v.old_only", 4.0D));

        overrides.accept(MODEL_B, Map.of("v.eye", 0.0D));

        assertValue(overrides, MODEL_B, "v.eye", 0.0D);
        assertFalse(overrides.lookup(MODEL_B, slot("v.old_only")).present());
    }

    @Test
    void anEmptySnapshotResetsTheModelAndTheNextEditReadsItsFallback() {
        ModelConfigurationOverrides overrides = new ModelConfigurationOverrides();
        overrides.accept(MODEL_B, Map.of("v.eye", 7.0D));

        overrides.accept(MODEL_B, Map.of());

        assertFalse(overrides.lookup(MODEL_B, slot("v.eye")).present());
        assertEquals(Map.of("v.eye", 3.0D), overrides.evaluate(MODEL_B,
                "v.eye+=1;", new Fallback(Map.of("v.eye", 2.0D))));
        assertValue(overrides, MODEL_B, "v.eye", 3.0D);
    }

    @Test
    void roamingAndTemporaryVariablesNeverBecomeModelConfigurationOverrides() {
        ModelConfigurationOverrides overrides = new ModelConfigurationOverrides();
        Fallback fallback = new Fallback(Map.of("v.roaming.hat", 6.0D));

        Map<String, Double> changes = overrides.evaluate(MODEL_B,
                "v.eye=1;v.roaming.hat=2;variable.roaming.jacket=3;t.scratch=4;",
                fallback);

        assertEquals(Map.of("v.eye", 1.0D), changes);
        assertValue(overrides, MODEL_B, "v.eye", 1.0D);
        assertFalse(overrides.lookup(MODEL_B, slot("v.roaming.hat")).present());
        assertFalse(overrides.lookup(MODEL_B, slot("variable.roaming.jacket")).present());
        assertFalse(overrides.lookup(MODEL_B, slot("t.scratch")).present());
        assertEquals(6.0D, fallback.readVariable(slot("v.roaming.hat")), 0.0001D);
    }

    private static int slot(String name) {
        return ExpressionEngine.slot(name);
    }

    private static void assertValue(ModelConfigurationOverrides overrides,
                                    String modelId, String name, double expected) {
        ConfigurationVariableOverrides.Lookup actual = overrides.lookup(modelId, slot(name));
        assertTrue(actual.present(), name + " must be assigned, including an explicit zero");
        assertEquals(expected, actual.value(), 0.0001D);
    }

    private static final class Fallback implements ExpressionEngine.Environment {
        private final Map<Integer, Double> values = new HashMap<>();

        private Fallback(Map<String, Double> initial) {
            initial.forEach((name, value) -> values.put(slot(name), value));
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
