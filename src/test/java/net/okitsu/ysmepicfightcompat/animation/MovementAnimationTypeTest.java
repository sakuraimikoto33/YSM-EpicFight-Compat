package net.okitsu.ysmepicfightcompat.animation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MovementAnimationTypeTest {
    @Test
    void crawlMovementUsesOfficialWalkAnimationThreshold() {
        assertFalse(MovementAnimationType.crawlMoving(0.05F));
        assertTrue(MovementAnimationType.crawlMoving(0.0501F));
        assertTrue(MovementAnimationType.crawlMoving(-0.0501F));
    }

    @Test
    void ladderDirectionUsesActualTickDisplacementWithoutDeadZone() {
        assertEquals(MovementAnimationType.LADDER_UP,
                MovementAnimationType.ladderMovement(12.00001D, 12.0D));
        assertEquals(MovementAnimationType.LADDER_IDLE,
                MovementAnimationType.ladderMovement(12.0D, 12.0D));
        assertEquals(MovementAnimationType.LADDER_DOWN,
                MovementAnimationType.ladderMovement(11.99999D, 12.0D));
        assertTrue(MovementAnimationType.LADDER_IDLE.isLadder());
        assertTrue(MovementAnimationType.LADDER_UP.isLadder());
        assertTrue(MovementAnimationType.LADDER_DOWN.isLadder());
        assertFalse(MovementAnimationType.CRAWL_MOVE.isLadder());
    }

    @Test
    void waterIdleMatchesOfficialInWaterAndAirbornePredicate() {
        assertTrue(MovementAnimationType.waterIdle(true, false));
        assertFalse(MovementAnimationType.waterIdle(true, true));
        assertFalse(MovementAnimationType.waterIdle(false, false));
    }

    @Test
    void specialMovementPriorityMatchesOfficialMainControllerOrder() {
        assertEquals(MovementAnimationType.SWIM,
                MovementAnimationType.resolveSpecialMovement(
                        true, true, true, true, 2.0D, 1.0D));
        assertEquals(MovementAnimationType.CRAWL_MOVE,
                MovementAnimationType.resolveSpecialMovement(
                        false, true, true, true, 2.0D, 1.0D));
        assertEquals(MovementAnimationType.CRAWL_IDLE,
                MovementAnimationType.resolveSpecialMovement(
                        false, true, true, false, 2.0D, 1.0D));
        assertEquals(MovementAnimationType.LADDER_DOWN,
                MovementAnimationType.resolveSpecialMovement(
                        false, false, true, false, 1.0D, 2.0D));
        assertNull(MovementAnimationType.resolveSpecialMovement(
                false, false, false, false, 1.0D, 2.0D));
    }

    @Test
    void controllerQueriesMapBackToTheSelectedSemanticMovement() {
        assertEquals(MovementAnimationType.SWIM,
                MovementAnimationType.fromControlQuery("ctrl.swim"));
        assertEquals(MovementAnimationType.WATER_IDLE,
                MovementAnimationType.fromControlQuery("ctrl.swim_stand"));
        assertEquals(MovementAnimationType.CRAWL_MOVE,
                MovementAnimationType.fromControlQuery("ctrl.climb"));
        assertEquals(MovementAnimationType.CRAWL_IDLE,
                MovementAnimationType.fromControlQuery("ctrl.climbing"));
        assertEquals(MovementAnimationType.LADDER_UP,
                MovementAnimationType.fromControlQuery("ctrl.ladder_up"));
        assertEquals(MovementAnimationType.LADDER_IDLE,
                MovementAnimationType.fromControlQuery("ctrl.ladder_stillness"));
        assertEquals(MovementAnimationType.LADDER_DOWN,
                MovementAnimationType.fromControlQuery("ctrl.ladder_down"));
        assertNull(MovementAnimationType.fromControlQuery("ctrl.idle"));
        assertNull(MovementAnimationType.fromControlQuery("query.is_swimming"));

        assertTrue(MovementAnimationType.controlValue(
                "ctrl.ladder_down", MovementAnimationType.LADDER_DOWN));
        assertFalse(MovementAnimationType.controlValue(
                "ctrl.ladder_up", MovementAnimationType.LADDER_DOWN));
        assertTrue(MovementAnimationType.controlValue("ctrl.idle", null));
        assertFalse(MovementAnimationType.controlValue(
                "ctrl.idle", MovementAnimationType.CRAWL_IDLE));
        assertNull(MovementAnimationType.controlValue(
                "query.is_swimming", MovementAnimationType.SWIM));
    }
}
