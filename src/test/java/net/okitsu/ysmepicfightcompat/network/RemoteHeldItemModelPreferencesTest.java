package net.okitsu.ysmepicfightcompat.network;

import net.minecraft.world.InteractionHand;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RemoteHeldItemModelPreferencesTest {
    @BeforeEach
    void initialize() {
        RemoteHeldItemModelPreferences.beginConnection();
    }

    @AfterEach
    void reset() {
        RemoteHeldItemModelPreferences.beginConnection();
    }

    @Test
    void unknownPlayersFailClosed() {
        HeldItemModelDisplayState state =
                RemoteHeldItemModelPreferences.find(UUID.randomUUID());

        assertSame(HeldItemModelDisplayState.UNKNOWN, state);
        assertFalse(state.usesYsm(InteractionHand.MAIN_HAND));
        assertFalse(state.usesYsm(InteractionHand.OFF_HAND));
        assertFalse(state.usesYsmSwitchAnimation(InteractionHand.MAIN_HAND));
        assertFalse(state.usesYsmSwitchAnimation(InteractionHand.OFF_HAND));
    }

    @Test
    void acceptsResolvedStateAndClearsItForANewConnection() {
        UUID playerId = UUID.randomUUID();
        HeldItemModelDisplayState accepted =
                new HeldItemModelDisplayState(true, false, false, true);

        RemoteHeldItemModelPreferences.accept(playerId, accepted);
        assertSame(accepted, RemoteHeldItemModelPreferences.find(playerId));
        assertTrue(RemoteHeldItemModelPreferences.find(playerId)
                .usesYsm(InteractionHand.MAIN_HAND));
        assertFalse(RemoteHeldItemModelPreferences.find(playerId)
                .usesYsm(InteractionHand.OFF_HAND));
        assertFalse(RemoteHeldItemModelPreferences.find(playerId)
                .usesYsmSwitchAnimation(InteractionHand.MAIN_HAND));
        assertTrue(RemoteHeldItemModelPreferences.find(playerId)
                .usesYsmSwitchAnimation(InteractionHand.OFF_HAND));

        RemoteHeldItemModelPreferences.beginConnection();
        HeldItemModelDisplayState reset =
                RemoteHeldItemModelPreferences.find(playerId);
        assertFalse(reset.usesYsm(InteractionHand.MAIN_HAND));
        assertFalse(reset.usesYsm(InteractionHand.OFF_HAND));
        assertFalse(reset.usesYsmSwitchAnimation(InteractionHand.MAIN_HAND));
        assertFalse(reset.usesYsmSwitchAnimation(InteractionHand.OFF_HAND));
    }
}
