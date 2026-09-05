package net.okitsu.ysmepicfightcompat.animation;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Session-only values written by YSM model-configuration expressions. */
final class ConfigurationVariableOverrides {
    record Lookup(boolean present, double value) {
        private static final Lookup MISSING = new Lookup(false, 0.0D);

        static Lookup missing() {
            return MISSING;
        }
    }

    private final Map<Integer, Double> values = new HashMap<>();
    private final Set<Integer> assigned = new HashSet<>();

    synchronized Map<String, Double> evaluate(String source,
                                               ExpressionEngine.Environment fallback) {
        Map<Integer, Object> workingValues = new HashMap<>(values);
        Set<Integer> workingAssigned = new HashSet<>(assigned);
        Set<Integer> written = new HashSet<>();
        ExpressionEngine.compile(source).evaluate(new OverlayEnvironment(
                workingValues, workingAssigned, written, fallback));

        for (int slot : workingAssigned) {
            if (isOrdinaryVariable(slot) && workingValues.get(slot) instanceof Number value) {
                values.put(slot, ExpressionEngine.number(value));
                assigned.add(slot);
            }
        }
        Map<String, Double> changes = new LinkedHashMap<>();
        for (int slot : written) {
            if (isOrdinaryVariable(slot) && workingValues.get(slot) instanceof Number value) {
                changes.put(ExpressionEngine.slotName(slot),
                        ExpressionEngine.number(value));
            }
        }
        return Map.copyOf(changes);
    }

    synchronized Lookup lookup(int slot) {
        if (!isOrdinaryVariable(slot) || !assigned.contains(slot)) {
            return Lookup.missing();
        }
        return new Lookup(true, values.getOrDefault(slot, 0.0D));
    }

    synchronized void clear() {
        values.clear();
        assigned.clear();
    }

    synchronized void replace(Map<String, Double> replacement) {
        clear();
        replacement.forEach((name, value) -> {
            int slot = ExpressionEngine.slot(name);
            if (isOrdinaryVariable(slot)) {
                values.put(slot, Double.isFinite(value) ? value : 0.0D);
                assigned.add(slot);
            }
        });
    }

    private static boolean isOrdinaryVariable(int slot) {
        String name = ExpressionEngine.slotName(slot);
        return (name.startsWith("v.") && !name.startsWith("v.roaming."))
                || (name.startsWith("variable.")
                && !name.startsWith("variable.roaming."));
    }

    private record OverlayEnvironment(Map<Integer, Object> values, Set<Integer> assigned,
                                      Set<Integer> written,
                                      ExpressionEngine.Environment fallback)
            implements MolangScriptRuntime.Host {
        @Override
        public MolangScriptRuntime scripts() {
            return MolangScriptRuntime.scripts(fallback);
        }

        @Override
        public double readVariable(int slot) {
            return ExpressionEngine.number(readVariableValue(slot));
        }

        @Override
        public Object readVariableValue(int slot) {
            return assigned.contains(slot) ? values.getOrDefault(slot, 0.0D)
                    : fallback.readVariableValue(slot);
        }

        @Override
        public boolean hasVariable(int slot) {
            return assigned.contains(slot) || fallback.hasVariable(slot);
        }

        @Override
        public void writeVariable(int slot, double value) {
            writeVariableValue(slot, value);
        }

        @Override
        public void writeVariableValue(int slot, Object value) {
            values.put(slot, ExpressionEngine.boundedValue(value));
            assigned.add(slot);
            written.add(slot);
        }

        @Override
        public double readQuery(int slot) {
            return ExpressionEngine.number(readQueryValue(slot));
        }

        @Override
        public Object readQueryValue(int slot) {
            MolangScriptRuntime runtime = scripts();
            if (runtime != null) {
                Object value = runtime.read(ExpressionEngine.slotName(slot), this);
                if (value != MolangScriptRuntime.UNHANDLED) {
                    return value;
                }
            }
            return fallback.readQueryValue(slot);
        }

        @Override
        public Object invokeValue(String name, Object[] arguments) {
            MolangScriptRuntime runtime = scripts();
            if (runtime != null) {
                Object value = runtime.invoke(name, arguments, this);
                if (value != MolangScriptRuntime.UNHANDLED) {
                    return value;
                }
            }
            return fallback.invokeValue(name, arguments);
        }

        @Override
        public Object[] arguments() {
            return fallback.arguments();
        }

        @Override
        public double invoke(String name, double[] arguments) {
            return fallback.invoke(name, arguments);
        }

        @Override
        public double invokeWithText(String name, String[] arguments) {
            return fallback.invokeWithText(name, arguments);
        }

        @Override
        public double invokeWithMixedArguments(String name, String[] textArguments,
                                               double[] numericArguments) {
            return fallback.invokeWithMixedArguments(
                    name, textArguments, numericArguments);
        }
    }
}
