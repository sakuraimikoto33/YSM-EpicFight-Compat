package net.okitsu.ysmepicfightcompat.animation;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.List;

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

    @Test
    void typedQueryAndFunctionResultsCanComputeNumericConfigurationValues() {
        ConfigurationVariableOverrides overrides = new ConfigurationVariableOverrides();
        Fallback fallback = new Fallback();
        Map<String, Double> changes = overrides.evaluate(
                "t.entries=ysm.effects();v.total=0;for_each(t.entry,t.entries,{v.total+=t.entry.level;});"
                        + "v.blue=ysm.texture_name=='Blue';v.argument=args[0];", fallback);

        assertEquals(Map.of("v.total", 5.0D, "v.blue", 1.0D, "v.argument", 4.0D), changes);
        assertFalse(overrides.lookup(ExpressionEngine.slot("t.entries")).present());
    }

    @Test
    void customFunctionWritesRemainInsideTheConfigurationOverlay() {
        ConfigurationVariableOverrides overrides = new ConfigurationVariableOverrides();
        Fallback fallback = new Fallback();
        fallback.runtime = new MolangScriptRuntime(Map.of(
                "edit", "v.eye+=args[0];return v.eye;",
                "read", "return v.eye;"), Map.of());

        Map<String, Double> changes = overrides.evaluate(
                "v.eye=2;v.result=fn.edit(3);v.read=fn.read;", fallback);

        assertEquals(Map.of("v.eye", 5.0D, "v.result", 5.0D, "v.read", 5.0D), changes);
        assertFalse(fallback.hasVariable(ExpressionEngine.slot("v.eye")));
    }

    private static final class Fallback implements MolangScriptRuntime.Host {
        private final Map<Integer, Double> values = new HashMap<>();
        private MolangScriptRuntime runtime;

        @Override
        public MolangScriptRuntime scripts() {
            return runtime;
        }

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
        public Object readQueryValue(int slot) {
            return ExpressionEngine.slotName(slot).equals("ysm.texture_name") ? "Blue" : readQuery(slot);
        }

        @Override
        public Object invokeValue(String name, Object[] arguments) {
            return name.equals("ysm.effects") ? List.of(Map.of("level", 2), Map.of("level", 3)) : 0.0D;
        }

        @Override
        public Object[] arguments() {
            return new Object[]{4.0D};
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
