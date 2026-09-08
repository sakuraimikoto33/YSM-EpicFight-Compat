package net.okitsu.ysmepicfightcompat.network;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigurationPlayerTransitionsTest {
    private static final UUID ID = new UUID(1, 1);
    private static final UUID OTHER_ID = new UUID(2, 2);

    @Test
    void withoutATransitionOnlyTheExactOnlinePlayerIsAllowed() {
        ConfigurationPlayerTransitions<Player> transitions = new ConfigurationPlayerTransitions<>();
        Player online = new Player("same");
        Player equalButDifferent = new Player("same");
        assertEquals(online, equalButDifferent);

        assertTrue(transitions.allows(ID, online, online));
        assertFalse(transitions.allows(ID, equalButDifferent, online));
        assertFalse(transitions.allows(ID, online, null));
        assertFalse(transitions.allows(ID, null, null));
        assertFalse(transitions.allows(null, online, online));
        assertTrue(transitions.ready().isEmpty());
    }

    @Test
    void beginRequiresDistinctNonNullInstancesAndTheExactOnlineOriginal() {
        ConfigurationPlayerTransitions<Player> transitions = new ConfigurationPlayerTransitions<>();
        Player original = new Player("same");
        Player replacement = new Player("same");

        assertFalse(transitions.begin(null, original, replacement, original, true));
        assertFalse(transitions.begin(ID, null, replacement, null, true));
        assertFalse(transitions.begin(ID, original, null, original, true));
        assertFalse(transitions.begin(ID, original, original, original, true));
        assertFalse(transitions.begin(ID, original, replacement, replacement, true));
        assertFalse(transitions.begin(ID, original, replacement, null, true));
        assertTrue(transitions.allows(ID, original, original));
        assertTrue(transitions.ready().isEmpty());
        assertTrue(transitions.begin(ID, original, replacement, original, true));
    }

    @Test
    void replacementOwnsTrackingBeforeTheOnlineUuidMapIsUpdated() {
        ConfigurationPlayerTransitions<Player> transitions = new ConfigurationPlayerTransitions<>();
        Player original = new Player("same");
        Player replacement = new Player("same");
        assertTrue(transitions.begin(ID, original, replacement, original, true));

        assertTrue(transitions.allows(ID, replacement, original));
        assertTrue(transitions.allows(ID, replacement, null));
        assertTrue(transitions.allows(ID, replacement, replacement));
        assertFalse(transitions.allows(ID, original, original));
        assertFalse(transitions.allows(ID, new Player("same"), replacement));
        assertFalse(transitions.allows(ID, null, replacement));
        assertTrue(transitions.ready().isEmpty(), "Tracking alone must not mark a respawn ready");
        assertTrue(transitions.allows(OTHER_ID, original, original));
    }

    @Test
    void onlyAMatchingDeathRespawnEventMakesTheReplacementReady() {
        ConfigurationPlayerTransitions<Player> transitions = new ConfigurationPlayerTransitions<>();
        Player original = new Player("same");
        Player replacement = new Player("same");
        assertTrue(transitions.begin(ID, original, replacement, original, true));

        transitions.respawned(ID, replacement, true);
        transitions.respawned(ID, original, false);
        transitions.respawned(ID, new Player("same"), false);
        transitions.respawned(OTHER_ID, replacement, false);
        transitions.respawned(null, replacement, false);
        transitions.respawned(ID, null, false);
        assertTrue(transitions.ready().isEmpty());
        transitions.respawned(ID, replacement, false);
        var ready = transitions.ready();
        assertEquals(1, ready.size());
        assertEquals(ID, ready.get(0).playerId());
        assertSame(replacement, ready.get(0).replacement());
        assertTrue(ready.get(0).wasDeath());
        assertTrue(ready.get(0).deathNotificationRequired());
        assertTrue(ready.get(0).ready());
        transitions.respawned(ID, replacement, false);
        transitions.respawned(ID, replacement, true);
        assertEquals(ready, transitions.ready());
    }

    @Test
    void endReturnRequiresANonDeathCloneAndDoesNotBecomeADeathTransition() {
        ConfigurationPlayerTransitions<Player> transitions = new ConfigurationPlayerTransitions<>();
        Player original = new Player("original");
        Player replacement = new Player("replacement");
        assertTrue(transitions.begin(ID, original, replacement, original, false));
        transitions.respawned(ID, replacement, false);
        assertTrue(transitions.ready().isEmpty());

        transitions.respawned(ID, replacement, true);

        assertEquals(List.of(new ConfigurationPlayerTransitions.Pending<>(
                        ID, replacement, false, false, true)),
                transitions.ready());
    }

    @Test
    void pendingDeathNotificationSurvivesAnEndReturnAndItsDistinctReadyEvent() {
        ConfigurationPlayerTransitions<Player> transitions = new ConfigurationPlayerTransitions<>();
        Player original = new Player("original");
        Player afterDeath = new Player("after death");
        Player afterEnd = new Player("after End");
        assertTrue(transitions.begin(ID, original, afterDeath, original, true));
        transitions.respawned(ID, afterDeath, false);
        var deathSnapshot = transitions.ready();
        assertTrue(deathSnapshot.get(0).deathNotificationRequired());
        assertTrue(transitions.begin(ID, afterDeath, afterEnd, afterDeath, false));

        transitions.respawned(ID, afterDeath, false);
        transitions.remove(ID, afterDeath);
        transitions.respawned(ID, afterEnd, false);
        assertTrue(transitions.ready().isEmpty(),
                "Carrying a death notification must not accept a death event for the End return");
        assertTrue(transitions.allows(ID, afterEnd, afterDeath));
        transitions.respawned(ID, afterEnd, true);

        assertEquals(List.of(new ConfigurationPlayerTransitions.Pending<>(
                        ID, afterEnd, false, true, true)),
                transitions.ready());
        transitions.remove(ID, afterDeath);
        transitions.respawned(ID, afterDeath, false);
        assertSame(afterEnd, transitions.ready().get(0).replacement());
        assertTrue(transitions.ready().get(0).deathNotificationRequired());
        assertSame(afterDeath, deathSnapshot.get(0).replacement());
    }

    @Test
    void anUnreadyDeathNotificationIsAlsoCarriedIntoAnEndReturn() {
        ConfigurationPlayerTransitions<Player> transitions = new ConfigurationPlayerTransitions<>();
        Player original = new Player("original");
        Player afterDeath = new Player("after death");
        Player afterEnd = new Player("after End");
        assertTrue(transitions.begin(ID, original, afterDeath, original, true));
        assertTrue(transitions.begin(ID, afterDeath, afterEnd, afterDeath, false));

        transitions.respawned(ID, afterEnd, true);

        assertFalse(transitions.ready().get(0).wasDeath());
        assertTrue(transitions.ready().get(0).deathNotificationRequired());
    }

    @Test
    void anEndReturnFollowedByDeathRequiresADeathNotification() {
        ConfigurationPlayerTransitions<Player> transitions = new ConfigurationPlayerTransitions<>();
        Player original = new Player("original");
        Player afterEnd = new Player("after End");
        Player afterDeath = new Player("after death");
        assertTrue(transitions.begin(ID, original, afterEnd, original, false));
        transitions.respawned(ID, afterEnd, true);
        assertFalse(transitions.ready().get(0).deathNotificationRequired());
        assertTrue(transitions.begin(ID, afterEnd, afterDeath, afterEnd, true));

        transitions.respawned(ID, afterEnd, true);
        transitions.remove(ID, afterEnd);
        transitions.respawned(ID, afterDeath, true);
        assertTrue(transitions.ready().isEmpty());
        transitions.respawned(ID, afterDeath, false);

        assertEquals(List.of(new ConfigurationPlayerTransitions.Pending<>(
                        ID, afterDeath, true, true, true)),
                transitions.ready());
    }

    @Test
    void removingAPublishedDeathNotificationLeavesLaterEndReturnsUnmarked() {
        ConfigurationPlayerTransitions<Player> transitions = new ConfigurationPlayerTransitions<>();
        Player original = new Player("original");
        Player afterDeath = new Player("after death");
        Player afterEnd = new Player("after End");
        assertTrue(transitions.begin(ID, original, afterDeath, original, true));
        transitions.respawned(ID, afterDeath, false);
        assertTrue(transitions.ready().get(0).deathNotificationRequired());
        transitions.remove(ID, afterDeath);
        assertTrue(transitions.ready().isEmpty());

        assertTrue(transitions.begin(ID, afterDeath, afterEnd, afterDeath, false));
        transitions.respawned(ID, afterEnd, true);

        assertEquals(List.of(new ConfigurationPlayerTransitions.Pending<>(
                        ID, afterEnd, false, false, true)),
                transitions.ready());
    }

    @Test
    void duplicateAndLateCloneEventsCannotReplaceOrUnreadyTheCurrentTransition() {
        ConfigurationPlayerTransitions<Player> transitions = new ConfigurationPlayerTransitions<>();
        Player original = new Player("original");
        Player replacement = new Player("replacement");
        Player unrelated = new Player("unrelated");
        assertTrue(transitions.begin(ID, original, replacement, original, true));
        transitions.respawned(ID, replacement, false);
        var ready = transitions.ready();

        assertFalse(transitions.begin(ID, original, replacement, original, true));
        assertFalse(transitions.begin(ID, original, unrelated, original, false));
        assertFalse(transitions.begin(ID, unrelated, new Player("next"), unrelated, true));
        assertEquals(ready, transitions.ready());
        assertTrue(transitions.allows(ID, replacement, unrelated));
    }

    @Test
    void aSecondDeathSupersedesThePreviousReplacementWithoutAcceptingItsLateEvents() {
        ConfigurationPlayerTransitions<Player> transitions = new ConfigurationPlayerTransitions<>();
        Player original = new Player("original");
        Player first = new Player("first");
        Player second = new Player("second");
        assertTrue(transitions.begin(ID, original, first, original, true));
        transitions.respawned(ID, first, false);
        var firstSnapshot = transitions.ready();
        assertTrue(transitions.begin(ID, first, second, first, true));

        assertTrue(transitions.ready().isEmpty());
        assertTrue(transitions.allows(ID, second, first));
        assertFalse(transitions.allows(ID, first, first));
        transitions.remove(ID, first);
        transitions.respawned(ID, first, false);
        assertFalse(transitions.begin(ID, original, first, original, true));
        assertTrue(transitions.ready().isEmpty());
        transitions.respawned(ID, second, false);
        assertSame(second, transitions.ready().get(0).replacement());
        assertSame(first, firstSnapshot.get(0).replacement());
    }

    @Test
    void anUnreadyReplacementCanBeSupersededOnlyAfterBecomingTheOnlineOriginal() {
        ConfigurationPlayerTransitions<Player> transitions = new ConfigurationPlayerTransitions<>();
        Player original = new Player("original");
        Player first = new Player("first");
        Player second = new Player("second");
        assertTrue(transitions.begin(ID, original, first, original, true));
        assertFalse(transitions.begin(ID, first, second, original, true));
        assertTrue(transitions.begin(ID, first, second, first, true));
        assertTrue(transitions.allows(ID, second, first));
        assertTrue(transitions.ready().isEmpty());
    }

    @Test
    void readySnapshotsAreImmutableDetachedAndExcludeUnreadyPlayers() {
        ConfigurationPlayerTransitions<Player> transitions = new ConfigurationPlayerTransitions<>();
        Player original = new Player("original");
        Player replacement = new Player("replacement");
        Player otherOriginal = new Player("other original");
        Player otherReplacement = new Player("other replacement");
        transitions.begin(ID, original, replacement, original, true);
        transitions.begin(OTHER_ID, otherOriginal, otherReplacement, otherOriginal, false);
        transitions.respawned(ID, replacement, false);
        var snapshot = transitions.ready();
        assertEquals(1, snapshot.size());
        assertThrows(UnsupportedOperationException.class, snapshot::clear);
        assertThrows(UnsupportedOperationException.class, () -> snapshot.add(snapshot.get(0)));

        transitions.respawned(OTHER_ID, otherReplacement, true);
        assertEquals(1, snapshot.size());
        assertEquals(2, transitions.ready().size());
        transitions.clear();
        assertTrue(transitions.ready().isEmpty());
        assertEquals(1, snapshot.size());
        assertTrue(transitions.allows(ID, original, original));
    }

    @Test
    void cleanupMatchesTheReplacementByIdentityAndMissingEventsCreateNoEntries() {
        ConfigurationPlayerTransitions<Player> transitions = new ConfigurationPlayerTransitions<>();
        Player original = new Player("same");
        Player replacement = new Player("same");
        transitions.respawned(ID, replacement, false);
        transitions.remove(ID, replacement);
        assertTrue(transitions.ready().isEmpty());
        assertTrue(transitions.allows(ID, original, original));
        transitions.begin(ID, original, replacement, original, true);
        transitions.respawned(ID, replacement, false);

        transitions.remove(ID, original);
        transitions.remove(ID, new Player("same"));
        transitions.remove(ID, null);
        transitions.remove(null, replacement);
        transitions.remove(OTHER_ID, replacement);
        assertEquals(1, transitions.ready().size());
        transitions.remove(ID, replacement);
        assertTrue(transitions.ready().isEmpty());
        assertTrue(transitions.allows(ID, original, original));
        assertFalse(transitions.allows(ID, replacement, original));
    }

    private record Player(String value) {
    }
}
