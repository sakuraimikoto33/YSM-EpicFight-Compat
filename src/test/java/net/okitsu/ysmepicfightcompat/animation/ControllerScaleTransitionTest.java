package net.okitsu.ysmepicfightcompat.animation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ControllerScaleTransitionTest {
    private static final float EPSILON = 0.0001F;

    @Test
    void builtinProgressOneReplacesTheOldScaleImmediately() {
        ControllerScaleTransition transition = new ControllerScaleTransition(1);
        float[][] values = {{1, 1, 1}};
        boolean[] present = {false};
        transition.begin(1, 1, values, present);
        transition.record(0, new float[]{0, 0, 0});
        transition.apply(values, present);

        values[0] = new float[]{1, 1, 1};
        present[0] = false;
        transition.begin(2, 1, values, present);
        transition.record(0, new float[]{2, 3, 4});
        assertTrue(transition.apply(values, present));
        assertArrayEquals(new float[]{2, 3, 4}, values[0], EPSILON);
        assertTrue(present[0]);
    }

    @Test
    void enteringACustomStateBlendsFromTheLastHiddenScale() {
        ControllerScaleTransition transition = new ControllerScaleTransition(1);
        float[][] values = {{1, 1, 1}};
        boolean[] present = {false};
        transition.begin(1, 1, values, present);
        transition.record(0, new float[]{0, 0, 0});
        transition.apply(values, present);

        sample(transition, 2, 0, values, present, 1, false, new float[]{2, 4, 6});
        assertArrayEquals(new float[]{0, 0, 0}, values[0], EPSILON);
        sample(transition, 2, 0.5F, values, present, 1, false, new float[]{2, 4, 6});
        assertArrayEquals(new float[]{1, 2, 3}, values[0], EPSILON);
        sample(transition, 2, 1, values, present, 1, false, new float[]{2, 4, 6});
        assertArrayEquals(new float[]{2, 4, 6}, values[0], EPSILON);
    }

    @Test
    void precedingScaleStaysLiveAndAnEvaluatedLayerIsNeverAppliedTwice() {
        ControllerScaleTransition transition = new ControllerScaleTransition(1);
        float[][] values = {{2, 2, 2}};
        boolean[] present = {true};
        transition.begin(1, 0.5F, values, present);
        transition.record(0, new float[]{6, 6, 6});
        values[0] = new float[]{6, 6, 6};
        transition.apply(values, present);
        assertArrayEquals(new float[]{4, 4, 4}, values[0], EPSILON);

        values[0] = new float[]{4, 4, 4};
        transition.begin(1, 0.5F, values, present);
        transition.record(0, new float[]{6, 6, 6});
        values[0] = new float[]{99, 99, 99};
        transition.apply(values, present);
        assertArrayEquals(new float[]{5, 5, 5}, values[0], EPSILON);
    }

    @Test
    void interruptedTransitionsFreezeTheDisplayedEffectRatherThanTheOldTarget() {
        ControllerScaleTransition transition = new ControllerScaleTransition(1);
        float[][] values = {{2, 2, 2}};
        boolean[] present = {true};
        transition.begin(1, 0.5F, values, present);
        transition.record(0, new float[]{6, 6, 6});
        transition.apply(values, present);
        assertEquals(4, values[0][0], EPSILON);

        sample(transition, 2, 0, values, present, 2, true, new float[]{10, 10, 10});
        assertEquals(4, values[0][0], EPSILON);
        sample(transition, 2, 0.5F, values, present, 2, true, new float[]{10, 10, 10});
        assertEquals(7, values[0][0], EPSILON);
        sample(transition, 2, 0.5F, values, present, 4, true, new float[]{10, 10, 10});
        assertEquals(7.5F, values[0][0], EPSILON);
    }

    @Test
    void outgoingOnlyTracksReturnToLiveBaseAndReleaseTheirPresenceAtCompletion() {
        ControllerScaleTransition transition = new ControllerScaleTransition(2);
        float[][] values = {{2, 2, 2}, {1, 1, 1}};
        boolean[] present = {true, false};
        transition.begin(1, 1, values, present);
        transition.record(0, new float[]{0, 0, 0});
        transition.record(1, new float[]{2, 2, 2});
        transition.apply(values, present);

        values[0] = new float[]{4, 4, 4};
        values[1] = new float[]{88, 88, 88};
        present[0] = true;
        present[1] = false;
        transition.begin(2, 0.5F, values, present);
        transition.apply(values, present);
        assertArrayEquals(new float[]{2, 2, 2}, values[0], EPSILON);
        assertArrayEquals(new float[]{1.5F, 1.5F, 1.5F}, values[1], EPSILON);
        assertTrue(present[1]);

        values[0] = new float[]{6, 6, 6};
        present[1] = false;
        transition.begin(2, 1, values, present);
        assertFalse(transition.apply(values, present));
        assertArrayEquals(new float[]{6, 6, 6}, values[0], EPSILON);
        assertArrayEquals(new float[]{1, 1, 1}, values[1], EPSILON);
        assertTrue(present[0]);
        assertFalse(present[1]);
    }

    @Test
    void untouchedTracksRestoreTheirOriginalValuesAndPresence() {
        ControllerScaleTransition transition = new ControllerScaleTransition(3);
        float[][] values = {{2, 3, 4}, {1, 1, 1}, {9, 9, 9}};
        boolean[] present = {true, false, false};
        transition.begin(1, 1, values, present);
        transition.record(1, new float[]{5, 6, 7});
        transition.record(1, new float[]{7, 8, 9});
        for (int index = 0; index < values.length; index++) {
            values[index] = new float[]{42, 42, 42};
            present[index] = true;
        }
        transition.apply(values, present);
        assertArrayEquals(new float[]{2, 3, 4}, values[0], EPSILON);
        assertArrayEquals(new float[]{7, 8, 9}, values[1], EPSILON);
        assertArrayEquals(new float[]{1, 1, 1}, values[2], EPSILON);
        assertArrayEquals(new boolean[]{true, true, false}, present);
    }

    @Test
    void validatesCountDimensionsAndCollectionOrder() {
        assertThrows(IllegalArgumentException.class, () -> new ControllerScaleTransition(-1));
        assertThrows(IllegalArgumentException.class, () -> new ControllerScaleTransition(65_537));
        assertDoesNotThrow(() -> new ControllerScaleTransition(65_536));
        ControllerScaleTransition empty = new ControllerScaleTransition(0);
        empty.begin(0, 1, new float[0][3], new boolean[0]);
        assertFalse(empty.apply(new float[0][3], new boolean[0]));

        ControllerScaleTransition transition = new ControllerScaleTransition(1);
        float[][] values = {{1, 1, 1}};
        boolean[] present = {false};
        assertThrows(IllegalStateException.class, () -> transition.record(0, values[0]));
        assertThrows(IllegalStateException.class, () -> transition.apply(values, present));
        assertThrows(IllegalArgumentException.class, () -> transition.begin(1, 1, values, new boolean[0]));
        assertThrows(IllegalArgumentException.class, () -> transition.begin(1, 1, new float[][]{{1, 1}}, present));
        transition.begin(1, 1, values, present);
        assertThrows(IndexOutOfBoundsException.class, () -> transition.record(1, values[0]));
        assertThrows(IllegalArgumentException.class, () -> transition.record(0, new float[2]));
        assertThrows(IllegalArgumentException.class, () -> transition.apply(new float[0][3], present));
        assertFalse(transition.apply(values, present));
        assertThrows(IllegalStateException.class, () -> transition.apply(values, present));
    }

    private static void sample(ControllerScaleTransition transition, long generation, float progress,
                               float[][] values, boolean[] present, float base,
                               boolean basePresent, float[] target) {
        values[0] = new float[]{base, base, base};
        present[0] = basePresent;
        transition.begin(generation, progress, values, present);
        transition.record(0, target);
        transition.apply(values, present);
    }
}
