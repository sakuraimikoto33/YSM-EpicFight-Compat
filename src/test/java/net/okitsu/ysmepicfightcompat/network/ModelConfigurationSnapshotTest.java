package net.okitsu.ysmepicfightcompat.network;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelConfigurationSnapshotTest {
    private static final String MODEL_A = "test:model_a";
    private static final String MODEL_B = "test:model_b";

    @Test
    void aMissingSnapshotStartsEmptyForTheSelectedModel() {
        ModelConfigurationSnapshot snapshot = ModelConfigurationSnapshot.reconcile(null, MODEL_B);

        assertEquals(MODEL_B, snapshot.modelId());
        assertTrue(snapshot.values().isEmpty());
    }

    @Test
    void aDelayedSelectionPollPreservesChangesAlreadyAcceptedForTheSameModel() {
        ModelConfigurationSnapshot old = new ModelConfigurationSnapshot(
                MODEL_A, Map.of("v.old_only", 9.0D));
        ModelConfigurationSnapshot accepted = ModelConfigurationSnapshot.reconcile(old, MODEL_B)
                .merge(Map.of("v.eye", 0.0D, "v.hat", 2.0D));

        ModelConfigurationSnapshot polled = ModelConfigurationSnapshot.reconcile(accepted, MODEL_B);

        assertSame(accepted, polled);
        assertEquals(Map.of("v.eye", 0.0D, "v.hat", 2.0D), polled.values());
        assertEquals(Map.of("v.old_only", 9.0D), old.values());
    }

    @Test
    void aModelChangeDropsOldValuesBeforeApplyingTheNewModelsChanges() {
        ModelConfigurationSnapshot old = new ModelConfigurationSnapshot(
                MODEL_A, Map.of("v.eye", 7.0D, "v.old_only", 9.0D));

        ModelConfigurationSnapshot changed = ModelConfigurationSnapshot.reconcile(old, MODEL_B);
        assertNotSame(old, changed);
        assertEquals(MODEL_B, changed.modelId());
        assertTrue(changed.values().isEmpty());

        ModelConfigurationSnapshot updated = changed.merge(Map.of("v.eye", 0.0D));
        assertEquals(MODEL_B, updated.modelId());
        assertEquals(Map.of("v.eye", 0.0D), updated.values());
        assertEquals(Map.of("v.eye", 7.0D, "v.old_only", 9.0D), old.values());
    }

    @Test
    void sameModelUpdatesMergeAndKeepExplicitZeroWithoutMutatingEarlierSnapshots() {
        ModelConfigurationSnapshot old = new ModelConfigurationSnapshot(
                MODEL_B, Map.of("v.eye", 1.0D, "v.hat", 2.0D));

        ModelConfigurationSnapshot updated = ModelConfigurationSnapshot.reconcile(old, MODEL_B)
                .merge(Map.of("variable.eye", 0.0D));

        assertEquals(Map.of("v.eye", 0.0D, "v.hat", 2.0D), updated.values());
        assertEquals(Map.of("v.eye", 1.0D, "v.hat", 2.0D), old.values());
        assertThrows(UnsupportedOperationException.class,
                () -> updated.values().put("v.eye", 3.0D));
    }

    @Test
    void removingTheSelectionResetsValuesWhileAnEmptySameModelUpdateDoesNot() {
        ModelConfigurationSnapshot old = new ModelConfigurationSnapshot(
                MODEL_B, Map.of("v.eye", 1.0D));

        assertEquals(old, old.merge(Map.of()));
        ModelConfigurationSnapshot unselected = ModelConfigurationSnapshot.reconcile(old, "");
        assertEquals("", unselected.modelId());
        assertTrue(unselected.values().isEmpty());
        assertEquals(Map.of("v.eye", 1.0D), old.values());
    }

    @Test
    void roamingAndInvalidUpdatesAreRejectedWithoutChangingTheCurrentSnapshot() {
        ModelConfigurationSnapshot current = new ModelConfigurationSnapshot(
                MODEL_B, Map.of("v.eye", 0.0D));

        assertThrows(IllegalArgumentException.class,
                () -> current.merge(Map.of("v.roaming.hat", 1.0D)));
        assertThrows(IllegalArgumentException.class,
                () -> current.merge(Map.of("variable.roaming.hat", 1.0D)));
        assertThrows(IllegalArgumentException.class,
                () -> current.merge(Map.of("v.eye", Double.NaN)));
        assertEquals(Map.of("v.eye", 0.0D), current.values());
    }
}
