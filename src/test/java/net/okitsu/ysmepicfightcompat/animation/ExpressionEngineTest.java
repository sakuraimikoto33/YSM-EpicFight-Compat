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
    void malformedExpressionsFailClosedToZero() {
        assertEquals(0.0D,
                ExpressionEngine.compile("variable.a = (").evaluate(new TestEnvironment()));
    }

    private static final class TestEnvironment implements ExpressionEngine.Environment {
        private final Map<Integer, Double> values = new HashMap<>();

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
