package net.okitsu.ysmepicfightcompat.network;

import net.minecraft.network.FriendlyByteBuf;

import java.util.LinkedHashMap;
import java.util.Map;

/** Validation and bounded wire encoding for session-only YSM configuration variables. */
public final class ConfigurationVariableValues {
    public static final int MAX_VARIABLES = 256;
    private static final int MAX_NAME_LENGTH = 256;

    private ConfigurationVariableValues() {
    }

    public static Map<String, Double> validate(Map<String, Double> values) {
        if (values == null || values.size() > MAX_VARIABLES) {
            throw new IllegalArgumentException("Invalid configuration-variable count");
        }
        Map<String, Double> checked = new LinkedHashMap<>();
        values.forEach((name, value) -> {
            if (!isOrdinaryName(name) || value == null || !Double.isFinite(value)) {
                throw new IllegalArgumentException("Invalid configuration variable");
            }
            String canonicalName = canonicalName(name);
            if (checked.put(canonicalName, value) != null) {
                throw new IllegalArgumentException("Duplicate configuration variable alias");
            }
        });
        return Map.copyOf(checked);
    }

    public static Map<String, Double> merge(Map<String, Double> current,
                                             Map<String, Double> changes) {
        Map<String, Double> merged = new LinkedHashMap<>(validate(current));
        merged.putAll(validate(changes));
        return validate(merged);
    }

    public static void write(FriendlyByteBuf output, Map<String, Double> values) {
        Map<String, Double> checked = validate(values);
        output.writeVarInt(checked.size());
        checked.forEach((name, value) -> {
            output.writeUtf(name, MAX_NAME_LENGTH);
            output.writeDouble(value);
        });
    }

    public static Map<String, Double> read(FriendlyByteBuf input) {
        int count = input.readVarInt();
        if (count < 0 || count > MAX_VARIABLES) {
            throw new IllegalArgumentException("Invalid configuration-variable count");
        }
        Map<String, Double> values = new LinkedHashMap<>();
        for (int index = 0; index < count; index++) {
            String name = input.readUtf(MAX_NAME_LENGTH);
            double value = input.readDouble();
            if (values.put(name, value) != null) {
                throw new IllegalArgumentException("Duplicate configuration variable");
            }
        }
        return validate(values);
    }

    private static boolean isOrdinaryName(String name) {
        if (name == null || name.isBlank() || name.length() > MAX_NAME_LENGTH) {
            return false;
        }
        boolean variable = (name.startsWith("v.") && name.length() > "v.".length())
                || (name.startsWith("variable.")
                && name.length() > "variable.".length());
        boolean roaming = name.startsWith("v.roaming.")
                || name.startsWith("variable.roaming.");
        if (!variable || roaming) {
            return false;
        }
        return name.codePoints().allMatch(codePoint -> codePoint >= 0x20 && codePoint != 0x7f);
    }

    private static String canonicalName(String name) {
        return name.startsWith("variable.")
                ? "v." + name.substring("variable.".length()) : name;
    }
}
