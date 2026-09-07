package net.okitsu.ysmepicfightcompat.animation;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PoseOnlyExpressionEnvironmentTest {
    @Test
    void wrappingIsIdempotentAndRetainsTheOriginalScriptRuntime() {
        CountingEnvironment source = new CountingEnvironment();
        ExpressionEngine.Environment wrapped = ParallelAnimationProgram.poseOnlyEnvironment(source);

        assertSame(wrapped, ParallelAnimationProgram.poseOnlyEnvironment(wrapped));
        assertSame(source.runtime, MolangScriptRuntime.scripts(wrapped));
    }

    @Test
    void suppressesEveryExternalEffectAcrossEveryInvocationShapeIgnoringCase() {
        CountingEnvironment source = new CountingEnvironment();
        ExpressionEngine.Environment wrapped = ParallelAnimationProgram.poseOnlyEnvironment(source);

        for (String name : new String[]{"ysm.play_sound", "ysm.stop_sound", "ysm.stop_all_sounds",
                "ysm.particle", "ysm.abs_particle", "ysm.sync"}) {
            for (String spelling : new String[]{name, name.toUpperCase(Locale.ROOT),
                    "YsM." + name.substring(4)}) {
                assertEquals(0.0D, wrapped.invoke(spelling, new double[]{1.0D}));
                assertEquals(0.0D, wrapped.invokeWithText(spelling, new String[]{"effect"}));
                assertEquals(0.0D, wrapped.invokeWithMixedArguments(spelling,
                        new String[]{"effect", null}, new double[]{0.0D, 1.0D}));
                assertEquals(0.0D, wrapped.invokeValue(spelling, new Object[]{"effect", 1.0D}));
            }
        }

        assertEquals(0, source.invocations);
    }

    @Test
    void forwardsNumericAndTypedVariablesAndQueriesWithoutCoercion() {
        CountingEnvironment source = new CountingEnvironment();
        ExpressionEngine.Environment wrapped = ParallelAnimationProgram.poseOnlyEnvironment(source);
        int numericSlot = ExpressionEngine.slot("v.pose_weight");
        int typedSlot = ExpressionEngine.slot("v.pose_options");
        int numericQuery = ExpressionEngine.querySlot("q.anim_time");
        int typedQuery = ExpressionEngine.querySlot("ysm.pose_options");
        Map<String, Object> options = Map.of("form", "quadruped", "weight", 0.25D);

        assertFalse(wrapped.hasVariable(numericSlot));
        wrapped.writeVariable(numericSlot, 0.75D);
        wrapped.writeVariableValue(typedSlot, options);
        source.queries.put(numericQuery, 2.5D);
        source.queries.put(typedQuery, options);

        assertTrue(wrapped.hasVariable(numericSlot));
        assertEquals(0.75D, wrapped.readVariable(numericSlot));
        assertEquals(0.75D, source.variables.get(numericSlot));
        assertSame(options, wrapped.readVariableValue(typedSlot));
        assertSame(options, source.variables.get(typedSlot));
        assertEquals(2.5D, wrapped.readQuery(numericQuery));
        assertSame(options, wrapped.readQueryValue(typedQuery));
        assertSame(source.arguments, wrapped.arguments());
    }

    @Test
    void forwardsOtherFunctionsAndTheirOriginalBorrowedArgumentArrays() {
        CountingEnvironment source = new CountingEnvironment();
        ExpressionEngine.Environment wrapped = ParallelAnimationProgram.poseOnlyEnvironment(source);
        double[] numeric = {2.0D, 3.0D};
        String[] text = {"pose", null};
        Object[] typed = {Map.of("pose", 1.0D), "unchanged"};

        assertEquals(7.0D, wrapped.invoke("ysm.custom_numeric", numeric));
        assertEquals("numeric", source.invocationShape);
        assertSame(numeric, source.numericArguments);
        assertEquals(8.0D, wrapped.invokeWithText("ysm.custom_text", text));
        assertEquals("text", source.invocationShape);
        assertSame(text, source.textArguments);
        assertEquals(9.0D, wrapped.invokeWithMixedArguments("ysm.first_order", text, numeric));
        assertEquals("mixed", source.invocationShape);
        assertSame(text, source.textArguments);
        assertSame(numeric, source.numericArguments);
        assertSame(source.typedResult, wrapped.invokeValue("ysm.custom_typed", typed));
        assertEquals("typed", source.invocationShape);
        assertSame(typed, source.typedArguments);
        assertEquals(4, source.invocations);
    }

    @Test
    void effectFilterDoesNotSuppressUnrelatedOrSimilarFunctionNames() {
        CountingEnvironment source = new CountingEnvironment();
        ExpressionEngine.Environment wrapped = ParallelAnimationProgram.poseOnlyEnvironment(source);

        for (String name : new String[]{"ysm.play_sound_extra", "ysm.particle_count",
                "custom.play_sound", "play_sound", " ysm.play_sound", "ysm.play_sound "}) {
            assertEquals(7.0D, wrapped.invoke(name, new double[0]));
            assertEquals(name, source.invocationName);
        }

        assertEquals(6, source.invocations);
    }

    @Test
    void compiledChannelsKeepDependentWritesArithmeticAndPhysicsButSkipEffects() {
        CountingEnvironment source = new CountingEnvironment();
        ExpressionEngine.Environment wrapped = ParallelAnimationProgram.poseOnlyEnvironment(source);
        source.queries.put(ExpressionEngine.querySlot("q.anim_time"), 2.0D);
        ExpressionEngine.Expression expression = ExpressionEngine.compile(
                "v.body=math.sin(90)+q.anim_time;"
                        + "ysm.play_sound('effect',v.effect_argument=v.body+1);"
                        + "ysm.stop_sound('effect');ysm.stop_all_sounds();"
                        + "ysm.particle('effect',v.body);ysm.abs_particle('effect',v.body);ysm.sync(v.body);"
                        + "v.locator=v.body+ysm.first_order('pose',v.body,0.5);"
                        + "v.locator+v.effect_argument");

        assertTrue(expression.isValid(), expression.diagnostic());
        assertEquals(16.0D, expression.evaluate(wrapped));
        assertEquals(3.0D, source.variables.get(ExpressionEngine.slot("v.body")));
        assertEquals(4.0D, source.variables.get(ExpressionEngine.slot("v.effect_argument")));
        assertEquals(12.0D, source.variables.get(ExpressionEngine.slot("v.locator")));
        assertEquals(1, source.invocations);
        assertEquals("ysm.first_order", source.invocationName);
        assertEquals("mixed", source.invocationShape);

        CountingEnvironment ordinary = new CountingEnvironment();
        ordinary.queries.put(ExpressionEngine.querySlot("q.anim_time"), 2.0D);
        assertEquals(16.0D, expression.evaluate(ordinary));
        assertEquals(ordinary.variables, source.variables,
                "Pose-only sampling must preserve ordinary arithmetic and variable dependencies");
        assertEquals(6, ordinary.invocations, "Only the five sound/particle calls should be removed");
    }

    @Test
    void compiledTypedChannelsKeepTheirValuesAndFunctionReturns() {
        CountingEnvironment source = new CountingEnvironment();
        ExpressionEngine.Environment wrapped = ParallelAnimationProgram.poseOnlyEnvironment(source);
        ExpressionEngine.Expression expression = ExpressionEngine.compile(
                "v.form='Quadruped';v.options=ysm.custom_typed(v.form);"
                        + "ysm.particle('effect');v.options");

        assertTrue(expression.isValid(), expression.diagnostic());
        assertEquals(source.typedResult, expression.evaluateValue(wrapped));
        assertEquals("Quadruped", source.variables.get(ExpressionEngine.slot("v.form")));
        assertEquals(source.typedResult, source.variables.get(ExpressionEngine.slot("v.options")));
        assertEquals(1, source.invocations);
        assertEquals("ysm.custom_typed", source.invocationName);
    }

    @Test
    void nestedFunctionsKeepTheirArgumentsAndLocalStateWithoutPublishingEffects() {
        CountingEnvironment source = new CountingEnvironment(Map.of(
                "outer", "t.keep=10;v.body=args[0];v.form='Quadruped';"
                        + "v.from_inner=fn.inner(args[0]+1);"
                        + "ysm.play_sound('effect');ysm.sync(args[0]);"
                        + "return t.keep+v.body+v.from_inner;",
                "inner", "t.keep=99;ysm.stop_sound('effect');ysm.stop_all_sounds();"
                        + "ysm.particle('effect');ysm.abs_particle('effect');ysm.sync(args[0]);"
                        + "return args[0]*2;",
                "bare", "v.from_bare=7;ysm.sync(7);return v.from_bare;"));
        int[] syncs = {0};
        source.runtime.syncSender(ignored -> syncs[0]++);
        ExpressionEngine.Environment wrapped = ParallelAnimationProgram.poseOnlyEnvironment(source);
        ExpressionEngine.Expression expression = ExpressionEngine.compile("fn.outer(3)+fn.bare");

        assertTrue(expression.isValid(), expression.diagnostic());
        assertEquals(28.0D, expression.evaluate(wrapped));
        assertEquals("Quadruped", source.variables.get(ExpressionEngine.slot("v.form")));
        assertEquals(8.0D, source.variables.get(ExpressionEngine.slot("v.from_inner")));
        assertEquals(7.0D, source.variables.get(ExpressionEngine.slot("v.from_bare")));
        assertEquals(0, source.invocations, "Nested functions must not publish sound or particle effects");
        assertEquals(0, syncs[0], "Nested functions must not publish sync packets");
        assertSame(source.runtime, MolangScriptRuntime.scripts(wrapped));
    }

    @Test
    void normalEnvironmentStillPublishesDirectAndNestedSyncAfterPoseOnlySampling() {
        CountingEnvironment source = new CountingEnvironment(Map.of(
                "nested", "ysm.sync(args[0]);return args[0];"));
        int[] syncs = {0};
        double[] total = {0.0D};
        source.runtime.syncSender(arguments -> {
            syncs[0]++;
            total[0] += arguments[0];
        });
        ExpressionEngine.Environment wrapped = ParallelAnimationProgram.poseOnlyEnvironment(source);
        ExpressionEngine.Expression expression = ExpressionEngine.compile("ysm.sync(2);fn.nested(3)");

        assertEquals(3.0D, expression.evaluate(wrapped));
        source.runtime.invoke("ysm.sync", new Object[]{5.0D}, wrapped);
        assertEquals(0, syncs[0]);
        assertFalse(MolangScriptRuntime.externalOutputsEnabled(wrapped));

        assertEquals(3.0D, expression.evaluate(source));
        assertEquals(2, syncs[0]);
        assertEquals(5.0D, total[0]);
        assertTrue(MolangScriptRuntime.externalOutputsEnabled(source));
    }

    private static final class CountingEnvironment implements MolangScriptRuntime.Host {
        private final Map<Integer, Object> variables = new HashMap<>();
        private final Map<Integer, Object> queries = new HashMap<>();
        private final Object[] arguments = {"pose", 2.0D};
        private final Object typedResult = Map.of("form", "Quadruped");
        private final MolangScriptRuntime runtime;
        private int invocations;
        private String invocationName;
        private String invocationShape;
        private double[] numericArguments;
        private String[] textArguments;
        private Object[] typedArguments;

        private CountingEnvironment() {
            this(Map.of());
        }

        private CountingEnvironment(Map<String, String> functions) {
            runtime = new MolangScriptRuntime(functions, Map.of());
        }

        @Override
        public MolangScriptRuntime scripts() {
            return runtime;
        }

        @Override
        public double readVariable(int slot) {
            return number(variables.get(slot));
        }

        @Override
        public Object readVariableValue(int slot) {
            return variables.get(slot);
        }

        @Override
        public boolean hasVariable(int slot) {
            return variables.containsKey(slot);
        }

        @Override
        public void writeVariable(int slot, double value) {
            variables.put(slot, value);
        }

        @Override
        public void writeVariableValue(int slot, Object value) {
            variables.put(slot, value);
        }

        @Override
        public double readQuery(int slot) {
            return number(queries.get(slot));
        }

        @Override
        public Object readQueryValue(int slot) {
            Object value = runtime.read(ExpressionEngine.slotName(slot), this);
            return value == MolangScriptRuntime.UNHANDLED ? queries.get(slot) : value;
        }

        @Override
        public double invoke(String name, double[] arguments) {
            if ("math.sin".equals(name)) {
                return Math.sin(Math.toRadians(arguments.length == 0 ? 0.0D : arguments[0]));
            }
            recordInvocation(name, "numeric");
            numericArguments = arguments;
            return 7.0D;
        }

        @Override
        public double invokeWithText(String name, String[] arguments) {
            recordInvocation(name, "text");
            textArguments = arguments;
            return 8.0D;
        }

        @Override
        public double invokeWithMixedArguments(String name, String[] text, double[] numeric) {
            recordInvocation(name, "mixed");
            textArguments = text;
            numericArguments = numeric;
            return 9.0D;
        }

        @Override
        public Object invokeValue(String name, Object[] arguments) {
            Object value = runtime.invoke(name, arguments, this);
            if (value != MolangScriptRuntime.UNHANDLED) {
                return value;
            }
            if ("ysm.first_order".equals(name) || "math.sin".equals(name)) {
                return MolangScriptRuntime.Host.super.invokeValue(name, arguments);
            }
            recordInvocation(name, "typed");
            typedArguments = arguments;
            return typedResult;
        }

        @Override
        public Object[] arguments() {
            return arguments;
        }

        private void recordInvocation(String name, String shape) {
            invocations++;
            invocationName = name;
            invocationShape = shape;
        }

        private static double number(Object value) {
            return value instanceof Number number ? number.doubleValue() : 0.0D;
        }
    }
}
