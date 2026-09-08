package net.okitsu.ysmepicfightcompat.animation;

import net.okitsu.ysmepicfightcompat.network.ConfigurationVariableValues;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** A confirmed snapshot overlaid with bounded, already-evaluated local writes. */
final class ModelConfigurationOverrides {
    enum InitialSnapshotResult { REPLACED, PRESERVED, RESCOPE }

    private record Pending(long sequence, double value) {
    }

    private ConfigurationVariableOverrides overrides = new ConfigurationVariableOverrides();
    private String modelId;
    private UUID context = UUID.randomUUID();
    private UUID serverScope;
    private long sequence;
    private long revision = -1;
    private long acknowledgedSequence;
    private Map<String, Double> confirmed = Map.of();
    // Only the latest absolute write for each variable is retained, not an
    // unbounded list of commands. An ACK never evaluates the source again.
    private Map<String, Pending> pending = Map.of();

    synchronized ConfigurationVariableOverrides.Lookup lookup(String requestedModelId, int slot) {
        return Objects.equals(modelId, requestedModelId) ? overrides.lookup(slot)
                : ConfigurationVariableOverrides.Lookup.missing();
    }

    synchronized Map<String, Double> evaluate(String selectedModelId, String expression,
                                              ExpressionEngine.Environment fallback) {
        boolean sameModel = Objects.equals(modelId, selectedModelId);
        long nextSequence = sameModel ? Math.incrementExact(sequence) : 1;
        Map<String, Double> nextConfirmed = sameModel ? confirmed : Map.of();
        Map<String, Pending> nextPending = new LinkedHashMap<>(sameModel ? pending : Map.of());
        ConfigurationVariableOverrides candidate = new ConfigurationVariableOverrides();
        candidate.replace(composite(nextConfirmed, nextPending));
        Map<String, Double> changes = candidate.evaluate(expression, fallback);
        changes.forEach((name, value) -> nextPending.put(name, new Pending(nextSequence, value)));
        candidate.replace(composite(nextConfirmed, nextPending));
        // All count/name/value checks complete before changing the active model,
        // context, sequence, confirmed base, pending set, or visible overlay.
        if (!sameModel) {
            newContext(selectedModelId);
        }
        confirmed = nextConfirmed;
        pending = Map.copyOf(nextPending);
        sequence = nextSequence;
        overrides = candidate;
        return changes;
    }

    synchronized void accept(String selectedModelId, Map<String, Double> values) {
        Map<String, Double> checked = ConfigurationVariableValues.validate(values);
        ConfigurationVariableOverrides candidate = new ConfigurationVariableOverrides();
        candidate.replace(checked);
        newContext(selectedModelId);
        confirmed = checked;
        overrides = candidate;
    }

    synchronized void selectModel(String selectedModelId) {
        if (!Objects.equals(modelId, selectedModelId)) {
            accept(selectedModelId, Map.of());
        }
    }

    /**
     * Handles an unsolicited initial snapshot after the caller selects the live model.
     * Already-evaluated local edits survive initial/death notifications and are resent
     * only after any existing server scope has been replaced; expressions are not replayed.
     */
    synchronized InitialSnapshotResult acceptInitialSnapshot(
            String selectedModelId, Map<String, Double> values) {
        if (pending.isEmpty()) {
            accept(selectedModelId, values);
            return InitialSnapshotResult.REPLACED;
        }
        if (serverScope != null) {
            scope(null);
            return InitialSnapshotResult.RESCOPE;
        }
        return InitialSnapshotResult.PRESERVED;
    }

    synchronized boolean acknowledge(String selectedModelId, UUID expectedContext,
                                       UUID expectedScope, long serverRevision,
                                       long throughSequence, Map<String, Double> values) {
        if (!Objects.equals(modelId, selectedModelId) || !context.equals(expectedContext)
                || serverScope == null || !serverScope.equals(expectedScope)
                || serverRevision < 0 || serverRevision < revision
                || throughSequence < acknowledgedSequence || throughSequence > sequence
                || serverRevision == revision && throughSequence == acknowledgedSequence) {
            return false;
        }
        Map<String, Double> nextConfirmed = ConfigurationVariableValues.validate(values);
        Map<String, Pending> nextPending = new LinkedHashMap<>(pending);
        nextPending.values().removeIf(write -> write.sequence() <= throughSequence);
        ConfigurationVariableOverrides candidate = new ConfigurationVariableOverrides();
        candidate.replace(composite(nextConfirmed, nextPending));
        confirmed = nextConfirmed;
        pending = Map.copyOf(nextPending);
        overrides = candidate;
        revision = serverRevision;
        acknowledgedSequence = throughSequence;
        return true;
    }

    synchronized String modelId() {
        return modelId;
    }

    synchronized UUID context() {
        return context;
    }

    synchronized long sequence() {
        return sequence;
    }

    synchronized Map<String, Double> pendingChanges() {
        Map<String, Double> values = new LinkedHashMap<>();
        pending.forEach((name, write) -> values.put(name, write.value()));
        return Map.copyOf(values);
    }

    synchronized UUID serverScope() {
        return serverScope;
    }

    synchronized void scope(UUID value) {
        if (!Objects.equals(serverScope, value)) {
            serverScope = value;
            revision = -1;
            acknowledgedSequence = 0;
        }
    }

    private void newContext(String selectedModelId) {
        modelId = selectedModelId;
        context = UUID.randomUUID();
        serverScope = null;
        sequence = 0;
        revision = -1;
        acknowledgedSequence = 0;
        confirmed = Map.of();
        pending = Map.of();
    }

    private static Map<String, Double> composite(Map<String, Double> base,
                                                  Map<String, Pending> writes) {
        Map<String, Double> combined = new LinkedHashMap<>(base);
        writes.forEach((name, write) -> combined.put(name, write.value()));
        return ConfigurationVariableValues.validate(combined);
    }
}
