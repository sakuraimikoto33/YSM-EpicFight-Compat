package net.okitsu.ysmepicfightcompat.animation;

import net.okitsu.ysmepicfightcompat.geometry.GeometryDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MultilineTimelineTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void evaluatesConditionalBlockSplitAcrossCommandLines(boolean enabled) {
        AnimationClip clip = clip("v.enabled ? {", "v.result=7;", "};");
        Environment environment = new Environment();
        environment.writeVariable(ExpressionEngine.slot("v.enabled"), enabled ? 1.0D : 0.0D);

        clip.mergeTimelineExpressions();
        evaluate(clip.timeline().get(0), environment);

        assertEquals(enabled ? 7.0D : 0.0D, environment.value("v.result"));
        assertEquals(enabled, environment.hasVariable(ExpressionEngine.slot("v.result")));
    }

    @Test
    void preservesNewlinesAfterCommentsAndDoesNotInsertStatementSeparators() {
        AnimationClip clip = clip("v.result=1; // not v.result=99;",
                "true ? { // open a block", "v.result+=2;", "};");
        Environment environment = new Environment();

        clip.mergeTimelineExpressions();
        evaluate(clip.timeline().get(0), environment);

        assertEquals(3.0D, environment.value("v.result"));
        assertEquals("v.result=1; // not v.result=99;\ntrue ? { // open a block\nv.result+=2;\n};",
                clip.timeline().get(0).statements().get(0));
    }

    @Test
    void returnExitsMergedEventButNotIndependentStatementsOrLaterEvents() {
        AnimationClip independent = clip("v.before=1;", "return 7;", "v.after=2;");
        AnimationClip merged = clip("v.before=1;", "return 7;", "v.after=2;");
        merged.timeline().add(new AnimationClip.TimelineEvent(1.0F, List.of("v.later=3;")));
        merged.mergeTimelineExpressions();
        Environment independentEnvironment = new Environment();
        Environment mergedEnvironment = new Environment();

        evaluate(independent.timeline().get(0), independentEnvironment);
        evaluate(merged.timeline().get(0), mergedEnvironment);

        assertEquals(1.0D, independentEnvironment.value("v.before"));
        assertEquals(1.0D, mergedEnvironment.value("v.before"));
        assertEquals(2.0D, independentEnvironment.value("v.after"));
        assertFalse(mergedEnvironment.hasVariable(ExpressionEngine.slot("v.after")));
        assertFalse(mergedEnvironment.hasVariable(ExpressionEngine.slot("v.later")));
        evaluate(merged.timeline().get(1), mergedEnvironment);
        assertEquals(3.0D, mergedEnvironment.value("v.later"));
        assertEquals(3, independent.timeline().get(0).statements().size());
    }

    @Test
    void leavesEmptyAndSingleEventsUnchangedAndMergingIsIdempotent() {
        AnimationClip clip = new AnimationClip("parallel0");
        AnimationClip.TimelineEvent empty = new AnimationClip.TimelineEvent(0.0F, List.of());
        AnimationClip.TimelineEvent single = new AnimationClip.TimelineEvent(1.0F,
                List.of("v.single=1;"));
        clip.timeline().add(empty);
        clip.timeline().add(single);
        clip.timeline().add(new AnimationClip.TimelineEvent(2.0F,
                List.of("v.first=1;", "v.second=2;")));

        clip.mergeTimelineExpressions();
        List<AnimationClip.TimelineEvent> once = List.copyOf(clip.timeline());
        clip.mergeTimelineExpressions();

        assertEquals(once, clip.timeline());
        assertEquals(3, clip.timeline().size());
        assertSame(empty, clip.timeline().get(0));
        assertSame(single, clip.timeline().get(1));
        assertSame(once.get(2), clip.timeline().get(2));
        assertEquals(2.0F, clip.timeline().get(2).time());
        assertEquals(List.of("v.first=1;\nv.second=2;"),
                clip.timeline().get(2).statements());
        AnimationClip noEvents = new AnimationClip("empty");
        noEvents.mergeTimelineExpressions();
        assertTrue(noEvents.timeline().isEmpty());
    }

    @Test
    void boundsJoinedSourceIncludingInsertedNewlines() {
        AnimationClip atLimit = clip(" ".repeat(ExpressionEngine.MAX_SOURCE_LENGTH - 2), "1");
        atLimit.mergeTimelineExpressions();
        String joined = atLimit.timeline().get(0).statements().get(0);
        assertEquals(ExpressionEngine.MAX_SOURCE_LENGTH, joined.length());
        assertTrue(ExpressionEngine.compile(joined).isValid());

        AnimationClip oversized = clip(" ".repeat(ExpressionEngine.MAX_SOURCE_LENGTH - 1), "1");
        AnimationClip.TimelineEvent original = oversized.timeline().get(0);
        assertThrows(IllegalArgumentException.class, oversized::mergeTimelineExpressions);
        assertSame(original, oversized.timeline().get(0),
                "a rejected event must not become a truncated or partially joined program");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void defaultVisibilityUsesTheMergedConditionalResult(boolean hidden) {
        AnimationClip clip = clip(hidden + " ? {", "v.hidden=1;", "};");
        AnimationClip.VectorValue scale = new AnimationClip.VectorValue();
        for (int axis = 0; axis < 3; axis++) {
            scale.setExpression(axis, "1-v.hidden");
        }
        AnimationClip.Track track = new AnimationClip.Track();
        track.keyframes().add(new AnimationClip.Keyframe(
                0.0F, AnimationClip.Interpolation.LINEAR, scale, null));
        AnimationClip.BoneTracks tracks = new AnimationClip.BoneTracks();
        tracks.scale(track);
        clip.boneTracks().put("Accessory", tracks);
        clip.mergeTimelineExpressions();
        GeometryDocument geometry = new GeometryDocument();
        geometry.bones().put("Accessory", new GeometryDocument.Bone("Accessory"));

        assertEquals(hidden, DefaultPoseProgram.calculateVisibility(
                geometry, Map.of(clip.name(), clip)).get("Accessory"));
    }

    private static AnimationClip clip(String... lines) {
        AnimationClip clip = new AnimationClip("parallel0");
        clip.timeline().add(new AnimationClip.TimelineEvent(0.0F, List.of(lines)));
        return clip;
    }

    private static void evaluate(AnimationClip.TimelineEvent event, Environment environment) {
        for (String statement : event.statements()) {
            ExpressionEngine.Expression expression = ExpressionEngine.compile(statement);
            assertTrue(expression.isValid(), expression::diagnostic);
            expression.evaluate(environment);
        }
    }

    private static final class Environment implements ExpressionEngine.Environment {
        private final Map<Integer, Double> values = new HashMap<>();

        double value(String name) {
            return readVariable(ExpressionEngine.slot(name));
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
            return 0.0D;
        }

        @Override
        public double invoke(String name, double[] arguments) {
            return 0.0D;
        }

        @Override
        public double invokeWithText(String name, String[] arguments) {
            return 0.0D;
        }
    }
}
