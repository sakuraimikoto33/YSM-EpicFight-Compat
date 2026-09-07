package net.okitsu.ysmepicfightcompat.network;

import net.okitsu.ysmepicfightcompat.animation.ModAnimationType;
import net.okitsu.ysmepicfightcompat.animation.MovementAnimationType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MovementAnimationDisplayStateTest {
    @AfterEach
    void clearRemoteState() {
        RemoteMovementAnimationPreferences.beginConnection();
    }

    @Test
    void modPoseMatchesOnlyItsOwnedClipFamilyAndModelWithoutOrdinaryOwnership() {
        MovementAnimationDisplayState state = new MovementAnimationDisplayState(
                " WINE_FOX/21_SAINT ", null, false, false,
                ModAnimationType.PARCOOL, true, "parcool:roll_front");

        assertTrue(state.usesYsmMod("wine_fox/21_saint", ModAnimationType.PARCOOL, "parcool:roll_front"));
        assertFalse(state.usesYsmMod("wine_fox/05_magical", ModAnimationType.PARCOOL, "parcool:roll_front"));
        assertFalse(state.usesYsmMod("wine_fox/21_saint", ModAnimationType.SWEM, "swem:walk"));
        assertFalse(state.usesYsmMod("wine_fox/21_saint", null, "parcool:roll_front"));
        assertFalse(state.usesYsmMod("wine_fox/21_saint", ModAnimationType.PARCOOL, "parcool:roll_back"));
        assertFalse(state.usesYsmMod("wine_fox/21_saint", ModAnimationType.PARCOOL, "PARCOOL:ROLL_FRONT"));
        assertFalse(state.usesYsmMod("wine_fox/21_saint", ModAnimationType.PARCOOL, null));
        assertFalse(state.usesYsmMod("wine_fox/21_saint", ModAnimationType.PARCOOL));
        assertFalse(state.ysmOwned());
        assertFalse(state.naturalLadderPose());
    }

    @Test
    void disabledModPoseRetainsActiveClipAndFamilyAndBothChangesUpdateTheSyncDecision() {
        MovementAnimationDisplayState enabled = new MovementAnimationDisplayState(
                "wine_fox/21_saint", null, false, false, ModAnimationType.SWEM, true, "swem:walk");
        MovementAnimationDisplayState disabled = new MovementAnimationDisplayState(
                "wine_fox/21_saint", null, false, false, ModAnimationType.SWEM, false, "swem:walk");
        MovementAnimationDisplayState changedFamily = new MovementAnimationDisplayState(
                "wine_fox/21_saint", null, false, false, ModAnimationType.PARCOOL, false, "parcool:roll_front");
        MovementAnimationDisplayState changedClip = new MovementAnimationDisplayState(
                "wine_fox/21_saint", null, false, false, ModAnimationType.SWEM, false, "swem:gallop");

        assertEquals(ModAnimationType.SWEM, disabled.modAnimation());
        assertEquals("swem:walk", disabled.modAnimationClip());
        assertFalse(disabled.usesYsmMod("wine_fox/21_saint", ModAnimationType.SWEM, "swem:walk"));
        assertNotEquals(enabled, disabled);
        assertNotEquals(disabled, changedFamily);
        assertNotEquals(disabled, changedClip);
    }

    @Test
    void emptyModelsAndAbsentFamiliesCannotClaimModPoseOwnership() {
        MovementAnimationDisplayState noModel = new MovementAnimationDisplayState(
                "", null, false, false, ModAnimationType.PARCOOL, true, "parcool:roll_front");
        MovementAnimationDisplayState noFamily = new MovementAnimationDisplayState(
                "wine_fox/21_saint", null, false, false, null, true);
        MovementAnimationDisplayState legacy = new MovementAnimationDisplayState(
                "wine_fox/21_saint", MovementAnimationType.LADDER_UP, true, true);

        assertFalse(noModel.modAnimationOwned());
        assertFalse(noFamily.modAnimationOwned());
        assertNull(legacy.modAnimation());
        assertFalse(legacy.modAnimationOwned());
        assertTrue(legacy.naturalLadderPose());
    }

    @Test
    void familyOnlyLegacyStatesAndMissingClipsCannotClaimAnyClip() {
        MovementAnimationDisplayState legacy = new MovementAnimationDisplayState(
                "wine_fox/21_saint", null, false, false, ModAnimationType.PARCOOL, true);
        MovementAnimationDisplayState nullClip = new MovementAnimationDisplayState(
                "wine_fox/21_saint", null, false, false, ModAnimationType.SWEM, true, null);

        assertEquals("", legacy.modAnimationClip());
        assertFalse(legacy.modAnimationOwned());
        assertFalse(legacy.usesYsmMod("wine_fox/21_saint", ModAnimationType.PARCOOL));
        assertFalse(legacy.usesYsmMod("wine_fox/21_saint", ModAnimationType.PARCOOL, "parcool:roll_front"));
        assertEquals("", nullClip.modAnimationClip());
        assertFalse(nullClip.modAnimationOwned());
    }

    @Test
    void rejectsUnboundedUnknownNonCanonicalAndWrongFamilyClipIdentifiers() {
        for (String invalid : new String[]{"swem:jump_lv6", "swem:*", "swem:",
                "SWEM:WALK", " swem:walk", "swem:walk\n", "walk", "parcool:roll_front",
                "x".repeat(MovementAnimationDisplayState.MAX_MOD_ANIMATION_CLIP_LENGTH + 1)}) {
            assertThrows(IllegalArgumentException.class, () ->
                    new MovementAnimationDisplayState("wine_fox/21_saint", null,
                            false, false, ModAnimationType.SWEM, true, invalid));
        }
        assertThrows(IllegalArgumentException.class, () ->
                new MovementAnimationDisplayState("wine_fox/21_saint", null,
                        false, false, null, true, "swem:walk"));
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
    void naturalLadderStateMatchesOnlyItsResolvedModelAndMovement() {
        MovementAnimationDisplayState state = new MovementAnimationDisplayState(
                "wine_fox/01_taisho_maid", MovementAnimationType.LADDER_UP,
                true, true);

        assertTrue(state.usesNaturalLadderPose(
                "wine_fox/01_taisho_maid", MovementAnimationType.LADDER_UP));
        assertFalse(state.usesNaturalLadderPose(
                "wine_fox/21_saint", MovementAnimationType.LADDER_UP));
        assertFalse(state.usesNaturalLadderPose(
                "wine_fox/01_taisho_maid", MovementAnimationType.LADDER_DOWN));
    }

    @Test
    void naturalLadderStateCannotEscapeLadderYsmOwnership() {
        MovementAnimationDisplayState ordinaryMovement =
                new MovementAnimationDisplayState(
                        "wine_fox/01_taisho_maid", MovementAnimationType.RUN,
                        true, true);
        MovementAnimationDisplayState epicOwnedLadder =
                new MovementAnimationDisplayState(
                        "wine_fox/01_taisho_maid", MovementAnimationType.LADDER_IDLE,
                        false, true);

        assertFalse(ordinaryMovement.naturalLadderPose());
        assertFalse(epicOwnedLadder.naturalLadderPose());
    }

    @Test
    void emptyModelOrMovementCannotClaimYsmOwnership() {
        MovementAnimationDisplayState noModel = new MovementAnimationDisplayState(
                "", MovementAnimationType.RUN, true);
        MovementAnimationDisplayState noMovement = new MovementAnimationDisplayState(
                "wine_fox/21_saint", null, true);

        assertFalse(noModel.ysmOwned());
        assertFalse(noMovement.ysmOwned());
        assertFalse(noModel.naturalLadderPose());
        assertFalse(noMovement.naturalLadderPose());
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
                "wine_fox/21_saint", MovementAnimationType.RUN, true, false,
                ModAnimationType.PARCOOL, true, "parcool:fast_running");

        RemoteMovementAnimationPreferences.accept(playerId, state);
        assertEquals(state, RemoteMovementAnimationPreferences.find(playerId));

        RemoteMovementAnimationPreferences.beginConnection();
        assertEquals(MovementAnimationDisplayState.DEFAULT,
                RemoteMovementAnimationPreferences.find(playerId));
    }
}
