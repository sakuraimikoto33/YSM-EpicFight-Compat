package net.okitsu.ysmepicfightcompat.animation;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MolangReferenceIntegrationTest {
    private static final double EPSILON = 1.0E-6D;

    @Test
    void customFunctionsKeepOpaqueArgumentsAndReturnValuesWithoutRedirectingTheirCaller() {
        FakeHost host = new FakeHost(Map.of(
                "identity", "return args[0];",
                "combined", "return args[0]->query.health + query.health;"));
        host.target.query("query.health", 7.0D);

        assertSame(host.reference, evaluate("fn.identity(ysm.projectile_owner)", host));
        assertEquals(7.0D, number(evaluate(
                "fn.identity(ysm.projectile_owner)->q.health", host)), EPSILON);
        assertEquals(27.0D, number(evaluate(
                "fn.combined(fn.identity(ysm.projectile_owner))", host)), EPSILON);
        assertEquals(20.0D, number(evaluate("query.health", host)), EPSILON);
    }

    @Test
    void scriptControllersUseReferenceQueriesWithoutLosingTheirOwnPlaybackClock() {
        FakeHost host = new FakeHost(Map.of("main@player_ctrl_main", """
                ctrl.set_animation('pose');
                v.owner_health=ysm.projectile_owner->query.health;
                v.owner_clock=ysm.projectile_owner->query.anim_time;
                v.own_clock=query.anim_time;
                return v.owner_health>0 ? ctrl.state_continue : ctrl.state_pause;
                """));
        host.target.query("query.health", 7.0D);
        host.target.query("query.anim_time", 99.0D);

        assertTrue(host.runtime.controller("player.main", "", 0, 0, host).visible());
        MolangScriptRuntime.Output output = host.runtime.controller("player.main", "", 0, 0.25, host);

        assertTrue(output.visible());
        assertEquals(0.25D, output.elapsed(), EPSILON);
        assertEquals(7.0D, host.number("v.owner_health"), EPSILON);
        assertEquals(99.0D, host.number("v.owner_clock"), EPSILON);
        assertEquals(0.25D, host.number("v.own_clock"), EPSILON);
    }

    @Test
    void bedrockControllerConditionsAndWeightsReceiveFunctionReferenceResults() {
        FakeHost host = new FakeHost(Map.of(
                "owner", "return ysm.projectile_owner;",
                "health", "return args[0]->query.health;"));
        AnimationControllerProgram program = controller("""
                {"animation_controllers":{"player.parallel_0":{"states":{
                  "default":{"animations":["pose"],
                    "transitions":[{"alert":"fn.owner()->query.health>10"}]},
                  "alert":{"animations":[{"alert":"fn.health(fn.owner())/20"}]}
                }}}}
                """);
        AnimationControllerProgram.RuntimeState state = new AnimationControllerProgram.RuntimeState();
        host.target.query("query.health", 5.0D);
        assertEquals("pose", program.select(0, host, state).get(0).name());
        assertEquals("pose", program.select(0.1, host, state).get(0).name());

        host.target.query("query.health", 15.0D);
        List<AnimationControllerProgram.ActiveAnimation> selected = program.select(0.2, host, state);

        assertEquals(1, selected.size());
        assertEquals("alert", selected.get(0).name());
        assertEquals(0.75F, selected.get(0).weight(), EPSILON);
        assertEquals(20.0D, number(evaluate("query.health", host)), EPSILON);
    }

    @Test
    void controllerLocalVariablesRemainLocalWhenAFunctionReadsAnotherEntity() {
        FakeHost host = new FakeHost(Map.of(
                "inspect", "return (args[0]->variable.shared)??7;"));
        host.value("v.shared", 0.1D);
        AnimationControllerProgram program = controller("""
                {"animation_controllers":{"player.parallel_0":{"states":{"default":{
                  "variables":{"shared":{"input":"0.75"}},
                  "on_entry":["v.target_private=fn.inspect(ysm.projectile_owner);v.source_shared=v.shared;"],
                  "animations":[{"pose":"variable.shared"}]
                }}}}}
                """);

        List<AnimationControllerProgram.ActiveAnimation> selected = program.select(
                0, host, new AnimationControllerProgram.RuntimeState());

        assertEquals(7.0D, host.number("v.target_private"), EPSILON);
        assertEquals(0.75D, host.number("v.source_shared"), EPSILON);
        assertEquals(0.1D, host.number("v.shared"), EPSILON);
        assertEquals(0.75F, selected.get(0).weight(), EPSILON);
        assertEquals(0.75D, selected.get(0).stateVariables().get(
                ExpressionEngine.slot("v.shared")), EPSILON);
        assertFalse(host.target.hasVariable(ExpressionEngine.slot("v.shared")));
    }

    @Test
    void invalidStoredReferencesSkipRightHandArgumentsAndPreserveFunctionContext() {
        FakeHost host = new FakeHost(Map.of("inspect", """
                t.calls=0;
                t.result=args[0]->ysm.equipped_enchantment_level(t.calls+=1,'minecraft:flame');
                v.side_effects=t.calls;
                return t.result+query.health;
                """));
        evaluate("v.saved=ysm.projectile_owner;", host);
        host.reference.valid = false;

        assertEquals(20.0D, number(evaluate("fn.inspect(v.saved)", host)), EPSILON);
        assertEquals(0.0D, host.number("v.side_effects"), EPSILON);
        assertEquals(0, host.target.invocations);
        assertEquals(0, host.reference.resolutions);
        assertEquals(20.0D, number(evaluate("query.health", host)), EPSILON);
    }

    private static AnimationControllerProgram controller(String source) {
        Map<String, AnimationController> controllers = BedrockAnimationControllerParser.parse(
                JsonParser.parseString(source).getAsJsonObject());
        return new AnimationControllerProgram(controllers, Map.of(
                "pose", new AnimationControllerProgram.ClipInfo(1, AnimationClip.Playback.REPEAT),
                "alert", new AnimationControllerProgram.ClipInfo(1, AnimationClip.Playback.REPEAT)));
    }

    private static Object evaluate(String source, ExpressionEngine.Environment environment) {
        ExpressionEngine.Expression expression = ExpressionEngine.compile(source);
        assertTrue(expression.isValid(), expression::diagnostic);
        return expression.evaluateValue(environment);
    }

    private static double number(Object value) {
        return ExpressionEngine.number(value);
    }

    private static final class FakeHost implements MolangScriptRuntime.Host {
        private final MolangScriptRuntime runtime;
        private final Map<Integer, Object> values = new HashMap<>();
        private final ReadOnlyTarget target = new ReadOnlyTarget();
        private final FakeReference reference = new FakeReference(target);

        private FakeHost(Map<String, String> sources) {
            runtime = new MolangScriptRuntime(sources, Map.of(
                    "pose", new MolangScriptRuntime.Clip(1, AnimationClip.Playback.REPEAT)));
            values.put(ExpressionEngine.querySlot("query.health"), 20.0D);
            values.put(ExpressionEngine.querySlot("ysm.projectile_owner"), reference);
        }

        private void value(String name, Object value) { values.put(ExpressionEngine.slot(name), value); }
        private double number(String name) { return readVariable(ExpressionEngine.slot(name)); }
        @Override public MolangScriptRuntime scripts() { return runtime; }
        @Override public double readVariable(int slot) { return ExpressionEngine.number(values.get(slot)); }
        @Override public Object readVariableValue(int slot) { return values.get(slot); }
        @Override public boolean hasVariable(int slot) { return values.containsKey(slot); }
        @Override public void writeVariable(int slot, double value) { values.put(slot, value); }
        @Override public void writeVariableValue(int slot, Object value) { values.put(slot, value); }
        @Override public Object readQueryValue(int slot) {
            Object value = runtime.read(ExpressionEngine.slotName(slot), this);
            return value == MolangScriptRuntime.UNHANDLED ? values.get(slot) : value;
        }
        @Override public double readQuery(int slot) { return ExpressionEngine.number(readQueryValue(slot)); }
        @Override public Object invokeValue(String name, Object[] arguments) {
            Object value = runtime.invoke(name, arguments, this);
            return value == MolangScriptRuntime.UNHANDLED ? null : value;
        }
        @Override public double invoke(String name, double[] arguments) { return 0; }
        @Override public double invokeWithText(String name, String[] arguments) { return 0; }
    }

    /** A private-variable-free target, matching the host's read-only reference boundary. */
    private static final class ReadOnlyTarget implements ExpressionEngine.Environment {
        private final Map<Integer, Object> queries = new HashMap<>();
        private int invocations;

        private void query(String name, Object value) { queries.put(ExpressionEngine.querySlot(name), value); }
        @Override public double readVariable(int slot) { return 0; }
        @Override public Object readVariableValue(int slot) { return null; }
        @Override public boolean hasVariable(int slot) { return false; }
        @Override public void writeVariable(int slot, double value) { throw new AssertionError("Target write"); }
        @Override public double readQuery(int slot) { return ExpressionEngine.number(queries.get(slot)); }
        @Override public Object readQueryValue(int slot) { return queries.get(slot); }
        @Override public Object invokeValue(String name, Object[] arguments) { invocations++; return null; }
        @Override public double invoke(String name, double[] arguments) { invocations++; return 0; }
        @Override public double invokeWithText(String name, String[] arguments) { invocations++; return 0; }
    }

    private static final class FakeReference implements ExpressionEngine.EntityReference {
        private final ExpressionEngine.Environment target;
        private boolean valid = true;
        private int resolutions;

        private FakeReference(ExpressionEngine.Environment target) { this.target = target; }
        @Override public boolean isValid() { return valid; }
        @Override public ExpressionEngine.Environment resolve() {
            resolutions++;
            return valid ? target : null;
        }
    }
}
