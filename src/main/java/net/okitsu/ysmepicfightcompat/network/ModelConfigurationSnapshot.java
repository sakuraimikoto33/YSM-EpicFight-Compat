package net.okitsu.ysmepicfightcompat.network;

import java.util.Map;

/** Model-scoped server state, independent of selection notification timing. */
record ModelConfigurationSnapshot(String modelId, Map<String, Double> values) {
    static ModelConfigurationSnapshot reconcile(ModelConfigurationSnapshot current, String modelId) {
        return current != null && current.modelId().equals(modelId)
                ? current : new ModelConfigurationSnapshot(modelId, Map.of());
    }

    ModelConfigurationSnapshot merge(Map<String, Double> changes) {
        return new ModelConfigurationSnapshot(modelId,
                ConfigurationVariableValues.merge(values, changes));
    }
}
