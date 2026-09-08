package net.okitsu.ysmepicfightcompat.network;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigurationSyncSessionTest {
    private static final String MODEL_A = "test:model_a";
    private static final String MODEL_B = "test:model_b";
    private static final UUID CONTEXT_A = new UUID(1L, 1L);
    private static final UUID CONTEXT_B = new UUID(2L, 2L);

    @Test
    void newSessionsHaveNoGrantedClientAndUseOwnerIdentity() {
        Owner owner = new Owner("same-player");
        ConfigurationSyncSession session = new ConfigurationSyncSession(owner, MODEL_A);

        assertTrue(session.isOwner(owner));
        assertTrue(session.matches(owner, MODEL_A));
        assertFalse(session.isOwner(new Owner("same-player")));
        assertFalse(session.isOwner(null));
        assertFalse(session.matches(owner, MODEL_B));
        assertFalse(session.matches(new Owner("same-player"), MODEL_A));
        assertEquals(MODEL_A, session.modelId());
        assertNotEquals(ConfigurationSyncSession.NO_CONTEXT, session.scope());
        assertEquals(ConfigurationSyncSession.NO_CONTEXT, session.context());
        assertEquals(0L, session.processedSequence());
        assertEquals(0L, session.revision());
        assertFalse(session.accepts(session.scope(), ConfigurationSyncSession.NO_CONTEXT, 1L));
        assertThrows(NullPointerException.class, () -> new ConfigurationSyncSession(null, MODEL_A));
    }

    @Test
    void absentSelectionsHaveOneCanonicalModelId() {
        Object owner = new Object();
        ConfigurationSyncSession session = new ConfigurationSyncSession(owner, null);
        UUID scope = session.scope();

        assertEquals("", session.modelId());
        assertTrue(session.matches(owner, ""));
        assertTrue(session.matches(owner, null));
        session.changeModel("");
        assertEquals(scope, session.scope());
    }

    @Test
    void grantRotatesTheScopeAndReplayingTheSameRequestIsIdempotent() {
        ConfigurationSyncSession session = new ConfigurationSyncSession(new Object(), MODEL_A);
        UUID initialScope = session.scope();

        assertTrue(session.grant(CONTEXT_A, 1L));
        UUID grantedScope = session.scope();
        assertNotEquals(initialScope, grantedScope);
        assertEquals(CONTEXT_A, session.context());
        assertTrue(session.accepts(grantedScope, CONTEXT_A, 1L));
        session.processed(1L);

        assertTrue(session.grant(CONTEXT_A, 1L));
        assertEquals(grantedScope, session.scope());
        assertEquals(1L, session.processedSequence());
        assertEquals(1L, session.revision());
    }

    @Test
    void staleRequestsAndEqualOrderDifferentContextsCannotReplaceTheGrant() {
        ConfigurationSyncSession session = granted(CONTEXT_B, 8L);
        UUID scope = session.scope();
        session.processed(3L);

        assertFalse(session.grant(CONTEXT_A, 7L));
        assertFalse(session.grant(CONTEXT_B, 7L));
        assertFalse(session.grant(CONTEXT_A, 8L));

        assertEquals(scope, session.scope());
        assertEquals(CONTEXT_B, session.context());
        assertEquals(3L, session.processedSequence());
        assertEquals(1L, session.revision());
    }

    @Test
    void invalidGrantRequestsDoNotConsumeAnOrderOrMutateTheSession() {
        ConfigurationSyncSession session = new ConfigurationSyncSession(new Object(), MODEL_A);
        UUID scope = session.scope();

        assertFalse(session.grant(CONTEXT_A, 0L));
        assertFalse(session.grant(CONTEXT_A, -1L));
        assertFalse(session.grant(null, 100L));
        assertFalse(session.grant(ConfigurationSyncSession.NO_CONTEXT, 100L));
        assertEquals(scope, session.scope());
        assertEquals(ConfigurationSyncSession.NO_CONTEXT, session.context());
        assertTrue(session.grant(CONTEXT_A, 1L));
    }

    @Test
    void aNewClientContextOnTheSameModelDropsOnlyAcknowledgementHistory() {
        ConfigurationSyncSession session = granted(CONTEXT_A, 1L);
        UUID oldScope = session.scope();
        session.processed(5L);

        assertTrue(session.grant(CONTEXT_B, 2L));

        assertEquals(MODEL_A, session.modelId());
        assertNotEquals(oldScope, session.scope());
        assertEquals(CONTEXT_B, session.context());
        assertEquals(0L, session.processedSequence());
        assertEquals(0L, session.revision());
        assertFalse(session.accepts(oldScope, CONTEXT_A, 6L));
        assertTrue(session.accepts(session.scope(), CONTEXT_B, 1L));
        assertFalse(session.grant(CONTEXT_A, 1L));
    }

    @Test
    void aHigherRequestOrderAlsoRefreshesAnUnchangedContext() {
        ConfigurationSyncSession session = granted(CONTEXT_A, 1L);
        UUID oldScope = session.scope();
        session.processed(2L);

        assertTrue(session.grant(CONTEXT_A, 2L));

        assertNotEquals(oldScope, session.scope());
        assertEquals(0L, session.processedSequence());
        assertEquals(0L, session.revision());
        assertFalse(session.accepts(oldScope, CONTEXT_A, 3L));
    }

    @Test
    void pollingTheUnchangedModelKeepsItsGrantAndAcknowledgements() {
        ConfigurationSyncSession session = granted(CONTEXT_A, 1L);
        UUID scope = session.scope();
        session.processed(2L);

        session.changeModel(MODEL_A);

        assertEquals(scope, session.scope());
        assertEquals(CONTEXT_A, session.context());
        assertEquals(2L, session.processedSequence());
        assertEquals(1L, session.revision());
    }

    @Test
    void modelChangesInvalidateTheGrantButKeepTheRequestHighWaterMark() {
        ConfigurationSyncSession session = granted(CONTEXT_A, 5L);
        UUID oldScope = session.scope();
        session.processed(7L);

        session.changeModel(MODEL_B);

        assertEquals(MODEL_B, session.modelId());
        assertNotEquals(oldScope, session.scope());
        assertEquals(ConfigurationSyncSession.NO_CONTEXT, session.context());
        assertEquals(0L, session.processedSequence());
        assertEquals(0L, session.revision());
        assertFalse(session.accepts(oldScope, CONTEXT_A, 8L));
        assertFalse(session.grant(CONTEXT_A, 4L));
        assertFalse(session.grant(CONTEXT_A, 5L));
        assertFalse(session.grant(CONTEXT_B, 5L));
        assertTrue(session.grant(CONTEXT_B, 6L));
    }

    @Test
    void switchingAwayAndBackNeverRevivesAnOlderModelScope() {
        ConfigurationSyncSession session = granted(CONTEXT_A, 1L);
        UUID firstScopeA = session.scope();

        session.changeModel(MODEL_B);
        assertTrue(session.grant(CONTEXT_B, 2L));
        UUID scopeB = session.scope();
        session.changeModel(MODEL_A);
        assertFalse(session.grant(CONTEXT_A, 1L));
        assertFalse(session.grant(CONTEXT_B, 2L));
        assertTrue(session.grant(CONTEXT_A, 3L));

        assertNotEquals(firstScopeA, session.scope());
        assertNotEquals(scopeB, session.scope());
        assertFalse(session.accepts(firstScopeA, CONTEXT_A, 1L));
        assertFalse(session.accepts(scopeB, CONTEXT_B, 1L));
        assertTrue(session.accepts(session.scope(), CONTEXT_A, 1L));
    }

    @Test
    void explicitInvalidationPreservesModelAndOrderWithoutAClientGrant() {
        ConfigurationSyncSession session = granted(CONTEXT_A, 3L);
        UUID oldScope = session.scope();
        session.processed(4L);

        session.invalidate();

        assertEquals(MODEL_A, session.modelId());
        assertNotEquals(oldScope, session.scope());
        assertEquals(ConfigurationSyncSession.NO_CONTEXT, session.context());
        assertEquals(0L, session.processedSequence());
        assertEquals(0L, session.revision());
        assertFalse(session.accepts(oldScope, CONTEXT_A, 5L));
        assertFalse(session.grant(CONTEXT_A, 2L));
        assertFalse(session.grant(CONTEXT_A, 3L));
        assertTrue(session.grant(CONTEXT_B, 4L));
    }

    @Test
    void replacementPlayerInstancesHaveIndependentScopesForTheSameIdentityAndModel() {
        Owner original = new Owner("same-player");
        Owner replacement = new Owner("same-player");
        ConfigurationSyncSession oldSession = new ConfigurationSyncSession(original, MODEL_A);
        assertTrue(oldSession.grant(CONTEXT_A, 8L));
        oldSession.processed(9L);
        ConfigurationSyncSession current = new ConfigurationSyncSession(replacement, MODEL_A);

        assertFalse(oldSession.matches(replacement, MODEL_A));
        assertTrue(current.matches(replacement, MODEL_A));
        assertFalse(current.matches(original, MODEL_A));
        assertNotEquals(oldSession.scope(), current.scope());
        assertTrue(current.grant(CONTEXT_B, 1L));
        assertFalse(current.accepts(oldSession.scope(), CONTEXT_A, 10L));
        assertEquals(0L, current.processedSequence());
        assertEquals(0L, current.revision());
        assertEquals(9L, oldSession.processedSequence());
    }

    @Test
    void onlyPositiveSequencesFromTheGrantedScopeAndContextAreAccepted() {
        ConfigurationSyncSession session = granted(CONTEXT_A, 1L);

        assertTrue(session.accepts(session.scope(), CONTEXT_A, 1L));
        assertTrue(session.accepts(session.scope(), CONTEXT_A, Long.MAX_VALUE));
        assertFalse(session.accepts(CONTEXT_B, CONTEXT_A, 1L));
        assertFalse(session.accepts(session.scope(), CONTEXT_B, 1L));
        assertFalse(session.accepts(null, CONTEXT_A, 1L));
        assertFalse(session.accepts(session.scope(), null, 1L));
        assertFalse(session.accepts(session.scope(), ConfigurationSyncSession.NO_CONTEXT, 1L));
        assertFalse(session.accepts(session.scope(), CONTEXT_A, 0L));
        assertFalse(session.accepts(session.scope(), CONTEXT_A, -1L));
        assertFalse(session.accepts(session.scope(), CONTEXT_A, Long.MIN_VALUE));
    }

    @Test
    void processingAcceptedOrRejectedEditsAdvancesCoverageAndDuplicatesAreIdempotent() {
        ConfigurationSyncSession session = granted(CONTEXT_A, 1L);
        assertFalse(session.duplicate(1L));

        session.processed(1L);
        assertTrue(session.duplicate(1L));
        assertEquals(1L, session.processedSequence());
        assertEquals(1L, session.revision());

        // Validation may reject the values, but its request still receives an acknowledgement.
        session.processed(4L);
        assertTrue(session.duplicate(3L));
        assertTrue(session.duplicate(4L));
        assertFalse(session.duplicate(5L));
        assertEquals(4L, session.processedSequence());
        assertEquals(2L, session.revision());

        session.processed(4L);
        session.processed(2L);
        session.processed(0L);
        session.processed(-1L);
        assertEquals(4L, session.processedSequence());
        assertEquals(2L, session.revision());
        assertTrue(session.accepts(session.scope(), CONTEXT_A, 4L));
    }

    private static ConfigurationSyncSession granted(UUID context, long requestOrder) {
        ConfigurationSyncSession session = new ConfigurationSyncSession(new Object(), MODEL_A);
        assertTrue(session.grant(context, requestOrder));
        return session;
    }

    private record Owner(String id) {
    }
}
