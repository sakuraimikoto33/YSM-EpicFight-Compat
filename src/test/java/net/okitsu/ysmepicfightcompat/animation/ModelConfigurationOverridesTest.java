package net.okitsu.ysmepicfightcompat.animation;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelConfigurationOverridesTest {
    private static final String MODEL_A = "test:model_a";
    private static final String MODEL_B = "test:model_b";

    @Test
    void aReplacementPlayerStateRejectsEveryCombinationOfOldContextAndScope() {
        ModelConfigurationOverrides beforeDeath = new ModelConfigurationOverrides();
        beforeDeath.evaluate(MODEL_A, "v.eye=7;v.old_only=4;", new Fallback(Map.of()));
        UUID oldContext = beforeDeath.context();
        UUID oldScope = UUID.randomUUID();
        beforeDeath.scope(oldScope);
        assertTrue(beforeDeath.acknowledge(MODEL_A, oldContext, oldScope,
                1, 1, Map.of("v.eye", 7.0D, "v.old_only", 4.0D)));

        ModelConfigurationOverrides replacement = new ModelConfigurationOverrides();
        replacement.selectModel(MODEL_A);
        replacement.evaluate(MODEL_A, "v.eye+=1;", new Fallback(Map.of("v.eye", 2.0D)));
        UUID newContext = replacement.context();
        UUID newScope = UUID.randomUUID();
        replacement.scope(newScope);

        assertNotEquals(oldContext, newContext);
        assertFalse(replacement.acknowledge(MODEL_A, oldContext, oldScope,
                2, 1, Map.of("v.eye", 99.0D)));
        assertFalse(replacement.acknowledge(MODEL_A, oldContext, newScope,
                2, 1, Map.of("v.eye", 99.0D)));
        assertFalse(replacement.acknowledge(MODEL_A, newContext, oldScope,
                2, 1, Map.of("v.eye", 99.0D)));
        assertValue(replacement, MODEL_A, "v.eye", 3.0D);
        assertFalse(replacement.lookup(MODEL_A, slot("v.old_only")).present());
        assertEquals(Map.of("v.eye", 3.0D), replacement.pendingChanges());
        assertTrue(replacement.acknowledge(MODEL_A, newContext, newScope,
                1, 1, Map.of("v.eye", 3.0D)));
        assertTrue(replacement.pendingChanges().isEmpty());
    }

    @Test
    void anEmptyInitialSnapshotPreservesNewEditsBeforeTheScopeGrant() {
        ModelConfigurationOverrides replacement = new ModelConfigurationOverrides();
        replacement.selectModel(MODEL_A);
        replacement.evaluate(MODEL_A, "v.eye=0;v.hat=2;", new Fallback(Map.of()));
        UUID context = replacement.context();
        long sequence = replacement.sequence();

        assertEquals(ModelConfigurationOverrides.InitialSnapshotResult.PRESERVED,
                replacement.acceptInitialSnapshot(MODEL_A, Map.of()));

        assertNull(replacement.serverScope());
        assertEquals(context, replacement.context());
        assertEquals(sequence, replacement.sequence());
        assertEquals(Map.of("v.eye", 0.0D, "v.hat", 2.0D), replacement.pendingChanges());
        assertValue(replacement, MODEL_A, "v.eye", 0.0D);
        assertValue(replacement, MODEL_A, "v.hat", 2.0D);
    }

    @Test
    void anEmptyInitialSnapshotRescopesNewEditsOnlyOnceBeforeTheNextGrant() {
        ModelConfigurationOverrides replacement = new ModelConfigurationOverrides();
        replacement.selectModel(MODEL_A);
        replacement.evaluate(MODEL_A, "v.eye=2;", new Fallback(Map.of()));
        UUID context = replacement.context();
        UUID oldScope = UUID.randomUUID();
        replacement.scope(oldScope);
        long sequence = replacement.sequence();

        assertEquals(ModelConfigurationOverrides.InitialSnapshotResult.RESCOPE,
                replacement.acceptInitialSnapshot(MODEL_A, Map.of()));
        assertNull(replacement.serverScope());
        assertFalse(replacement.acknowledge(MODEL_A, context, oldScope,
                1, 1, Map.of("v.eye", 9.0D)));
        assertEquals(ModelConfigurationOverrides.InitialSnapshotResult.PRESERVED,
                replacement.acceptInitialSnapshot(MODEL_A, Map.of()));
        assertEquals(ModelConfigurationOverrides.InitialSnapshotResult.PRESERVED,
                replacement.acceptInitialSnapshot(MODEL_A, Map.of()));
        assertEquals(context, replacement.context());
        assertEquals(sequence, replacement.sequence());
        assertValue(replacement, MODEL_A, "v.eye", 2.0D);
        assertEquals(Map.of("v.eye", 2.0D), replacement.pendingChanges());

        UUID newScope = UUID.randomUUID();
        replacement.scope(newScope);
        assertTrue(replacement.acknowledge(MODEL_A, context, newScope, 0, 0, Map.of()));
        assertValue(replacement, MODEL_A, "v.eye", 2.0D);
        assertTrue(replacement.acknowledge(MODEL_A, context, newScope,
                1, sequence, Map.of("v.eye", 2.0D)));
        assertTrue(replacement.pendingChanges().isEmpty());
    }

    @Test
    void initialNotificationsAndRescopingNeverReplayTheNewPlayersExpression() {
        ModelConfigurationOverrides replacement = new ModelConfigurationOverrides();
        Fallback fallback = new Fallback(Map.of());
        replacement.evaluate(MODEL_A, "v.eye+=q.life_time;ysm.play_sound('tick');", fallback);
        assertEquals(1, fallback.queries);
        assertEquals(1, fallback.invocations);
        assertEquals(ModelConfigurationOverrides.InitialSnapshotResult.PRESERVED,
                replacement.acceptInitialSnapshot(MODEL_A, Map.of()));
        replacement.scope(UUID.randomUUID());
        assertEquals(ModelConfigurationOverrides.InitialSnapshotResult.RESCOPE,
                replacement.acceptInitialSnapshot(MODEL_A, Map.of()));
        assertEquals(ModelConfigurationOverrides.InitialSnapshotResult.PRESERVED,
                replacement.acceptInitialSnapshot(MODEL_A, Map.of()));
        UUID scope = UUID.randomUUID();
        replacement.scope(scope);
        assertTrue(replacement.acknowledge(MODEL_A, replacement.context(), scope,
                0, 0, Map.of()));
        assertTrue(replacement.acknowledge(MODEL_A, replacement.context(), scope,
                1, replacement.sequence(), Map.of("v.eye", 1.0D)));

        assertEquals(1, fallback.queries);
        assertEquals(1, fallback.invocations);
        assertValue(replacement, MODEL_A, "v.eye", 1.0D);
    }

    @Test
    void anEmptyInitialSnapshotWithoutPendingEditsRemovesOverridesInsteadOfAssigningZero() {
        ModelConfigurationOverrides overrides = new ModelConfigurationOverrides();
        overrides.accept(MODEL_A, Map.of("v.eye", 7.0D, "v.old_only", 4.0D));
        UUID oldContext = overrides.context();
        overrides.scope(UUID.randomUUID());
        Fallback newPlayerInit = new Fallback(Map.of("v.eye", 2.0D));

        assertEquals(ModelConfigurationOverrides.InitialSnapshotResult.REPLACED,
                overrides.acceptInitialSnapshot(MODEL_A, Map.of()));

        assertFalse(overrides.lookup(MODEL_A, slot("v.eye")).present());
        assertFalse(overrides.lookup(MODEL_A, slot("v.old_only")).present());
        assertTrue(overrides.pendingChanges().isEmpty());
        assertEquals(0, overrides.sequence());
        assertNull(overrides.serverScope());
        assertNotEquals(oldContext, overrides.context());
        assertEquals(Map.of("v.eye", 3.0D), overrides.evaluate(
                MODEL_A, "v.eye+=1;", newPlayerInit));
        assertEquals(2.0D, newPlayerInit.readVariable(slot("v.eye")), 0.0001D);
        assertValue(overrides, MODEL_A, "v.eye", 3.0D);
    }

    @Test
    void initialSnapshotReplacementPreservesExplicitZeroAndRemovesOnlyOmittedOverrides() {
        ModelConfigurationOverrides overrides = new ModelConfigurationOverrides();
        overrides.accept(MODEL_A, Map.of("v.eye", 7.0D, "v.old_only", 4.0D));

        assertEquals(ModelConfigurationOverrides.InitialSnapshotResult.REPLACED,
                overrides.acceptInitialSnapshot(MODEL_A, Map.of("v.eye", 0.0D)));

        assertValue(overrides, MODEL_A, "v.eye", 0.0D);
        assertFalse(overrides.lookup(MODEL_A, slot("v.old_only")).present());
        assertTrue(overrides.pendingChanges().isEmpty());
    }

    @Test
    void anInitialResetNeverChangesRoamingStorageOrCreatesRoamingOverrides() {
        ModelConfigurationOverrides overrides = new ModelConfigurationOverrides();
        overrides.accept(MODEL_A, Map.of("v.eye", 7.0D));
        Fallback officialRestored = new Fallback(Map.of(
                "v.roaming.jacket", 2.0D, "v.eye", 3.0D));

        assertEquals(ModelConfigurationOverrides.InitialSnapshotResult.REPLACED,
                overrides.acceptInitialSnapshot(MODEL_A, Map.of()));
        assertFalse(overrides.lookup(MODEL_A, slot("v.roaming.jacket")).present());
        assertEquals(Map.of("v.eye", 2.0D), overrides.evaluate(MODEL_A,
                "v.eye=v.roaming.jacket;v.roaming.jacket=9;", officialRestored));
        assertEquals(ModelConfigurationOverrides.InitialSnapshotResult.PRESERVED,
                overrides.acceptInitialSnapshot(MODEL_A, Map.of()));

        assertEquals(2.0D, officialRestored.readVariable(slot("v.roaming.jacket")), 0.0001D);
        assertEquals(3.0D, officialRestored.readVariable(slot("v.eye")), 0.0001D);
        assertFalse(overrides.lookup(MODEL_A, slot("v.roaming.jacket")).present());
        assertEquals(Map.of("v.eye", 2.0D), overrides.pendingChanges());
    }

    @Test
    void anOlderAcknowledgementCannotOverwriteTheLatestCalculatedWrite() {
        ModelConfigurationOverrides overrides = new ModelConfigurationOverrides();
        Fallback fallback = new Fallback(Map.of());
        overrides.evaluate(MODEL_A, "v.count+=1;", fallback);
        UUID context = overrides.context();
        UUID scope = UUID.randomUUID();
        overrides.scope(scope);
        overrides.evaluate(MODEL_A, "v.count+=1;", fallback);

        assertTrue(overrides.acknowledge(MODEL_A, context, scope, 1, 1, Map.of("v.count", 1.0D)));
        assertValue(overrides, MODEL_A, "v.count", 2.0D);
        assertEquals(Map.of("v.count", 2.0D), overrides.pendingChanges());
        assertEquals(Map.of("v.count", 3.0D), overrides.evaluate(MODEL_A, "v.count+=1;", fallback));
        assertValue(overrides, MODEL_A, "v.count", 3.0D);
        assertEquals(3, overrides.sequence());
        assertTrue(overrides.acknowledge(MODEL_A, context, scope, 3, 3, Map.of("v.count", 3.0D)));
        assertTrue(overrides.pendingChanges().isEmpty());
    }

    @Test
    void initialScopeAndSnapshotPreserveEditsCalculatedBeforeTheGrant() {
        ModelConfigurationOverrides overrides = new ModelConfigurationOverrides();
        overrides.evaluate(MODEL_A, "variable.eye=0;v.hat=2;", new Fallback(Map.of("v.eye", 9.0D)));
        UUID context = overrides.context();
        assertNull(overrides.serverScope());
        UUID scope = UUID.randomUUID();
        overrides.scope(scope);

        assertEquals(context, overrides.context());
        assertEquals(1, overrides.sequence());
        assertTrue(overrides.acknowledge(MODEL_A, context, scope, 0, 0,
                Map.of("v.eye", 5.0D, "v.server_only", 7.0D)));
        assertValue(overrides, MODEL_A, "variable.eye", 0.0D);
        assertValue(overrides, MODEL_A, "v.hat", 2.0D);
        assertValue(overrides, MODEL_A, "v.server_only", 7.0D);
        assertEquals(Map.of("v.eye", 0.0D, "v.hat", 2.0D), overrides.pendingChanges());
    }

    @Test
    void acknowledgementsOnlyClearVariablesWhoseLatestWriteWasProcessed() {
        ModelConfigurationOverrides overrides = new ModelConfigurationOverrides();
        Fallback fallback = new Fallback(Map.of());
        overrides.evaluate(MODEL_A, "v.a=1;v.b=1;", fallback);
        overrides.evaluate(MODEL_A, "v.a=2;", fallback);
        UUID context = overrides.context();
        UUID scope = UUID.randomUUID();
        overrides.scope(scope);

        assertTrue(overrides.acknowledge(MODEL_A, context, scope, 1, 1,
                Map.of("v.a", 1.0D, "v.b", 1.0D)));
        assertEquals(Map.of("v.a", 2.0D), overrides.pendingChanges());
        assertValue(overrides, MODEL_A, "v.b", 1.0D);
        // A rejected operation acknowledges its sequence with the authoritative
        // unchanged base, so it cannot remain optimistic forever.
        assertTrue(overrides.acknowledge(MODEL_A, context, scope, 2, 2,
                Map.of("v.a", 1.0D, "v.b", 1.0D)));
        assertTrue(overrides.pendingChanges().isEmpty());
        assertValue(overrides, MODEL_A, "v.a", 1.0D);
    }

    @Test
    void duplicateOldAndFutureAcknowledgementsNeverRegressVisibleState() {
        ModelConfigurationOverrides overrides = new ModelConfigurationOverrides();
        Fallback fallback = new Fallback(Map.of());
        overrides.evaluate(MODEL_A, "v.a=1;", fallback);
        overrides.evaluate(MODEL_A, "v.a=2;", fallback);
        UUID context = overrides.context();
        UUID scope = UUID.randomUUID();
        overrides.scope(scope);
        assertTrue(overrides.acknowledge(MODEL_A, context, scope, 2, 2, Map.of("v.a", 2.0D)));

        assertFalse(overrides.acknowledge(MODEL_A, context, scope, 2, 2, Map.of("v.a", 99.0D)));
        assertFalse(overrides.acknowledge(MODEL_A, context, scope, 1, 1, Map.of("v.a", 1.0D)));
        assertFalse(overrides.acknowledge(MODEL_A, context, scope, 3, 1, Map.of("v.a", 1.0D)));
        assertFalse(overrides.acknowledge(MODEL_A, context, scope, 3, 3, Map.of("v.a", 3.0D)));
        assertValue(overrides, MODEL_A, "v.a", 2.0D);
        overrides.evaluate(MODEL_A, "v.a=3;", fallback);
        // Equal server revisions can still acknowledge a later rejected edit.
        assertTrue(overrides.acknowledge(MODEL_A, context, scope, 2, 3, Map.of("v.a", 2.0D)));
        assertTrue(overrides.pendingChanges().isEmpty());
        assertValue(overrides, MODEL_A, "v.a", 2.0D);
    }

    @Test
    void reconnectingScopesKeepPendingValuesButRejectThePreviousScopesReplies() {
        ModelConfigurationOverrides overrides = new ModelConfigurationOverrides();
        overrides.evaluate(MODEL_A, "v.a=1;", new Fallback(Map.of()));
        UUID context = overrides.context();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        overrides.scope(first);
        assertTrue(overrides.acknowledge(MODEL_A, context, first, 5, 0, Map.of()));
        overrides.scope(first);
        assertFalse(overrides.acknowledge(MODEL_A, context, first, 4, 0, Map.of()));
        overrides.scope(null);
        assertNull(overrides.serverScope());
        assertFalse(overrides.acknowledge(MODEL_A, context, first, 6, 1, Map.of("v.a", 9.0D)));
        assertFalse(overrides.acknowledge(MODEL_A, context, null, 6, 1, Map.of("v.a", 9.0D)));
        overrides.scope(second);

        assertEquals(context, overrides.context());
        assertEquals(1, overrides.sequence());
        assertEquals(Map.of("v.a", 1.0D), overrides.pendingChanges());
        assertFalse(overrides.acknowledge(MODEL_A, context, first, 7, 1, Map.of("v.a", 9.0D)));
        assertTrue(overrides.acknowledge(MODEL_A, context, second, 0, 0, Map.of()));
        assertValue(overrides, MODEL_A, "v.a", 1.0D);
    }

    @Test
    void aModelRoundTripAndAnExplicitResetAlwaysUseFreshContexts() {
        ModelConfigurationOverrides overrides = new ModelConfigurationOverrides();
        ModelConfigurationOverrides otherInstance = new ModelConfigurationOverrides();
        assertNotEquals(overrides.context(), otherInstance.context());
        Fallback fallback = new Fallback(Map.of());
        overrides.evaluate(MODEL_A, "v.a=1;", fallback);
        UUID firstContext = overrides.context();
        UUID firstScope = UUID.randomUUID();
        overrides.scope(firstScope);
        overrides.selectModel(MODEL_A);
        assertEquals(firstContext, overrides.context());
        assertEquals(1, overrides.sequence());
        overrides.selectModel(MODEL_B);
        UUID secondContext = overrides.context();
        assertNotEquals(firstContext, secondContext);
        assertNull(overrides.serverScope());
        assertTrue(overrides.pendingChanges().isEmpty());
        assertEquals(0, overrides.sequence());
        overrides.evaluate(MODEL_A, "v.a=2;", fallback);
        assertNotEquals(firstContext, overrides.context());
        assertNotEquals(secondContext, overrides.context());
        UUID currentContext = overrides.context();
        overrides.scope(firstScope);
        assertFalse(overrides.acknowledge(MODEL_A, firstContext, firstScope, 1, 1, Map.of("v.a", 9.0D)));
        assertFalse(overrides.acknowledge(MODEL_B, currentContext, firstScope, 1, 1, Map.of("v.a", 9.0D)));
        assertFalse(overrides.lookup(MODEL_B, slot("v.a")).present());
        assertEquals(currentContext, overrides.context());
        assertEquals(MODEL_A, overrides.modelId());
        assertValue(overrides, MODEL_A, "v.a", 2.0D);
        overrides.accept(MODEL_A, Map.of());
        assertNotEquals(currentContext, overrides.context());
        assertTrue(overrides.pendingChanges().isEmpty());
        assertEquals(0, overrides.sequence());
    }

    @Test
    void acknowledgementsNeverRepeatQueriesOrFunctionSideEffects() {
        ModelConfigurationOverrides overrides = new ModelConfigurationOverrides();
        Fallback fallback = new Fallback(Map.of());
        overrides.evaluate(MODEL_A, "v.a+=q.life_time;ysm.play_sound('tick');", fallback);
        assertEquals(1, fallback.queries);
        assertEquals(1, fallback.invocations);
        UUID scope = UUID.randomUUID();
        overrides.scope(scope);
        assertTrue(overrides.acknowledge(MODEL_A, overrides.context(), scope, 0, 0, Map.of()));
        assertTrue(overrides.acknowledge(MODEL_A, overrides.context(), scope, 1, 1, Map.of("v.a", 1.0D)));
        assertEquals(1, fallback.queries);
        assertEquals(1, fallback.invocations);
        assertValue(overrides, MODEL_A, "v.a", 1.0D);
    }

    @Test
    void pendingWritesAreCoalescedAndAnOverflowCannotPartiallyCommit() {
        ModelConfigurationOverrides overrides = new ModelConfigurationOverrides();
        Fallback fallback = new Fallback(Map.of());
        StringBuilder source = new StringBuilder();
        for (int index = 0; index < 256; index++) {
            source.append("v.value").append(index).append("=0;");
        }
        overrides.evaluate(MODEL_A, source.toString(), fallback);
        for (int index = 0; index < 300; index++) {
            overrides.evaluate(MODEL_A, "v.value0+=1;", fallback);
        }
        assertEquals(256, overrides.pendingChanges().size());
        long sequence = overrides.sequence();
        UUID context = overrides.context();
        Map<String, Double> pending = overrides.pendingChanges();
        assertThrows(IllegalArgumentException.class, () -> overrides.evaluate(MODEL_A,
                "v.value0=999;v.extra=1;", fallback));
        assertEquals(sequence, overrides.sequence());
        assertEquals(context, overrides.context());
        assertEquals(pending, overrides.pendingChanges());
        assertValue(overrides, MODEL_A, "v.value0", 300.0D);
        assertFalse(overrides.lookup(MODEL_A, slot("v.extra")).present());
        assertThrows(UnsupportedOperationException.class, () -> pending.put("v.extra", 1.0D));
    }

    @Test
    void anOversizedConfirmedPendingCompositionLeavesAcknowledgementStateUntouched() {
        ModelConfigurationOverrides overrides = new ModelConfigurationOverrides();
        overrides.evaluate(MODEL_A, "v.pending=2;", new Fallback(Map.of()));
        UUID scope = UUID.randomUUID();
        overrides.scope(scope);
        UUID context = overrides.context();
        assertThrows(IllegalArgumentException.class, () -> overrides.acknowledge(
                MODEL_A, context, scope, 1, 0, values(256)));
        assertValue(overrides, MODEL_A, "v.pending", 2.0D);
        assertFalse(overrides.lookup(MODEL_A, slot("v.value0")).present());
        assertEquals(Map.of("v.pending", 2.0D), overrides.pendingChanges());
        // The failed ACK did not consume its revision/sequence metadata.
        assertTrue(overrides.acknowledge(MODEL_A, context, scope, 1, 0, values(255)));
        assertTrue(overrides.acknowledge(MODEL_A, context, scope, 2, 1, values(256)));
        assertTrue(overrides.pendingChanges().isEmpty());
        assertFalse(overrides.lookup(MODEL_A, slot("v.pending")).present());
        assertValue(overrides, MODEL_A, "v.value255", 255.0D);
    }

    @Test
    void invalidReplacementOrNewModelEvaluationCannotEraseTheCurrentModel() {
        ModelConfigurationOverrides overrides = new ModelConfigurationOverrides();
        overrides.evaluate(MODEL_A, "v.a=1;", new Fallback(Map.of()));
        UUID context = overrides.context();
        assertThrows(IllegalArgumentException.class, () -> overrides.accept(MODEL_B, values(257)));
        StringBuilder source = new StringBuilder();
        for (int index = 0; index < 257; index++) {
            source.append("v.value").append(index).append("=1;");
        }
        assertThrows(IllegalArgumentException.class, () -> overrides.evaluate(
                MODEL_B, source.toString(), new Fallback(Map.of())));
        assertEquals(MODEL_A, overrides.modelId());
        assertEquals(context, overrides.context());
        assertEquals(1, overrides.sequence());
        assertValue(overrides, MODEL_A, "v.a", 1.0D);
        assertEquals(Map.of("v.a", 1.0D), overrides.pendingChanges());
    }

    private static Map<String, Double> values(int count) {
        Map<String, Double> result = new LinkedHashMap<>();
        for (int index = 0; index < count; index++) {
            result.put("v.value" + index, (double) index);
        }
        return result;
    }

    @Test
    void lookingUpTheOldRenderSelectionDoesNotDiscardANewerSnapshot() {
        ModelConfigurationOverrides overrides = new ModelConfigurationOverrides();
        overrides.accept(MODEL_A, Map.of("v.eye", 1.0D));
        overrides.accept(MODEL_B, Map.of("v.eye", 0.0D, "v.hat", 2.0D));

        assertFalse(overrides.lookup(MODEL_A, slot("v.eye")).present());
        assertFalse(overrides.lookup(MODEL_A, slot("v.hat")).present());
        assertFalse(overrides.lookup(null, slot("v.eye")).present());

        assertValue(overrides, MODEL_B, "v.eye", 0.0D);
        assertValue(overrides, MODEL_B, "v.hat", 2.0D);
    }

    @Test
    void evaluatingANewModelStartsWithItsFallbackInsteadOfTheOldModelsValues() {
        ModelConfigurationOverrides overrides = new ModelConfigurationOverrides();
        overrides.accept(MODEL_A, Map.of("v.eye", 40.0D, "v.old_only", 9.0D));
        Fallback fallback = new Fallback(Map.of("v.eye", 2.0D));

        Map<String, Double> changes = overrides.evaluate(MODEL_B,
                "v.eye+=1;v.new_only=v.old_only+4;", fallback);

        assertEquals(Map.of("v.eye", 3.0D, "v.new_only", 4.0D), changes);
        assertValue(overrides, MODEL_B, "v.eye", 3.0D);
        assertFalse(overrides.lookup(MODEL_B, slot("v.old_only")).present());
        assertFalse(overrides.lookup(MODEL_A, slot("v.eye")).present());
        assertEquals(2.0D, fallback.readVariable(slot("v.eye")), 0.0001D);
        assertFalse(fallback.hasVariable(slot("v.new_only")));
    }

    @Test
    void sameModelExpressionsMergeWithTheReceivedSnapshotIncludingExplicitZero() {
        ModelConfigurationOverrides overrides = new ModelConfigurationOverrides();
        overrides.accept(MODEL_B, Map.of("v.eye", 1.0D, "v.hat", 3.0D));
        Fallback fallback = new Fallback(Map.of("v.eye", 20.0D));

        assertEquals(Map.of("v.eye", 0.0D), overrides.evaluate(
                MODEL_B, "variable.eye=1-variable.eye;", fallback));
        assertValue(overrides, MODEL_B, "v.eye", 0.0D);
        assertValue(overrides, MODEL_B, "v.hat", 3.0D);
        assertEquals(Map.of("v.eye", 2.0D), overrides.evaluate(
                MODEL_B, "v.eye+=2;", fallback));
        assertValue(overrides, MODEL_B, "variable.eye", 2.0D);
        assertValue(overrides, MODEL_B, "v.hat", 3.0D);
    }

    @Test
    void acceptingTheSameModelReplacesRatherThanMergesItsSnapshot() {
        ModelConfigurationOverrides overrides = new ModelConfigurationOverrides();
        overrides.accept(MODEL_B, Map.of("v.eye", 1.0D, "v.old_only", 4.0D));

        overrides.accept(MODEL_B, Map.of("v.eye", 0.0D));

        assertValue(overrides, MODEL_B, "v.eye", 0.0D);
        assertFalse(overrides.lookup(MODEL_B, slot("v.old_only")).present());
    }

    @Test
    void anEmptySnapshotResetsTheModelAndTheNextEditReadsItsFallback() {
        ModelConfigurationOverrides overrides = new ModelConfigurationOverrides();
        overrides.accept(MODEL_B, Map.of("v.eye", 7.0D));

        overrides.accept(MODEL_B, Map.of());

        assertFalse(overrides.lookup(MODEL_B, slot("v.eye")).present());
        assertEquals(Map.of("v.eye", 3.0D), overrides.evaluate(MODEL_B,
                "v.eye+=1;", new Fallback(Map.of("v.eye", 2.0D))));
        assertValue(overrides, MODEL_B, "v.eye", 3.0D);
    }

    @Test
    void roamingAndTemporaryVariablesNeverBecomeModelConfigurationOverrides() {
        ModelConfigurationOverrides overrides = new ModelConfigurationOverrides();
        Fallback fallback = new Fallback(Map.of("v.roaming.hat", 6.0D));

        Map<String, Double> changes = overrides.evaluate(MODEL_B,
                "v.eye=1;v.roaming.hat=2;variable.roaming.jacket=3;t.scratch=4;",
                fallback);

        assertEquals(Map.of("v.eye", 1.0D), changes);
        assertValue(overrides, MODEL_B, "v.eye", 1.0D);
        assertFalse(overrides.lookup(MODEL_B, slot("v.roaming.hat")).present());
        assertFalse(overrides.lookup(MODEL_B, slot("variable.roaming.jacket")).present());
        assertFalse(overrides.lookup(MODEL_B, slot("t.scratch")).present());
        assertEquals(6.0D, fallback.readVariable(slot("v.roaming.hat")), 0.0001D);
    }

    private static int slot(String name) {
        return ExpressionEngine.slot(name);
    }

    private static void assertValue(ModelConfigurationOverrides overrides,
                                    String modelId, String name, double expected) {
        ConfigurationVariableOverrides.Lookup actual = overrides.lookup(modelId, slot(name));
        assertTrue(actual.present(), name + " must be assigned, including an explicit zero");
        assertEquals(expected, actual.value(), 0.0001D);
    }

    private static final class Fallback implements ExpressionEngine.Environment {
        private final Map<Integer, Double> values = new HashMap<>();
        private int queries;
        private int invocations;

        private Fallback(Map<String, Double> initial) {
            initial.forEach((name, value) -> values.put(slot(name), value));
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
            return ++queries;
        }

        @Override
        public double invoke(String name, double[] arguments) {
            invocations++;
            return 0.0D;
        }

        @Override
        public double invokeWithText(String name, String[] arguments) {
            invocations++;
            return 0.0D;
        }
    }
}
