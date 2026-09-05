package net.okitsu.ysmepicfightcompat.animation;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AdvancedExpressionEngineTest {
    @Test
    void nestedBlockReturnEscapesTheEntireProgram() {
        TypedEnvironment environment = new TypedEnvironment();
        assertEquals(7.0D, evaluate("v.before=1;{true?{return 7;};v.before=2;};v.after=3", environment));
        assertEquals(1.0D, environment.value("v.before"));
        assertFalse(environment.hasVariable(ExpressionEngine.slot("v.after")));
    }

    @Test
    void oneSidedConditionalBlocksAndEmptyStatementsRemainValid() {
        TypedEnvironment environment = new TypedEnvironment();
        assertEquals(4.0D, evaluate(";;false?{v.x=99;};true?{v.x=4;};v.x;;", environment));
    }

    @Test
    void typedReturnPreservesStringsAndLists() {
        TypedEnvironment environment = new TypedEnvironment();
        assertEquals("Case-Sensitive", compile("return 'Case-Sensitive';99").evaluateValue(environment));
        assertEquals(List.of(1.0D, "two", 3.0D),
                compile("return [1,'two',3]").evaluateValue(environment));
    }

    @Test
    void breakAndContinueAreScopedToTheirNearestLoop() {
        TypedEnvironment environment = new TypedEnvironment();
        assertEquals(8.0D, evaluate("v.i=0;v.sum=0;loop(10,{v.i+=1;"
                + "v.i==2?continue;v.i==5?break;v.sum+=v.i;});v.sum", environment));
        assertEquals(33.0D, evaluate("v.sum=0;loop(3,{loop(10,{v.sum+=1;break;});"
                + "v.sum+=10;});v.sum", environment));
    }

    @Test
    void returnEscapesMultipleLoopAndConditionalScopes() {
        TypedEnvironment environment = new TypedEnvironment();
        assertEquals(12.0D, evaluate("loop(20,{loop(20,{true?{return 12;};});});v.after=3", environment));
        assertFalse(environment.hasVariable(ExpressionEngine.slot("v.after")));
    }

    @Test
    void loopsAreCappedAndNegativeCountsDoNothing() {
        TypedEnvironment environment = new TypedEnvironment();
        assertEquals(1024.0D, evaluate("v.count=0;loop(999999,{v.count+=1;});v.count", environment));
        assertEquals(0.0D, evaluate("v.count=0;loop(-1,{v.count+=1;});v.count", environment));
        assertEquals(2.0D, evaluate("v.count=0;loop(2.9,{v.count+=1;});v.count", environment));
    }

    @Test
    void forEachConsumesTypedQueryListsAndStructMembers() {
        TypedEnvironment environment = new TypedEnvironment();
        environment.queries.put("ysm.effects", List.of(Map.of("strength", 2), Map.of("strength", 3)));
        assertEquals(5.0D, evaluate("v.total=0;for_each(v.effect,ysm.effects,"
                + "{v.total+=v.effect.strength;});v.total", environment));
    }

    @Test
    void functionArgumentsAreTypedAndMayBeTraversed() {
        TypedEnvironment environment = new TypedEnvironment();
        environment.arguments = new Object[]{Map.of("x", 5, "items", List.of("first", "second")), 3};
        assertEquals(8.0D, evaluate("args[0].x+args[1]", environment));
        assertEquals("second", compile("return args[0].items[1]").evaluateValue(environment));
        assertEquals(8.0D, evaluate("args[20]??8", environment));
    }

    @Test
    void forEachCanUseFunctionArgumentArrays() {
        TypedEnvironment environment = new TypedEnvironment();
        environment.arguments = new Object[]{2, 3, 5};
        assertEquals(10.0D, evaluate("v.sum=0;for_each(t.value,args,{v.sum+=t.value;});v.sum",
                environment));
    }

    @Test
    void structResultsSupportDirectMemberAccessAndVariableCopies() {
        TypedEnvironment environment = new TypedEnvironment();
        assertEquals(3.0D, evaluate("ysm.bone_rot('Head').x", environment));
        assertEquals(12.0D, evaluate("v.bone=ysm.bone_rot('Head');v.bone.x=12;v.bone.x", environment));
        assertEquals(3.0D, evaluate("ysm.bone_rot('Head').x", environment));
    }

    @Test
    void nestedStructWritesDoNotMutateTheQueryResult() {
        TypedEnvironment environment = new TypedEnvironment();
        environment.queries.put("ysm.struct", Map.of("inner", Map.of("x", 3)));
        assertEquals(9.0D, evaluate("v.struct=ysm.struct;v.struct.inner.x=9;v.struct.inner.x", environment));
        assertEquals(3, ((Map<?, ?>) ((Map<?, ?>) environment.queries.get("ysm.struct")).get("inner")).get("x"));
    }

    @Test
    void flatRoamingVariablesAndNestedStructMembersStayDistinct() {
        TypedEnvironment environment = new TypedEnvironment();
        assertEquals(9.0D, evaluate("variable.roaming.jacket=4;v.roaming.jacket+=5;"
                + "v.roaming.jacket", environment));
        assertEquals(ExpressionEngine.slot("t.test"), ExpressionEngine.slot("temp.test"));
    }

    @Test
    void indexedAssignmentsEvaluateTheIndexOnlyOnce() {
        TypedEnvironment environment = new TypedEnvironment();
        assertEquals(7.0D, evaluate("v.array=[3,4,5];v.i=0;"
                + "v.array[v.i+=1]+=3;v.array[1]", environment));
        assertEquals(1.0D, environment.value("v.i"));
    }

    @Test
    void nestedArrayAssignmentsPropagateToTheRoot() {
        TypedEnvironment environment = new TypedEnvironment();
        assertEquals(12.0D, evaluate("v.array=[[1,2],[3,4]];v.array[1][0]=12;v.array[1][0]", environment));
        assertEquals(7.0D, evaluate("v.array[0][8]=7;v.array[0][8]", environment));
    }

    @Test
    void coalesceEvaluatesIndexedSelectorsOnlyOnce() {
        TypedEnvironment environment = new TypedEnvironment();
        assertEquals(4.0D, evaluate("v.array=[3,4];v.i=0;v.array[v.i+=1]??8", environment));
        assertEquals(1.0D, environment.value("v.i"));
        assertEquals(0.0D, evaluate("v.assigned=0;v.assigned??8", environment));
        assertEquals(8.0D, evaluate("v.assigned=null;v.assigned??8", environment));
    }

    @Test
    void booleanOperatorsAndConditionalBranchesShortCircuitSideEffects() {
        TypedEnvironment environment = new TypedEnvironment();
        assertEquals(0.0D, evaluate("v.calls=0;false&&(v.calls+=1);true||(v.calls+=1);"
                + "true?0:(v.calls+=1);false?(v.calls+=1);v.calls", environment));
    }

    @Test
    void commentsAndEscapedTextDoNotChangeStatementBoundaries() {
        TypedEnvironment environment = new TypedEnvironment();
        assertEquals(5.0D, evaluate("/* { ignored } */v.x=2;// ; return 99\nv.x+=3;v.x", environment));
        assertEquals("it's\nfine", compile("return 'it\\'s\\nfine'").evaluateValue(environment));
        assertEquals(10.25D, evaluate("1e1+.25", environment));
    }

    @Test
    void typedAndLegacyStringComparisonsRemainCompatible() {
        TypedEnvironment environment = new TypedEnvironment();
        environment.queries.put("ysm.texture_name", "Blue");
        assertEquals(1.0D, evaluate("ysm.texture_name=='Blue'", environment));
        assertEquals(0.0D, evaluate("ysm.texture_name=='blue'", environment));
        environment.queries.put("ysm.legacy_texture", ExpressionEngine.number("Blue"));
        assertEquals(1.0D, evaluate("ysm.legacy_texture=='Blue'", environment));
    }

    @Test
    void functionNamesAreCaseInsensitiveButTextArgumentsArePreserved() {
        TypedEnvironment environment = new TypedEnvironment();
        assertEquals("ExactText", compile("FN.IDENTITY('ExactText')").evaluateValue(environment));
    }

    @Test
    void compoundArithmeticAndNonFiniteValuesRemainBounded() {
        TypedEnvironment environment = new TypedEnvironment();
        assertEquals(1.0D, evaluate("v.x=5;v.x*=3;v.x-=2;v.x%=4;v.x/=1;v.x", environment));
        assertEquals(0.0D, evaluate("1e999", environment));
        assertEquals(0.0D, evaluate("1/0", environment));
    }

    @Test
    void malformedInputsFailAsAWholeAndExposeDiagnostics() {
        for (String source : List.of("v.x=1;bad@token", "'unterminated", "1 /* unterminated",
                "return 2 garbage", "break", "continue", "loop(2,{", "q.x=3", "args[0]=3",
                "ysm.bone_rot('Head').x=2", "1e+")) {
            TypedEnvironment environment = new TypedEnvironment();
            ExpressionEngine.Expression expression = ExpressionEngine.compile(source);
            assertFalse(expression.isValid(), source);
            assertFalse(expression.diagnostic().isBlank(), source);
            assertEquals(0.0D, expression.evaluate(environment), source);
            assertFalse(environment.hasVariable(ExpressionEngine.slot("v.x")), source);
        }
    }

    @Test
    void sourceAndAstLimitsRejectWithoutStackOverflow() {
        assertFalse(ExpressionEngine.compile(" ".repeat(ExpressionEngine.MAX_SOURCE_LENGTH) + "1").isValid());
        assertFalse(ExpressionEngine.compile("(".repeat(200) + "1" + ")".repeat(200)).isValid());
        assertFalse(ExpressionEngine.compile("1+".repeat(200) + "1").isValid());
    }

    @Test
    void nestedLoopsExhaustOneSharedBudgetAndTheNextEvaluationRecovers() {
        TypedEnvironment environment = new TypedEnvironment();
        assertEquals(0.0D, evaluate("v.count=0;loop(1024,{loop(1024,{v.count+=1;});});999", environment));
        assertTrue(environment.value("v.count") < 1_048_576.0D);
        assertEquals(7.0D, evaluate("7", environment));
    }

    @Test
    void nestedCompiledFunctionsCannotResetTheOuterInstructionBudget() {
        TypedEnvironment environment = new TypedEnvironment();
        assertEquals(0.0D, evaluate("v.calls=0;loop(1024,{fn.expensive();});999", environment));
        assertTrue(environment.value("v.calls") < 1024.0D);
        assertEquals(3.0D, evaluate("3", environment));
    }

    @Test
    void externalEventBoundaryCanShareTheBudgetAndObserveExhaustion() {
        TypedEnvironment environment = new TypedEnvironment();
        try (ExpressionEngine.EvaluationScope scope = ExpressionEngine.beginEvaluation()) {
            ExpressionEngine.consumeOperations(ExpressionEngine.MAX_EVALUATION_OPERATIONS - 1);
            assertEquals(1.0D, evaluate("1", environment));
            assertThrows(ExpressionEngine.EvaluationLimitException.class, () -> evaluate("2", environment));
        }
        assertEquals(2.0D, evaluate("2", environment));
    }

    @Test
    void recursivelyNestedCompiledProgramsHaveAnIndependentStackDepthGuard() {
        TypedEnvironment environment = new TypedEnvironment();
        assertEquals(0.0D, evaluate("fn.recursive()", environment));
        assertEquals(2.0D, evaluate("2", environment));
    }

    @Test
    void typedCollectionsAreImmutableBoundedAndCannotRetainArbitraryObjects() {
        List<Object> source = new ArrayList<>();
        for (int index = 0; index < 2000; index++) {
            source.add(index);
        }
        Object bounded = ExpressionEngine.boundedValue(source);
        assertEquals(1024, ((List<?>) bounded).size());
        source.set(0, "changed");
        assertEquals(0.0D, ((List<?>) bounded).get(0));
        assertThrows(UnsupportedOperationException.class, () -> ((List<?>) bounded).clear());
        assertNull(ExpressionEngine.boundedValue(new Object()));
        List<Object> cyclic = new ArrayList<>();
        cyclic.add(cyclic);
        assertNotNull(ExpressionEngine.boundedValue(cyclic));
    }

    @Test
    void advancedContextProgramsAreNeverDeclaredSafeNumericSnapshots() {
        for (String source : List.of("return 1", "loop(2,{1;})", "args[0]", "fn.named",
                "fn.named()", "ctrl.is_playing()", "v.struct.x", "[1,2][0]")) {
            assertTrue(compile(source).dependencies().writesVariables(), source);
        }
        assertFalse(compile("math.sin(q.anim_time)+v.wind").dependencies().writesVariables());
        for (String query : List.of("ysm.texture_name", "ysm.dimension_name", "ysm.entity_type",
                "ysm.left_shoulder_parrot_variant", "ysm.right_shoulder_parrot_variant",
                "ysm.hit_target_id", "ysm.hit_target_type")) {
            assertTrue(compile(query).dependencies().hasTextArguments(), query);
        }
    }

    private static ExpressionEngine.Expression compile(String source) {
        ExpressionEngine.Expression expression = ExpressionEngine.compile(source);
        assertTrue(expression.isValid(), () -> source + ": " + expression.diagnostic());
        return expression;
    }

    private static double evaluate(String source, TypedEnvironment environment) {
        return compile(source).evaluate(environment);
    }

    private static final class TypedEnvironment implements ExpressionEngine.Environment {
        private final Map<Integer, Object> values = new HashMap<>();
        private final Map<String, Object> queries = new HashMap<>();
        private Object[] arguments = new Object[0];

        double value(String name) {
            return readVariable(ExpressionEngine.slot(name));
        }

        @Override
        public double readVariable(int slot) {
            return ExpressionEngine.number(readVariableValue(slot));
        }

        @Override
        public Object readVariableValue(int slot) {
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
        public void writeVariableValue(int slot, Object value) {
            values.put(slot, ExpressionEngine.boundedValue(value));
        }

        @Override
        public double readQuery(int slot) {
            return ExpressionEngine.number(readQueryValue(slot));
        }

        @Override
        public Object readQueryValue(int slot) {
            return queries.getOrDefault(ExpressionEngine.slotName(slot), 0.0D);
        }

        @Override
        public double invoke(String name, double[] arguments) {
            return 0.0D;
        }

        @Override
        public double invokeWithText(String name, String[] arguments) {
            return 0.0D;
        }

        @Override
        public Object invokeValue(String name, Object[] arguments) {
            return switch (name) {
                case "ysm.bone_rot" -> Map.of("x", 3, "y", 4, "z", 5);
                case "fn.identity" -> arguments[0];
                case "fn.expensive" -> ExpressionEngine.compile(
                        "v.calls+=1;loop(100,{v.inner+=1;});return 1").evaluateValue(this);
                case "fn.recursive" -> ExpressionEngine.compile("fn.recursive()")
                        .evaluateValue(this);
                default -> ExpressionEngine.Environment.super.invokeValue(name, arguments);
            };
        }

        @Override
        public Object[] arguments() {
            return arguments;
        }
    }
}
