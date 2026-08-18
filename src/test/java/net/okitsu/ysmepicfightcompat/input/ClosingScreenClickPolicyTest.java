package net.okitsu.ysmepicfightcompat.input;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ClosingScreenClickPolicyTest {
    @Test
    void consumesUnhandledClickThatClosedScreen() {
        assertTrue(ClosingScreenClickPolicy.shouldConsume(true, false, true));
    }

    @Test
    void leavesClicksAloneWhileScreenRemainsOpen() {
        assertFalse(ClosingScreenClickPolicy.shouldConsume(false, false, true));
    }

    @Test
    void leavesAlreadyHandledClicksAlone() {
        assertFalse(ClosingScreenClickPolicy.shouldConsume(true, true, true));
    }

    @Test
    void preservesAnotherEventHandlersDecision() {
        assertFalse(ClosingScreenClickPolicy.shouldConsume(true, false, false));
    }
}
