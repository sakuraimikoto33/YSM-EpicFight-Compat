package net.okitsu.ysmepicfightcompat.animation;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExpressionEngineReferenceTest {
    @Test
    void officialCompatibleProbeExpressionsDoNotRequireABareNullLiteral() {
        for (Object absent : new Object[]{null, 0.0D}) {
            TestEnvironment environment = new TestEnvironment();
            environment.query("ysm.projectile_owner", absent);
            environment.query("q.health", 20.0D);
            assertEquals(-90.0D, evaluate(
                    "-90 * ((ysm.projectile_owner->q.health) == 0)", environment));
            assertEquals(0.0D, evaluate("ysm.projectile_owner->q.health", environment));
            assertEquals(0.0D, evaluate("v.probe_bad = 0; ysm.projectile_owner->{ "
                    + "v.probe_bad = 1; }; return v.probe_bad * 90;", environment));
            assertEquals(15.0D, evaluate("15 + (ysm.projectile_owner->q.health)", environment));
            assertEquals(0.0D, environment.variable("v.probe_bad"));
        }
    }

    @Test
    void arrowBindsAboveUnaryAndArithmeticWhileLeavingFollowingTermsInSourceScope() {
        TestEnvironment source = new TestEnvironment();
        TestEnvironment target = new TestEnvironment();
        source.variable("v.target", new Reference(target));
        source.query("q.health", 2.0D);
        target.query("q.health", 9.0D);
        target.query("q.is_alive", 1.0D);

        assertEquals(-3.0D, evaluate("-v.target->q.health+q.health*3", source));
        assertEquals(11.0D, evaluate("+v.target->q.health+q.health", source));
        assertEquals(0.0D, evaluate("!v.target->q.is_alive", source));
        assertEquals(13.0D, evaluate("v.target->(q.health+4)", source));
        assertEquals(1.0D, evaluate("v.target->q.health > q.health", source));
    }

    @Test
    void chainsResolveEachIntermediateHandleInLeftToRightOrder() {
        TestEnvironment source = new TestEnvironment();
        TestEnvironment first = new TestEnvironment();
        TestEnvironment second = new TestEnvironment();
        Reference firstReference = new Reference(first);
        Reference secondReference = new Reference(second);
        source.variable("v.target", firstReference);
        first.query("ysm.projectile_owner", secondReference);
        second.query("q.health", 17.0D);

        assertEquals(17.0D, evaluate("v.target->ysm.projectile_owner->q.health", source));
        assertEquals(1, firstReference.resolves);
        assertEquals(1, secondReference.resolves);
        assertTrue(first.calls.isEmpty());
        assertTrue(second.calls.isEmpty());
    }

    @Test
    void missingStaleAndNonReferenceValuesSkipEveryRightHandSideEffectAndReturnZero() {
        TestEnvironment source = new TestEnvironment();
        TestEnvironment target = new TestEnvironment();
        Reference stale = new Reference(target);
        stale.valid = false;
        Reference unresolved = new Reference(null);
        List<Object> candidates = new ArrayList<>(List.of(0.0D, 1.0D, "entity", new Object(),
                Map.of("entity", 1), List.of(1), stale, unresolved));
        candidates.add(null);
        for (Object candidate : candidates) {
            source.variable("v.target", candidate);
            assertEquals(0.0D, evaluate(
                    "(v.target->{t.touched=1; q.side_effect();}) ?? 99", source));
            assertNull(source.variable("t.touched"));
        }
        assertEquals(0, stale.resolves);
        assertTrue(source.calls.isEmpty());
        assertTrue(target.calls.isEmpty());
        assertEquals(99.0D, evaluate("v.target ?? 99", source));
    }

    @Test
    void referencesAreRevalidatedAfterTheyWereStoredInVariablesAndContainers() {
        TestEnvironment source = new TestEnvironment();
        TestEnvironment target = new TestEnvironment();
        Reference reference = new Reference(target);
        source.variable("v.target", ExpressionEngine.boundedValue(reference));
        source.variable("v.targets", ExpressionEngine.boundedValue(List.of(reference)));
        reference.valid = false;

        assertEquals(3.0D, evaluate("v.target ?? 3", source));
        assertEquals(4.0D, evaluate("v.targets[0] ?? 4", source));
        assertEquals(0.0D, evaluate("v.target->q.side_effect()", source));
        assertEquals(0, reference.resolves);
        assertTrue(target.calls.isEmpty());
    }

    @Test
    void unavailableHostResolversFailClosedWithoutRunningTheTargetExpression() {
        TestEnvironment source = new TestEnvironment();
        source.variable("v.target", new ExpressionEngine.EntityReference() {
            @Override
            public boolean isValid() {
                return true;
            }

            @Override
            public ExpressionEngine.Environment resolve() {
                throw new IllegalStateException("world unloaded");
            }
        });
        assertEquals(0.0D, evaluate("v.target->{t.touched=1;}", source));
        assertNull(source.variable("t.touched"));
        assertNull(ExpressionEngine.boundedValue(new ExpressionEngine.EntityReference() {
            @Override
            public boolean isValid() {
                throw new IllegalStateException("world unloaded");
            }

            @Override
            public ExpressionEngine.Environment resolve() {
                throw new AssertionError("Invalid handle must not resolve");
            }
        }));
    }

    @Test
    void validReferencesHaveIdentityAndTruthWithoutBecomingNumericEntityIds() {
        TestEnvironment source = new TestEnvironment();
        Reference first = new Reference(new TestEnvironment());
        Reference second = new Reference(new TestEnvironment());
        source.variable("v.first", first);
        source.variable("v.alias", first);
        source.variable("v.second", second);

        assertSame(first, value("v.first", source));
        assertEquals(1.0D, evaluate("v.first == v.alias", source));
        assertEquals(0.0D, evaluate("v.first == v.second", source));
        assertEquals(0.0D, evaluate("v.first == 0", source));
        assertEquals(0.0D, evaluate("0 == v.first", source));
        assertEquals(0.0D, evaluate("v.first == null", source));
        assertEquals(1.0D, evaluate("v.first != 0", source));
        assertEquals(1.0D, evaluate("v.first && v.second", source));
        assertEquals(0.0D, evaluate("!v.first", source));
        assertEquals(7.0D, evaluate("v.first ? 7 : 9", source));
        assertEquals(0.0D, ExpressionEngine.number(first));
    }

    @Test
    void onlyValidOpaqueHandlesSurviveTypedContainersAndFunctionArguments() {
        TestEnvironment source = new TestEnvironment();
        TestEnvironment target = new TestEnvironment();
        Reference reference = new Reference(target);
        target.query("q.health", 23.0D);
        source.arguments = new Object[]{Map.of("owner", reference), new Object()};
        source.functions.put("q.echo", arguments -> arguments[0]);
        source.variable("v.nested", Map.of("list", new Object[]{reference, new Object()}));

        assertEquals(23.0D, evaluate("args[0].owner->q.health", source));
        assertEquals(23.0D, evaluate("q.echo(args[0].owner)->q.health", source));
        assertEquals(23.0D, evaluate("v.nested.list[0]->q.health", source));
        assertEquals(23.0D, evaluate("[args[0].owner][0]->q.health", source));
        assertNull(value("args[1]", source));
        assertNull(value("v.nested.list[1]", source));
        assertNull(value("args[0].owner.target", source));
        assertNull(ExpressionEngine.boundedValue(new Object()));
    }

    @Test
    void redirectedVariablesUseTheTargetAndTheSourceScopeIsRestoredAfterward() {
        TestEnvironment source = new TestEnvironment();
        TestEnvironment target = new TestEnvironment();
        source.variable("v.target", new Reference(target));
        source.variable("v.state", 3.0D);
        target.variable("v.state", 11.0D);
        target.variable("v.roaming.flag", 5.0D);
        source.query("q.health", 7.0D);
        target.query("q.health", 17.0D);

        assertEquals(43.0D, evaluate("t.result=v.target->{variable.state"
                + "+variable.roaming.flag+q.health;}; t.result+v.state+q.health", source));
        assertEquals(33.0D, source.variable("t.result"));
        assertEquals(3.0D, source.variable("v.state"));
    }

    @Test
    void tempsContextAndArgumentsRemainInTheOriginalExpressionAcrossNestedReferences() {
        TestEnvironment source = new TestEnvironment();
        TestEnvironment first = new TestEnvironment();
        TestEnvironment second = new TestEnvironment();
        source.variable("v.target", new Reference(first));
        first.query("ysm.projectile_owner", new Reference(second));
        source.variable("t.local", 2.0D);
        first.variable("t.local", 200.0D);
        second.variable("t.local", 300.0D);
        source.query("context.count", 3.0D);
        source.query("c.count", 5.0D);
        source.arguments = new Object[]{7.0D};
        second.query("context.count", 30.0D);
        second.query("c.count", 50.0D);
        second.arguments = new Object[]{70.0D};
        second.query("q.health", 11.0D);

        assertEquals(28.0D, evaluate("v.target->{ysm.projectile_owner->{"
                + "temp.local+=q.health;t.local+context.count+c.count+args[0];};}", source));
        assertEquals(13.0D, source.variable("t.local"));
        assertEquals(200.0D, first.variable("t.local"));
        assertEquals(300.0D, second.variable("t.local"));
    }

    @Test
    void targetFunctionsReceiveTargetArgumentsAndNeverFallBackToTheSourceController() {
        TestEnvironment source = new TestEnvironment();
        TestEnvironment target = new TestEnvironment();
        source.variable("v.target", new Reference(target));
        source.query("q.health", 2.0D);
        target.query("q.health", 13.0D);
        source.functions.put("fn.echo", arguments -> 99.0D);
        target.functions.put("fn.echo", arguments -> arguments[0]);
        source.functions.put("ctrl.set_animation", arguments -> 99.0D);
        target.functions.put("ctrl.set_animation", arguments -> 0.0D);
        source.functions.put("context.sum", arguments -> ExpressionEngine.number(arguments[0]) + 1);
        target.functions.put("context.sum", arguments -> 999.0D);

        assertEquals(13.0D, evaluate("v.target->fn.echo(q.health)", source));
        assertEquals(0.0D, evaluate("v.target->ctrl.set_animation('ignored')", source));
        assertEquals(14.0D, evaluate("v.target->context.sum(q.health)", source));
        assertEquals(List.of("fn.echo", "ctrl.set_animation"), target.calls);
        assertEquals(List.of("context.sum"), source.calls);
    }

    @Test
    void arrowBlocksAllTargetVariableWritesIncludingCompoundStructAndIndexedAssignments() {
        TestEnvironment source = new TestEnvironment();
        TestEnvironment target = new TestEnvironment();
        source.variable("v.target", new Reference(target));
        target.variable("v.value", 3.0D);
        target.variable("v.struct", Map.of("member", 4.0D));
        target.variable("v.list", List.of(5.0D));
        target.variable("v.roaming.flag", 6.0D);

        assertEquals(18.0D, evaluate("v.target->{v.value=100;variable.value+=10;"
                + "v.struct.member=100;v.list[0]=100;variable.roaming.flag=100;"
                + "t.result=v.value+v.struct.member+v.list[0]+v.roaming.flag;t.result;}", source));
        assertEquals(3.0D, target.variable("v.value"));
        assertEquals(Map.of("member", 4.0D), target.variable("v.struct"));
        assertEquals(List.of(5.0D), target.variable("v.list"));
        assertEquals(6.0D, target.variable("v.roaming.flag"));
        assertEquals(18.0D, source.variable("t.result"));
        assertEquals(0, target.writes);
    }

    @Test
    void directArrowAndArrowMemberAssignmentTargetsAreRejectedAtCompileTime() {
        for (String source : List.of("v.target->v.value=1", "(v.target->v.value)+=1",
                "v.target->v.list[0]=1", "(v.target->v.list)[0]=1",
                "for_each(v.target->v.value,[1],{0;})")) {
            assertFalse(ExpressionEngine.compile(source).isValid(), source);
        }
    }

    @Test
    void loopsContinueBreakAndReturnKeepTheirUsualExpressionScopeUnderArrow() {
        TestEnvironment source = new TestEnvironment();
        TestEnvironment target = new TestEnvironment();
        source.variable("v.target", new Reference(target));
        target.query("q.health", 2.0D);

        assertEquals(4.0D, evaluate("t.sum=0;loop(4,{t.index+=1;"
                + "v.target->{t.index==2?continue;t.index==4?break;"
                + "t.sum+=q.health;};});t.sum", source));
        assertEquals(2.0D, evaluate("v.target->{return q.health;};t.unreachable=1", source));
        assertNull(source.variable("t.unreachable"));
        assertTrue(target.calls.isEmpty());
    }

    @Test
    void nestedTargetCallsShareTheOuterOperationBudgetRatherThanRestartingIt() {
        TestEnvironment source = new TestEnvironment();
        TestEnvironment target = new TestEnvironment();
        source.variable("v.target", new Reference(target));
        ExpressionEngine.Expression nested = ExpressionEngine.compile(
                "loop(1024,{t.counter+=1;});1");
        target.functions.put("q.work", arguments -> nested.evaluateValue(target));

        assertEquals(0.0D, evaluate("loop(1024,{v.target->q.work();});t.finished=1", source));
        assertNull(source.variable("t.finished"));
        assertTrue(target.calls.size() > 0);
        assertTrue(target.calls.size() < 10);
        assertTrue(ExpressionEngine.number(target.variable("t.counter")) > 0);
        // A new top-level evaluation starts a fresh budget even after an aborted target call.
        assertEquals(7.0D, evaluate("3+4", source));
    }

    @Test
    void targetResolutionCannotSwallowTheSharedEvaluationLimit() {
        TestEnvironment source = new TestEnvironment();
        source.variable("v.target", new ExpressionEngine.EntityReference() {
            @Override
            public boolean isValid() {
                return true;
            }

            @Override
            public ExpressionEngine.Environment resolve() {
                ExpressionEngine.consumeOperations(ExpressionEngine.MAX_EVALUATION_OPERATIONS);
                return source;
            }
        });

        assertEquals(0.0D, evaluate("v.target->q.health;t.unreachable=1", source));
        assertNull(source.variable("t.unreachable"));
    }

    @Test
    void arrowAndOfficialReferenceSourcesNeverUseNumericWorkerSnapshots() {
        assertTrue(ExpressionEngine.compile("v.owner->q.health").dependencies().writesVariables());
        assertTrue(ExpressionEngine.compile("ysm.projectile_owner").dependencies().writesVariables());
        assertTrue(ExpressionEngine.compile("YSM.PROJECTILE_OWNER()").dependencies().writesVariables());
        assertFalse(ExpressionEngine.compile("q.health+v.offset").dependencies().writesVariables());
    }

    private static double evaluate(String source, TestEnvironment environment) {
        return ExpressionEngine.number(value(source, environment));
    }

    private static Object value(String source, TestEnvironment environment) {
        ExpressionEngine.Expression expression = ExpressionEngine.compile(source);
        assertTrue(expression.isValid(), source + ": " + expression.diagnostic());
        return expression.evaluateValue(environment);
    }

    private static final class Reference implements ExpressionEngine.EntityReference {
        private final TestEnvironment target;
        private boolean valid = true;
        private int resolves;

        private Reference(TestEnvironment target) {
            this.target = target;
        }

        @Override
        public boolean isValid() {
            return valid;
        }

        @Override
        public ExpressionEngine.Environment resolve() {
            resolves++;
            return target;
        }
    }

    private static final class TestEnvironment implements ExpressionEngine.Environment {
        private final Map<Integer, Object> variables = new HashMap<>();
        private final Map<Integer, Object> queries = new HashMap<>();
        private final Map<String, Function<Object[], Object>> functions = new HashMap<>();
        private final List<String> calls = new ArrayList<>();
        private Object[] arguments = new Object[0];
        private int writes;

        private void variable(String name, Object value) {
            variables.put(ExpressionEngine.slot(name), value);
        }

        private Object variable(String name) {
            return variables.get(ExpressionEngine.slot(name));
        }

        private void query(String name, Object value) {
            queries.put(ExpressionEngine.querySlot(name), value);
        }

        @Override
        public double readVariable(int slot) {
            return ExpressionEngine.number(readVariableValue(slot));
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
            writeVariableValue(slot, value);
        }

        @Override
        public void writeVariableValue(int slot, Object value) {
            writes++;
            variables.put(slot, value);
        }

        @Override
        public double readQuery(int slot) {
            return ExpressionEngine.number(readQueryValue(slot));
        }

        @Override
        public Object readQueryValue(int slot) {
            return queries.getOrDefault(slot, 0.0D);
        }

        @Override
        public double invoke(String name, double[] arguments) {
            throw new AssertionError("Typed target invocation expected");
        }

        @Override
        public double invokeWithText(String name, String[] arguments) {
            throw new AssertionError("Typed target invocation expected");
        }

        @Override
        public Object invokeValue(String name, Object[] arguments) {
            calls.add(name);
            return functions.getOrDefault(name, ignored -> 0.0D).apply(arguments);
        }

        @Override
        public Object[] arguments() {
            return arguments;
        }
    }
}
