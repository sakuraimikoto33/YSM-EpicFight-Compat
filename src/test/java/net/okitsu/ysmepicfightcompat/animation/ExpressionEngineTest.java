package net.okitsu.ysmepicfightcompat.animation;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void oneSidedOfficialYsmConditionalUsesZeroForItsOmittedFalseBranch() {
        TestEnvironment environment = new TestEnvironment();
        int headYaw = ExpressionEngine.querySlot("ysm.head_yaw");

        environment.values.put(headYaw, -50.0D);
        assertEquals(-25.0D, ExpressionEngine.compile(
                "-75+(ysm.head_yaw<=0?-ysm.head_yaw)").evaluate(environment), 0.0001D);
        assertEquals(0.0D, ExpressionEngine.compile(
                "(ysm.head_yaw>0?-ysm.head_yaw)").evaluate(environment), 0.0001D);

        environment.values.put(headYaw, 50.0D);
        assertEquals(-75.0D, ExpressionEngine.compile(
                "-75+(ysm.head_yaw<=0?-ysm.head_yaw)").evaluate(environment), 0.0001D);
        assertEquals(-50.0D, ExpressionEngine.compile(
                "(ysm.head_yaw>0?-ysm.head_yaw)").evaluate(environment), 0.0001D);
    }

    @Test
    void sumsOneSidedConditionalsUsedByOfficialBowReleaseChannels() {
        String releaseChannel = "(v.qh==1?(-167.20517))"
                + "+(v.qh==2?(-60))+(v.jump?(-167.20517));";
        TestEnvironment environment = new TestEnvironment();

        environment.writeVariable(ExpressionEngine.slot("v.qh"), 2.0D);
        assertEquals(-60.0D,
                ExpressionEngine.compile(releaseChannel).evaluate(environment), 0.0001D);

        environment.writeVariable(ExpressionEngine.slot("v.qh"), 0.0D);
        assertEquals(0.0D,
                ExpressionEngine.compile(releaseChannel).evaluate(environment), 0.0001D);
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
    void identifiersAreCaseInsensitiveLikeOfficialMolang() {
        TestEnvironment environment = new TestEnvironment();
        int slot = ExpressionEngine.querySlot("ysm.head_yaw");
        environment.values.put(slot, 35.0D);

        assertEquals(slot, ExpressionEngine.querySlot("YSM.head_yaw"));
        assertEquals(35.0D,
                ExpressionEngine.compile("YSM.head_yaw").evaluate(environment), 0.0001D);
    }

    @Test
    void uppercaseVariableAndQueryAliasesKeepTheirOfficialSemantics() {
        TestEnvironment environment = new TestEnvironment();
        environment.animationTime = 0.25D;

        double variable = ExpressionEngine.compile(
                "Variable.Roaming.Jacket=3;V.ROAMING.JACKET+=2;v.roaming.jacket")
                .evaluate(environment);
        double query = ExpressionEngine.compile("Q.ANIM_TIME").evaluate(environment);

        assertEquals(5.0D, variable, 0.0001D);
        assertEquals(0.25D, query, 0.0001D);
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

    @Test
    void mixedFunctionArgumentsKeepTheirTextKeyAndEvaluateNumericExpressions() {
        TestEnvironment environment = new TestEnvironment();

        double result = ExpressionEngine.compile(
                "ysm.first_order('hair',2+3,math.lerp(0,1,0.25))")
                .evaluate(environment);

        assertEquals(5.25D, result, 1.0E-12D);
    }

    @Test
    void recordsDependenciesNeededForSafeWorkerSnapshots() {
        ExpressionEngine.Dependencies pure = ExpressionEngine.compile(
                "math.sin(q.anim_time)+v.wind").dependencies();
        assertTrue(pure.querySlots().contains(
                ExpressionEngine.querySlot("query.anim_time")));
        assertTrue(pure.variableSlots().contains(ExpressionEngine.slot("v.wind")));
        assertEquals(java.util.Set.of("math.sin"), pure.functions());
        assertFalse(pure.writesVariables());
        assertFalse(pure.hasTextArguments());

        ExpressionEngine.Dependencies stateful = ExpressionEngine.compile(
                "v.wind=ysm.first_order('hair',q.delta_time,1)").dependencies();
        assertTrue(stateful.writesVariables());
        assertTrue(stateful.hasTextArguments());
        assertTrue(stateful.functions().contains("ysm.first_order"));
    }

    @Test
    void snapshotKeepsCapturedValuesAndWorkerLocalAnimationTime() {
        TestEnvironment source = new TestEnvironment();
        int wind = ExpressionEngine.slot("v.wind");
        int time = ExpressionEngine.querySlot("q.anim_time");
        source.values.put(wind, 2.0D);
        source.animationTime = 1.0D;
        SnapshotExpressionEnvironment snapshot = SnapshotExpressionEnvironment.capture(
                source, java.util.Set.of(wind), java.util.Set.of(time));
        source.values.put(wind, 9.0D);
        snapshot.clipTime(0.25D);

        assertEquals(3.0D, ExpressionEngine.compile(
                "v.wind+math.lerp(0,4,q.anim_time)").evaluate(snapshot), 0.0001D);
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
                    ? animationTime : values.getOrDefault(slot, 0.0D);
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

        @Override
        public double invokeWithMixedArguments(String name, String[] textArguments,
                                               double[] numericArguments) {
            if (!"ysm.first_order".equals(name)
                    || !"hair".equals(textArguments[0])) {
                return 0.0D;
            }
            return numericArguments[1] + numericArguments[2];
        }
    }
}
