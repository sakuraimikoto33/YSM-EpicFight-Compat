package net.okitsu.ysmepicfightcompat.network;

import net.okitsu.ysmepicfightcompat.animation.MovementAnimationType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MovementAnimationDisplayStateTest {
    @AfterEach
    void clearRemoteState() {
        RemoteMovementAnimationPreferences.beginConnection();
    }

    @Test
    void matchesOnlyTheResolvedModelAndMovement() {
        MovementAnimationDisplayState state = new MovementAnimationDisplayState(
                " WINE_FOX/21_SAINT ", MovementAnimationType.RUN, true);

        assertEquals("wine_fox/21_saint", state.modelId());
        assertTrue(state.usesYsm("wine_fox/21_saint", MovementAnimationType.RUN));
        assertFalse(state.usesYsm("wine_fox/05_magical", MovementAnimationType.RUN));
        assertFalse(state.usesYsm("wine_fox/21_saint", MovementAnimationType.WALK));
        assertEquals(MovementAnimationType.RUN,
                state.semanticMovementFor("wine_fox/21_saint"));
        assertEquals(null, state.semanticMovementFor("wine_fox/05_magical"));
    }

    @Test
    void semanticMovementRemainsAvailableWithoutYsmPoseOwnership() {
        MovementAnimationDisplayState state = new MovementAnimationDisplayState(
                "wine_fox/05_magical", MovementAnimationType.CREATIVE_FLIGHT,
                false);

        assertFalse(state.usesYsm(
                "wine_fox/05_magical", MovementAnimationType.CREATIVE_FLIGHT));
        assertEquals(MovementAnimationType.CREATIVE_FLIGHT,
                state.semanticMovementFor("wine_fox/05_magical"));
        assertEquals(null,
                state.semanticMovementFor("wine_fox/21_saint"));
    }

    @Test
    void emptyModelOrMovementCannotClaimYsmOwnership() {
        MovementAnimationDisplayState noModel = new MovementAnimationDisplayState(
                "", MovementAnimationType.RUN, true);
        MovementAnimationDisplayState noMovement = new MovementAnimationDisplayState(
                "wine_fox/21_saint", null, true);

        assertFalse(noModel.ysmOwned());
        assertFalse(noMovement.ysmOwned());
        assertFalse(noModel.usesYsm("", MovementAnimationType.RUN));
        assertFalse(noMovement.usesYsm("wine_fox/21_saint", null));
    }

    @Test
    void rejectsUnsafeNetworkModelIds() {
        assertThrows(IllegalArgumentException.class, () ->
                new MovementAnimationDisplayState(
                        "bad\nmodel", MovementAnimationType.RUN, true));
        assertThrows(IllegalArgumentException.class, () ->
                new MovementAnimationDisplayState(
                        "x".repeat(MovementAnimationPolicy.MAX_MODEL_ID_LENGTH + 1),
                        MovementAnimationType.RUN, true));
    }

    @Test
    void remoteSnapshotsAreClearedAtConnectionBoundaries() {
        UUID playerId = UUID.randomUUID();
        MovementAnimationDisplayState state = new MovementAnimationDisplayState(
                "wine_fox/21_saint", MovementAnimationType.RUN, true);

        RemoteMovementAnimationPreferences.accept(playerId, state);
        assertEquals(state, RemoteMovementAnimationPreferences.find(playerId));

        RemoteMovementAnimationPreferences.beginConnection();
        assertEquals(MovementAnimationDisplayState.DEFAULT,
                RemoteMovementAnimationPreferences.find(playerId));
    }
}
