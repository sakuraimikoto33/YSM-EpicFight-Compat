package net.okitsu.ysmepicfightcompat.animation;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Isolated because the capacity regression temporarily fills, then restores, the global cache. */
@Isolated
class AnimationControllerCompilationTest {
    @Test
    void reachedExpressionsSurviveGlobalCapacityAndRuntimeResetWithoutCachingValues()
            throws ReflectiveOperationException {
        String source = "v.controller_capacity_weight_calls+=1;return 1;";
        Map<String, ExpressionEngine.Expression> global = expressionMap(ExpressionEngine.class,
                null, "COMPILED");
        Map<String, ExpressionEngine.Expression> saved = new HashMap<>(global);
        try {
            global.remove(source);
            Field maximum = ExpressionEngine.class.getDeclaredField("MAX_COMPILED");
            maximum.setAccessible(true);
            int limit = maximum.getInt(null);
            for (int index = 0; index < limit; index++) {
                ExpressionEngine.compile("987654321+" + index);
            }
            assertTrue(global.size() >= limit);
            assertNotSame(ExpressionEngine.compile(source), ExpressionEngine.compile(source),
                    "The shared compiler must actually be unable to retain the probe source");

            AnimationControllerProgram program = parsedProgram("""
                    {"animation_controllers":{"controller.cache":{"states":{
                      "default":{"animations":[{"pose":"%s"}]},
                      "unreachable":{"animations":[{"other":"v.unreachable+=1;return 1;"}]}
                    }}}}
                    """.formatted(source));
            Map<String, ExpressionEngine.Expression> retained = handles(program);
            assertTrue(retained.isEmpty(), "Unreached states must not be eagerly compiled");
            TestEnvironment first = new TestEnvironment();
            AnimationControllerProgram.RuntimeState runtime = new AnimationControllerProgram.RuntimeState();
            assertEquals(1.0F, program.select(0, first, runtime).get(0).weight());
            ExpressionEngine.Expression compiled = retained.get(source);
            assertNotNull(compiled);
            assertNull(global.get(source));
            assertEquals(2.0D, first.value("v.controller_capacity_weight_calls"));

            program.select(0, first, runtime);
            assertEquals(4.0D, first.value("v.controller_capacity_weight_calls"),
                    "Completion and output weight still execute on every selection");
            assertSame(compiled, retained.get(source));
            runtime.reset();
            program.select(0, first, runtime);
            assertEquals(6.0D, first.value("v.controller_capacity_weight_calls"));
            assertSame(compiled, retained.get(source));

            TestEnvironment second = new TestEnvironment();
            program.select(0, second, new AnimationControllerProgram.RuntimeState());
            assertEquals(2.0D, second.value("v.controller_capacity_weight_calls"));
            assertEquals(6.0D, first.value("v.controller_capacity_weight_calls"));
            assertEquals(1, retained.size(), "Only the reached source belongs in this program's cache");
            assertEquals(0.0D, first.value("v.unreachable"));
        } finally {
            // Slot IDs must remain stable for other already compiled expressions.
            global.clear();
            global.putAll(saved);
        }
    }

    @Test
    void compilationReusePreservesLifecycleVariableWeightAndTransitionEvaluationOrder() {
        AnimationControllerProgram program = parsedProgram("""
                {"animation_controllers":{"controller.order":{"states":{
                  "default":{
                    "variables":{"sample":"q.record(1)"},
                    "animations":[{"pose":"q.record(2)"}],
                    "on_entry":["q.record(0);v.entries+=1;"],
                    "on_exit":["q.record(4);"],
                    "transitions":[{"next":"q.record(3) && v.advance"}]
                  },
                  "next":{"animations":[{"other":"q.record(6)"}],
                    "on_entry":["q.record(5);v.entries+=1;"]}
                }}}}
                """);
        TestEnvironment first = new TestEnvironment();
        AnimationControllerProgram.RuntimeState runtime = new AnimationControllerProgram.RuntimeState();
        assertEquals("pose", program.select(0, first, runtime).get(0).name());
        assertEquals(List.of(1, 0, 1, 2, 2), first.takeTrace());

        program.select(0, first, runtime);
        assertEquals(List.of(1, 2, 2), first.takeTrace());
        program.select(0.1, first, runtime);
        assertEquals(List.of(1, 2, 3, 1, 2, 2), first.takeTrace());
        first.set("v.advance", 1);
        assertEquals("other", program.select(0.2, first, runtime).get(0).name());
        assertEquals(List.of(1, 2, 3, 4, 5, 6, 6), first.takeTrace());
        assertEquals(2.0D, first.value("v.entries"));

        TestEnvironment second = new TestEnvironment();
        assertEquals("pose", program.select(0, second,
                new AnimationControllerProgram.RuntimeState()).get(0).name());
        assertEquals(List.of(1, 0, 1, 2, 2), second.takeTrace());
        assertEquals(1.0D, second.value("v.entries"));
        assertEquals(0.0D, first.value("v.sample"), "Controller-local variables must not leak");
    }

    @Test
    void invalidExpressionsKeepZeroFallbackAndEvaluationExceptionsStillPropagate()
            throws ReflectiveOperationException {
        AnimationControllerProgram program = parsedProgram("""
                {"animation_controllers":{"controller.failures":{"states":{
                  "default":{"animations":[{"pose":"q.explode()"},{"other":"1 +"}],
                    "transitions":[{"next":"1 +"}]},
                  "next":{"animations":["other"]}
                }}}}
                """);
        TestEnvironment environment = new TestEnvironment();
        AnimationControllerProgram.RuntimeState runtime = new AnimationControllerProgram.RuntimeState();
        environment.failure = new IllegalStateException("synthetic evaluation failure");
        assertSame(environment.failure, assertThrows(IllegalStateException.class,
                () -> program.select(0, environment, runtime)));
        ExpressionEngine.Expression throwing = handles(program).get("q.explode()");
        assertNotNull(throwing);

        environment.failure = null;
        assertEquals(List.of(1.0F, 0.0F), program.select(0, environment, runtime).stream()
                .map(AnimationControllerProgram.ActiveAnimation::weight).toList());
        ExpressionEngine.Expression invalid = handles(program).get("1 +");
        assertNotNull(invalid);
        assertFalse(invalid.isValid());
        assertEquals(List.of("pose", "other"), program.select(0.1, environment, runtime).stream()
                .map(AnimationControllerProgram.ActiveAnimation::name).toList());
        assertSame(invalid, handles(program).get("1 +"));
        assertSame(throwing, handles(program).get("q.explode()"));

        environment.failure = new IllegalStateException("later evaluation failure");
        assertSame(environment.failure, assertThrows(IllegalStateException.class,
                () -> program.select(0.1, environment, runtime)));
        assertSame(throwing, handles(program).get("q.explode()"));
    }

    @Test
    void sharedProgramKeepsConcurrentEntityValuesAndRuntimeStateIndependent() throws Exception {
        AnimationControllerProgram program = parsedProgram("""
                {"animation_controllers":{"controller.entities":{"states":{
                  "default":{"animations":[{"pose":"v.evaluations+=1;return v.identity;"}],
                    "on_entry":["v.entries+=1;"]}
                }}}}
                """);
        var workers = Executors.newFixedThreadPool(2);
        try {
            List<Callable<TestEnvironment>> calls = List.of(
                    () -> sampleEntity(program, 0.25F), () -> sampleEntity(program, 0.75F));
            var results = workers.invokeAll(calls, 10, TimeUnit.SECONDS);
            for (var result : results) {
                TestEnvironment environment = result.get(1, TimeUnit.SECONDS);
                assertEquals(1.0D, environment.value("v.entries"));
                assertEquals(100.0D, environment.value("v.evaluations"));
            }
        } finally {
            workers.shutdownNow();
        }
    }

    private static TestEnvironment sampleEntity(AnimationControllerProgram program, float identity) {
        TestEnvironment environment = new TestEnvironment();
        environment.set("v.identity", identity);
        AnimationControllerProgram.RuntimeState runtime = new AnimationControllerProgram.RuntimeState();
        for (int index = 0; index < 50; index++) {
            assertEquals(identity, program.select(0, environment, runtime).get(0).weight());
        }
        return environment;
    }

    private static AnimationControllerProgram parsedProgram(String source) {
        return new AnimationControllerProgram(BedrockAnimationControllerParser.parse(
                JsonParser.parseString(source).getAsJsonObject()), Map.of(
                "pose", new AnimationControllerProgram.ClipInfo(1, AnimationClip.Playback.REPEAT),
                "other", new AnimationControllerProgram.ClipInfo(1, AnimationClip.Playback.REPEAT)));
    }

    private static Map<String, ExpressionEngine.Expression> handles(AnimationControllerProgram program)
            throws ReflectiveOperationException {
        return expressionMap(AnimationControllerProgram.class, program, "compiledExpressions");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, ExpressionEngine.Expression> expressionMap(
            Class<?> owner, Object target, String name) throws ReflectiveOperationException {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return (Map<String, ExpressionEngine.Expression>) field.get(target);
    }

    private static final class TestEnvironment implements ExpressionEngine.Environment {
        private final Map<Integer, Double> values = new HashMap<>();
        private final List<Integer> trace = new ArrayList<>();
        private IllegalStateException failure;

        private void set(String name, double value) { values.put(ExpressionEngine.slot(name), value); }
        private double value(String name) { return readVariable(ExpressionEngine.slot(name)); }
        private List<Integer> takeTrace() {
            List<Integer> result = List.copyOf(trace);
            trace.clear();
            return result;
        }
        @Override public double readVariable(int slot) { return values.getOrDefault(slot, 0.0D); }
        @Override public boolean hasVariable(int slot) { return values.containsKey(slot); }
        @Override public void writeVariable(int slot, double value) { values.put(slot, value); }
        @Override public double readQuery(int slot) { return 0.0D; }
        @Override public double invokeWithText(String name, String[] arguments) { return 0.0D; }
        @Override public double invoke(String name, double[] arguments) {
            if (name.equals("q.explode") && failure != null) throw failure;
            if (name.equals("q.record")) trace.add((int) arguments[0]);
            return 1.0D;
        }
    }
}
