package net.okitsu.ysmepicfightcompat.animation;

import java.util.Map;
import java.util.Objects;

/** One bounded snapshot; looking up an older render selection never destroys it. */
final class ModelConfigurationOverrides {
    private final ConfigurationVariableOverrides overrides = new ConfigurationVariableOverrides();
    private String modelId;

    synchronized ConfigurationVariableOverrides.Lookup lookup(String requestedModelId, int slot) {
        return Objects.equals(modelId, requestedModelId) ? overrides.lookup(slot)
                : ConfigurationVariableOverrides.Lookup.missing();
    }

    synchronized Map<String, Double> evaluate(String selectedModelId, String expression,
                                              ExpressionEngine.Environment fallback) {
        if (!Objects.equals(modelId, selectedModelId)) {
            accept(selectedModelId, Map.of());
        }
        return overrides.evaluate(expression, fallback);
    }

    synchronized void accept(String selectedModelId, Map<String, Double> values) {
        modelId = selectedModelId;
        overrides.replace(values);
    }
}
