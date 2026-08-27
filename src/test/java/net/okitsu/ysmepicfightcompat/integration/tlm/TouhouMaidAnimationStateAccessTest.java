package net.okitsu.ysmepicfightcompat.integration.tlm;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TouhouMaidAnimationStateAccessTest {
    @AfterEach
    void clearState() {
        TouhouMaidAnimationStateAccess.clear();
    }

    @Test
    void observesEachPlayRequestAsANewGenerationWithoutMutatingState() {
        DuckState state = new DuckState();
        state.rouletteAnimPlaying = true;
        state.rouletteAnim = "extra0";
        TouhouMaidAnimationStateAccess.animationStarted(state, "extra0");

        TouhouMaidAnimationStateAccess.RouletteState first =
                TouhouMaidAnimationStateAccess.readUnchecked(state);
        TouhouMaidAnimationStateAccess.RouletteState stable =
                TouhouMaidAnimationStateAccess.readUnchecked(state);
        TouhouMaidAnimationStateAccess.animationStarted(state, "extra0");
        TouhouMaidAnimationStateAccess.RouletteState repeated =
                TouhouMaidAnimationStateAccess.readUnchecked(state);

        assertEquals("extra0", first.animationName());
        assertTrue(first.playing());
        assertEquals(1L, first.generation());
        assertEquals("extra0", state.rouletteAnim);
        assertTrue(state.rouletteAnimPlaying);
        assertEquals(1L, stable.generation());
        assertEquals(2L, repeated.generation());
    }

    @Test
    void releaseDropsTheRememberedGeneration() {
        DuckState state = new DuckState();
        state.rouletteAnimPlaying = true;
        state.rouletteAnim = "extra0";
        TouhouMaidAnimationStateAccess.animationStarted(state, "extra0");
        assertEquals(1L,
                TouhouMaidAnimationStateAccess.readUnchecked(state).generation());

        TouhouMaidAnimationStateAccess.release(state);

        assertEquals(0L,
                TouhouMaidAnimationStateAccess.readUnchecked(state).generation());
    }

    @Test
    void missingOrWrongFieldsFailClosed() {
        assertEquals(TouhouMaidAnimationStateAccess.RouletteState.none(),
                TouhouMaidAnimationStateAccess.readUnchecked(new Object()));
        assertEquals(TouhouMaidAnimationStateAccess.RouletteState.none(),
                TouhouMaidAnimationStateAccess.readUnchecked(new WrongAnimationType()));
    }

    public static final class DuckState {
        public boolean rouletteAnimPlaying;
        public String rouletteAnim = "";
    }

    public static final class WrongAnimationType {
        public boolean rouletteAnimPlaying = true;
        public Object rouletteAnim = "extra0";
    }
}
