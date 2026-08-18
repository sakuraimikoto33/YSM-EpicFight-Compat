package net.okitsu.ysmepicfightcompat.animation;

import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntFunction;
import java.util.function.ToIntFunction;

/** Resolves Molang roaming names against official YSM's hashed live provider. */
final class RoamingVariableLookup {
    private static final String PREFIX = "v.roaming.";

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
        if (!isRoaming(variableName)) {
            return Lookup.missing();
        }
        boolean numericValueSeen = false;
        for (int hash : hashesByName.computeIfAbsent(variableName, this::hashCandidates)) {
            Object raw = valueProvider.apply(hash);
            if (raw instanceof Number number) {
                numericValueSeen = true;
                double value = number.doubleValue();
                if (Double.isFinite(value) && value != 0.0D) {
                    return new Lookup(true, value);
                }
            }
        }
        return numericValueSeen ? new Lookup(true, 0.0D) : Lookup.missing();
    }

    private int[] hashCandidates(String variableName) {
        String suffix = variableName.substring(PREFIX.length());
        int[] candidates = {
                nameHasher.applyAsInt(variableName),
                nameHasher.applyAsInt(suffix),
                nameHasher.applyAsInt("roaming." + suffix)
        };
        return Arrays.stream(candidates).distinct().toArray();
    }

    private static boolean isRoaming(String variableName) {
        return variableName != null && variableName.startsWith(PREFIX)
                && variableName.length() > PREFIX.length();
    }
}
