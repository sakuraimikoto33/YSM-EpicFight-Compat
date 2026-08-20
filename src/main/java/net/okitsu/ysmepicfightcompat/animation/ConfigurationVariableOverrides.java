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
        Map<Integer, Double> workingValues = new HashMap<>(values);
        Set<Integer> workingAssigned = new HashSet<>(assigned);
        Set<Integer> written = new HashSet<>();
        ExpressionEngine.compile(source).evaluate(new OverlayEnvironment(
                workingValues, workingAssigned, written, fallback));

        for (int slot : workingAssigned) {
            if (isOrdinaryVariable(slot)) {
                values.put(slot, workingValues.getOrDefault(slot, 0.0D));
                assigned.add(slot);
            }
        }
        Map<String, Double> changes = new LinkedHashMap<>();
        for (int slot : written) {
            if (isOrdinaryVariable(slot)) {
                changes.put(ExpressionEngine.slotName(slot),
                        workingValues.getOrDefault(slot, 0.0D));
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

    private record OverlayEnvironment(Map<Integer, Double> values, Set<Integer> assigned,
                                      Set<Integer> written,
                                      ExpressionEngine.Environment fallback)
            implements ExpressionEngine.Environment {
        @Override
        public double readVariable(int slot) {
            return assigned.contains(slot) ? values.getOrDefault(slot, 0.0D)
                    : fallback.readVariable(slot);
        }

        @Override
        public boolean hasVariable(int slot) {
            return assigned.contains(slot) || fallback.hasVariable(slot);
        }

        @Override
        public void writeVariable(int slot, double value) {
            values.put(slot, Double.isFinite(value) ? value : 0.0D);
            assigned.add(slot);
            written.add(slot);
        }

        @Override
        public double readQuery(int slot) {
            return fallback.readQuery(slot);
        }

        @Override
        public double invoke(String name, double[] arguments) {
            return fallback.invoke(name, arguments);
        }

        @Override
        public double invokeWithText(String name, String[] arguments) {
            return fallback.invokeWithText(name, arguments);
        }
    }
}
