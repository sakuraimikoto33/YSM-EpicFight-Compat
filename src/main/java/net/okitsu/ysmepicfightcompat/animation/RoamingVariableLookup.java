package net.okitsu.ysmepicfightcompat.animation;

import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntFunction;
import java.util.function.ToIntFunction;

/** Resolves Molang variable names against official YSM's hashed live provider. */
final class RoamingVariableLookup {
    private static final String VARIABLE_PREFIX = "v.";
    private static final String ROAMING_PREFIX = "v.roaming.";

    record Lookup(boolean present, double value) {
        private static final Lookup MISSING = new Lookup(false, 0.0D);

        static Lookup missing() {
            return MISSING;
        }
    }

    private final ToIntFunction<String> nameHasher;
    private final Map<String, int[]> hashesByName = new ConcurrentHashMap<>();

    RoamingVariableLookup(ToIntFunction<String> nameHasher) {
        this.nameHasher = Objects.requireNonNull(nameHasher, "nameHasher");
    }

    Lookup lookup(String variableName, IntFunction<Object> valueProvider) {
        String canonicalName = canonicalName(variableName);
        if (!isSupported(canonicalName)) {
            return Lookup.missing();
        }
        boolean numericValueSeen = false;
        for (int hash : hashesByName.computeIfAbsent(canonicalName, this::hashCandidates)) {
            Object raw = valueProvider.apply(hash);
            if (raw instanceof Number number) {
                numericValueSeen = true;
                double value = number.doubleValue();
                if (Double.isFinite(value) && value != 0.0D) {
                    return new Lookup(true, value);
                }
            }
        }
        // The official getter returns numeric zero for an unknown ordinary-variable hash.
        // Roaming names are known to belong to this provider, but for ordinary v.* names a
        // zero-only result cannot distinguish "missing" from an explicit zero. Explicit
        // ordinary values are mirrored separately by OfficialConfigurationVariables.
        return numericValueSeen && isRoaming(canonicalName)
                ? new Lookup(true, 0.0D) : Lookup.missing();
    }

    private int[] hashCandidates(String variableName) {
        boolean roaming = isRoaming(variableName);
        String suffix = variableName.substring(
                roaming ? ROAMING_PREFIX.length() : VARIABLE_PREFIX.length());
        int[] candidates = roaming ? new int[]{
                nameHasher.applyAsInt(variableName),
                nameHasher.applyAsInt(suffix),
                nameHasher.applyAsInt("roaming." + suffix),
                nameHasher.applyAsInt("variable.roaming." + suffix)
        } : new int[]{
                nameHasher.applyAsInt(variableName),
                nameHasher.applyAsInt(suffix),
                nameHasher.applyAsInt("variable." + suffix)
        };
        return Arrays.stream(candidates).distinct().toArray();
    }

    static boolean isRoaming(String variableName) {
        String canonicalName = canonicalName(variableName);
        return canonicalName != null && canonicalName.startsWith(ROAMING_PREFIX)
                && canonicalName.length() > ROAMING_PREFIX.length();
    }

    private static boolean isSupported(String variableName) {
        return variableName != null && variableName.startsWith(VARIABLE_PREFIX)
                && variableName.length() > VARIABLE_PREFIX.length();
    }

    private static String canonicalName(String variableName) {
        return variableName != null && variableName.startsWith("variable.")
                ? VARIABLE_PREFIX + variableName.substring("variable.".length())
                : variableName;
    }
}
