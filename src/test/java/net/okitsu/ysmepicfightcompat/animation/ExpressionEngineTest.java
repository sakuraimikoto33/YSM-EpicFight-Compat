package net.okitsu.ysmepicfightcompat.animation;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ExpressionEngineTest {
    @Test
    void evaluatesPrecedenceAssignmentAndConditionalOperators() {
        TestEnvironment environment = new TestEnvironment();

        double result = ExpressionEngine.compile(
                "variable.a = 2 + 3 * 4; variable.b = variable.a > 10 ? 5 : 1; variable.b")
                .evaluate(environment);

        assertEquals(14.0D, environment.value("variable.a"));
        assertEquals(5.0D, result);
    }

    @Test
    void coalesceDistinguishesUnsetVariablesFromAssignedZero() {
        TestEnvironment environment = new TestEnvironment();
        assertEquals(7.0D,
                ExpressionEngine.compile("variable.missing ?? 7").evaluate(environment));
        ExpressionEngine.compile("variable.missing = 0").evaluate(environment);
        assertEquals(0.0D,
                ExpressionEngine.compile("variable.missing ?? 7").evaluate(environment));
    }

    @Test
    void abbreviatedAndFullVariableNamespacesShareTheSameSlot() {
        TestEnvironment environment = new TestEnvironment();

        double result = ExpressionEngine.compile(
                "variable.eye=1;v.eye+=2;variable.roaming.jacket=4;"
                        + "v.roaming.jacket+=1;variable.eye+v.roaming.jacket")
                .evaluate(environment);

        assertEquals(ExpressionEngine.slot("variable.eye"), ExpressionEngine.slot("v.eye"));
        assertEquals(ExpressionEngine.slot("variable.roaming.jacket"),
                ExpressionEngine.slot("v.roaming.jacket"));
        assertEquals(8.0D, result, 0.0001D);
    }

    @Test
    void malformedExpressionsFailClosedToZero() {
        assertEquals(0.0D,
                ExpressionEngine.compile("variable.a = (").evaluate(new TestEnvironment()));
    }

    @Test
    void nestedFunctionsKeepTheOuterArgumentsUsedByEyeDotAnimations() {
        TestEnvironment environment = new TestEnvironment();
        environment.animationTime = 0.3D;

        double result = ExpressionEngine.compile(
                "math.lerp(0,math.sin(q.anim_time*1440),math.exp(-q.anim_time*5))")
                .evaluate(environment);
        double expected = Math.sin(Math.toRadians(environment.animationTime * 1440.0D))
                * Math.exp(-environment.animationTime * 5.0D);

        assertEquals(expected, result, 1.0E-12D);
    }

    private static final class TestEnvironment implements ExpressionEngine.Environment {
        private final Map<Integer, Double> values = new HashMap<>();
        private double animationTime;

        double value(String name) {
            return values.getOrDefault(ExpressionEngine.slot(name), 0.0D);
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
            return "query.anim_time".equals(ExpressionEngine.slotName(slot))
                    ? animationTime : 0.0D;
        }

        @Override
        public double invoke(String name, double[] arguments) {
            return switch (name) {
                case "math.sin" -> Math.sin(Math.toRadians(arguments[0]));
                case "math.exp" -> Math.exp(arguments[0]);
                case "math.lerp" -> arguments[0]
                        + (arguments[1] - arguments[0]) * arguments[2];
                default -> 0.0D;
            };
        }

        @Override
        public double invokeWithText(String name, String[] arguments) {
            return 0.0D;
        }
    }
}
