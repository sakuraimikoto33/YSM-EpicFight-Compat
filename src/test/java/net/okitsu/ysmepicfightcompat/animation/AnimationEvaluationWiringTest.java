package net.okitsu.ysmepicfightcompat.animation;

import net.minecraft.world.InteractionHand;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Checks the actual entity entry point without constructing Minecraft entities or touching GL. */
class AnimationEvaluationWiringTest {
    private static final String ROOT = "net/okitsu/ysmepicfightcompat/animation/";
    private static final String PROGRAM = ROOT + "ParallelAnimationProgram";
    private static final String STATE = PROGRAM + "$RuntimeState";
    private static final String LIMITER = ROOT + "AnimationEvaluationRateLimiter";
    private static final String PUBLIC_SAMPLE = "(Lnet/minecraft/world/entity/LivingEntity;FZLjava/lang/Float;ZL"
            + ROOT + "MovementAnimationType;Z)L" + PROGRAM + "$Frame;";
    private static final String SAMPLE = "(Lnet/minecraft/world/entity/LivingEntity;FZLjava/lang/Float;ZL"
            + ROOT + "MovementAnimationType;ZZ)L" + PROGRAM + "$Frame;";

    @Test
    void publicDrawsAndTickOutputsEnterTheSameSamplerWithDistinctModes() throws IOException {
        Code rendered = read(PROGRAM, "sample", PUBLIC_SAMPLE);
        int renderedSample = rendered.call(PROGRAM, "sample");
        assertTrue((rendered.access & Opcodes.ACC_PUBLIC) != 0);
        assertEquals(1, rendered.calls(PROGRAM, "sample"));
        assertEquals(SAMPLE, rendered.descriptors.get(renderedSample));
        assertEquals(Opcodes.ICONST_0, rendered.steps.get(renderedSample - 1).opcode);
        assertEquals(Opcodes.ARETURN, rendered.steps.get(renderedSample + 1).opcode);
        assertTrue((sample().access & Opcodes.ACC_PRIVATE) != 0);

        Code output = read(PROGRAM, "advanceOutputs",
                "(Lnet/minecraft/world/entity/LivingEntity;ZZ)V");
        int outputSample = output.call(PROGRAM, "sample");
        assertEquals(1, output.calls(PROGRAM, "sample"));
        assertEquals(SAMPLE, output.descriptors.get(outputSample));
        assertEquals(Opcodes.ICONST_1, output.steps.get(outputSample - 1).opcode);
        assertEquals(Opcodes.ICONST_0, output.steps.get(outputSample - 2).opcode);
        assertEquals(Opcodes.ACONST_NULL, output.steps.get(outputSample - 3).opcode);
        int policy = output.call(PROGRAM, "shouldAdvanceOutputs");
        assertTrue(policy < outputSample);
        assertEquals("(IIII)Z", output.descriptors.get(policy));
        assertEquals(1, output.calls(PROGRAM, "shouldAdvanceOutputs"));
        assertTrue(output.call("net/okitsu/ysmepicfightcompat/config/ClientPreferences",
                "animationEvaluationRateLimitHz") < policy);
        assertTrue(output.steps.get(policy - 1).matches(Opcodes.GETFIELD, STATE, "lastRenderTick"));
        assertTrue(output.steps.get(policy - 3).matches(Opcodes.GETFIELD, STATE, "lastTickCount"));
        assertEquals(Opcodes.IFNE, output.steps.get(policy + 1).opcode);
        assertEquals(Opcodes.RETURN, output.steps.get(policy + 2).opcode);
        assertTrue(output.label(output.steps.get(policy + 1).target) > policy + 2);
        assertTrue(output.label(output.steps.get(policy + 1).target) < outputSample);
    }

    @Test
    void everyWorldDrawObservesItsTickBeforeAdmissionButOutputsAndPreviewsDoNot()
            throws IOException {
        Code code = sample();
        int marker = code.field(Opcodes.PUTFIELD, STATE, "lastRenderTick");
        assertTrue(code.call(STATE, "reset") < marker);
        assertTrue(marker < code.call(LIMITER, "shouldEvaluate"));
        assertTrue(marker < code.field(Opcodes.PUTFIELD, STATE, "lastTickCount"));
        assertEquals(1, code.steps.stream().filter(step ->
                step.matches(Opcodes.PUTFIELD, STATE, "lastRenderTick")).count());
        assertEquals(Opcodes.GETFIELD, code.steps.get(marker - 1).opcode);
        assertEquals("tickCount", code.steps.get(marker - 1).name);
        int previewGuard = code.previousOpcode(marker, Opcodes.IFNE);
        int outputGuard = code.previousOpcode(previewGuard, Opcodes.IFNE);
        Step outputLoad = code.steps.get(outputGuard - 1);
        Step previewLoad = code.steps.get(previewGuard - 1);
        assertEquals(Opcodes.ILOAD, outputLoad.opcode);
        assertEquals(8, outputLoad.variable, "The private sampler's final argument is outputOnly");
        assertEquals(Opcodes.ILOAD, previewLoad.opcode);
        assertTrue(previewLoad.variable > 8, "The other guard must use the computed preview local");
        assertEquals(code.label(code.steps.get(outputGuard).target),
                code.label(code.steps.get(previewGuard).target));
        assertTrue(code.label(code.steps.get(outputGuard).target) > marker);
    }

    @Test
    void liveSampleObservesSelectionBeforeGatingAndDoesNotAdvanceClocksOnSkippedDraws()
            throws IOException {
        Code code = sample();
        int selection = code.call(ROOT + "AutomaticAnimationSelector", "select");
        int gate = code.call(LIMITER, "shouldEvaluate");
        assertTrue(selection < gate);
        assertTrue(gate < code.field(Opcodes.PUTFIELD, STATE, "lastNow"));
        assertTrue(gate < code.field(Opcodes.PUTFIELD, STATE, "lastTickCount"));
        for (String[] target : List.of(
                new String[]{ROOT + "EntityAnimationEnvironment", "update"},
                new String[]{ROOT + "MolangScriptRuntime", "frame"},
                new String[]{ROOT + "AnimationControllerProgram", "selectObserved"},
                new String[]{PROGRAM, "evaluate"})) {
            assertTrue(gate < code.call(target[0], target[1]), target[1]);
        }
        Step admitted = code.steps.get(gate + 1);
        assertEquals(Opcodes.IFNE, admitted.opcode);
        int admittedStart = code.label(admitted.target);
        List<Step> skipped = code.steps.subList(gate + 2, admittedStart);
        assertEquals(1, skipped.stream().filter(step ->
                step.matches(Opcodes.INVOKESTATIC, PROGRAM, "reusableFrame")).count());
        assertEquals(Opcodes.ARETURN, skipped.get(skipped.size() - 1).opcode);
        assertFalse(skipped.stream().anyMatch(step -> step.opcode == Opcodes.PUTFIELD
                || step.opcode == Opcodes.INVOKEVIRTUAL
                || step.opcode == Opcodes.INVOKESTATIC && !"reusableFrame".equals(step.name)));
    }

    @Test
    void skippedDrawMayPublishACompletedWorkerWithoutAdvancingAnimationOrConsumingTime()
            throws IOException {
        Code code = read(PROGRAM, "reusableFrame", "(L" + STATE + ";)L" + PROGRAM + "$Frame;");
        int collect = code.call(PROGRAM, "collectCompletedEvaluation");
        int invalidation = code.field(Opcodes.PUTFIELD, STATE, "boneQuerySampledAt");
        assertEquals(1, code.calls(PROGRAM, "collectCompletedEvaluation"));
        assertEquals(1, code.steps.stream().filter(step -> step.opcode == Opcodes.INVOKESTATIC
                || step.opcode == Opcodes.INVOKEVIRTUAL || step.opcode == Opcodes.INVOKEINTERFACE
                || step.opcode == Opcodes.INVOKESPECIAL).count());
        assertTrue(collect < invalidation);
        int unchanged = code.previousOpcode(invalidation, Opcodes.IF_ACMPEQ);
        assertTrue(unchanged > collect);
        assertTrue(code.label(code.steps.get(unchanged).target) > invalidation);
        assertEquals(1, code.steps.stream().filter(step -> step.opcode == Opcodes.PUTFIELD).count());
        assertFalse(code.steps.stream().anyMatch(step -> LIMITER.equals(step.owner)
                || (ROOT + "EntityAnimationEnvironment").equals(step.owner)
                || (ROOT + "MolangScriptRuntime").equals(step.owner)
                || (ROOT + "AnimationControllerProgram").equals(step.owner)));
        assertFalse(code.steps.stream().anyMatch(step -> "evaluate".equals(step.name)
                || "scheduleEvaluation".equals(step.name)
                || "lastNow".equals(step.name) || "lastTickCount".equals(step.name)));
        assertEquals(Opcodes.ARETURN, code.steps.get(code.steps.size() - 1).opcode);
        assertTrue(code.steps.get(code.steps.size() - 2)
                .matches(Opcodes.GETFIELD, STATE, "publishedFrame"));
    }

    @Test
    void pendingInputsAndAuthoredEndpointsBypassTheGateOnlyWhenTheNewCapIsEnabled()
            throws IOException {
        Code code = sample();
        int pending = code.call(ROOT + "MolangScriptRuntime", "hasPendingSyncs");
        int itemSwitch = code.call(PROGRAM + "$ItemSwitchState", "needsEvaluation");
        int swing = code.call(PROGRAM + "$FullBodySwingState", "needsEvaluation");
        int ending = code.call(PROGRAM, "fullBodyEndingWeight");
        int roulette = code.field(Opcodes.GETFIELD, STATE, "rouletteStopSent");
        int once = code.field(Opcodes.GETSTATIC, ROOT + "AnimationClip$Playback", "ONCE");
        int gate = code.call(LIMITER, "shouldEvaluate");
        for (int check : new int[]{pending, itemSwitch, swing, ending, roulette, once}) {
            assertTrue(check < gate);
        }
        int enabledGuard = code.previousOpcode(pending, Opcodes.IFLE);
        Step rateLoad = code.steps.get(enabledGuard - 1);
        assertEquals(Opcodes.ILOAD, rateLoad.opcode);
        int configured = code.call("net/okitsu/ysmepicfightcompat/config/ClientPreferences",
                "animationEvaluationRateLimitHz");
        Step rateStore = code.steps.get(configured + 1);
        assertEquals(Opcodes.ISTORE, rateStore.opcode);
        assertEquals(rateStore.variable, rateLoad.variable);
        int disabled = code.label(code.steps.get(enabledGuard).target);
        assertTrue(disabled > once && disabled < gate);
        assertEquals(Opcodes.ICONST_0, code.steps.get(disabled).opcode);
    }

    @Test
    void successfulSampleCommitsTheAdmissionOnlyAfterEvaluationAndImmediatelyBeforeReturning()
            throws IOException {
        Code code = sample();
        int committed = code.call(LIMITER, "evaluated");
        assertEquals(1, code.calls(LIMITER, "evaluated"));
        assertTrue(committed > code.lastCall(PROGRAM, "evaluate"));
        assertTrue(committed > code.call(PROGRAM, "scheduleEvaluation"));
        assertTrue(committed > code.lastField(Opcodes.PUTFIELD, STATE, "publishedFrame"));
        List<Step> tail = code.steps.subList(committed + 1, code.steps.size());
        assertEquals(3, tail.size());
        assertEquals(Opcodes.ALOAD, tail.get(0).opcode);
        assertTrue(tail.get(1).matches(Opcodes.GETFIELD, STATE, "publishedFrame"));
        assertEquals(Opcodes.ARETURN, tail.get(2).opcode);
        assertEquals(2, code.steps.stream().filter(step -> step.opcode == Opcodes.ARETURN).count());
    }

    @Test
    void freshContextDiscardsAnOldWorkerBeforeCollectionAndForcesSynchronousEvaluation()
            throws IOException {
        Code code = sample();
        int gate = code.call(LIMITER, "shouldEvaluate");
        int discard = code.call(PROGRAM, "discardPendingEvaluation");
        int collect = code.call(PROGRAM, "collectCompletedEvaluation");
        assertTrue(gate < discard && discard < collect);
        assertTrue(code.call(LIMITER, "currentContextMatches") < discard);
        int freshGuard = code.previousOpcode(discard, Opcodes.IFEQ);
        Step freshLoad = code.steps.get(freshGuard - 1);
        assertEquals(Opcodes.ILOAD, freshLoad.opcode);
        assertTrue(code.label(code.steps.get(freshGuard).target) > discard);

        int workerGuard = -1;
        for (int index = collect + 1; index < code.call(PROGRAM, "evaluate") - 1; index++) {
            Step load = code.steps.get(index);
            Step branch = code.steps.get(index + 1);
            if (load.opcode == Opcodes.ILOAD && load.variable == freshLoad.variable
                    && branch.opcode == Opcodes.IFNE) {
                workerGuard = index + 1;
                break;
            }
        }
        assertTrue(workerGuard >= 0, "Worker eligibility must test the same fresh-context local");
        int ineligible = code.label(code.steps.get(workerGuard).target);
        assertTrue(ineligible > code.call(PROGRAM, "asyncSafe"));
        assertEquals(Opcodes.ICONST_0, code.steps.get(ineligible).opcode);
        Step workerStore = code.steps.get(ineligible + 1);
        assertEquals(Opcodes.ISTORE, workerStore.opcode);
        Step workerLoad = code.steps.get(ineligible + 2);
        assertEquals(Opcodes.ILOAD, workerLoad.opcode);
        assertEquals(workerStore.variable, workerLoad.variable);
        assertEquals(Opcodes.IFNE, code.steps.get(ineligible + 3).opcode);
        assertEquals(PROGRAM, code.steps.get(ineligible + 5).owner);
        assertEquals("discardPendingEvaluation", code.steps.get(ineligible + 5).name);
    }

    @Test
    void runtimeResetClearsLimiterStateAlongsideTheAnimationClocks() throws IOException {
        Code code = read(STATE, "reset", "(D)V");
        assertEquals(1, code.calls(LIMITER, "reset"));
        assertTrue(code.call(LIMITER, "reset") < code.field(Opcodes.PUTFIELD, STATE, "lastNow"));
        assertTrue(code.call(LIMITER, "reset") < code.field(Opcodes.PUTFIELD, STATE, "publishedFrame"));
        int renderTick = code.field(Opcodes.PUTFIELD, STATE, "lastRenderTick");
        assertTrue(code.call(LIMITER, "reset") < renderTick);
        assertEquals(Opcodes.LDC, code.steps.get(renderTick - 1).opcode);
        assertEquals(Integer.MIN_VALUE, code.constants.get(renderTick - 1));
    }

    @Test
    void liveDeltaPreservesIntentionalWaitsAndRetainsTheDisabledModeStallClamp()
            throws IOException {
        Code code = sample();
        assertTrue(code.call(LIMITER, "shouldEvaluate") < code.call(PROGRAM, "evaluationDeltaTime"));
        assertEquals(1.01, ParallelAnimationProgram.evaluationDeltaTime(1.01, 0, 1), 1.0E-9);
        assertEquals(0.04, ParallelAnimationProgram.evaluationDeltaTime(0.04, 0, 30), 1.0E-9);
        assertEquals(0.25, ParallelAnimationProgram.evaluationDeltaTime(1.01, 0, 0), 1.0E-9);
        assertEquals(0, ParallelAnimationProgram.evaluationDeltaTime(1.01, -1, 1), 1.0E-9);
        assertEquals(0, ParallelAnimationProgram.evaluationDeltaTime(1, 2, 1), 1.0E-9);
        assertEquals(1.25, ParallelAnimationProgram.evaluationDeltaTime(10, 0, 1), 1.0E-9);
    }

    @Test
    void discreteInputsAndConfigurationTokensInvalidateContextEquality() {
        Set<InteractionHand> main = Set.of(InteractionHand.MAIN_HAND);
        Set<InteractionHand> off = Set.of(InteractionHand.OFF_HAND);
        Object token = new Object();
        ParallelAnimationProgram.EvaluationContext original = context(
                false, false, null, 7, main, main, main, token);
        assertEquals(original, context(false, false, null, 7,
                Set.copyOf(main), Set.copyOf(main), Set.copyOf(main), token));
        for (ParallelAnimationProgram.EvaluationContext changed : List.of(
                context(true, false, null, 7, main, main, main, token),
                context(false, true, null, 7, main, main, main, token),
                context(false, false, MovementAnimationType.RUN, 7, main, main, main, token),
                context(false, false, null, 8, main, main, main, token),
                context(false, false, null, 7, off, main, main, token),
                context(false, false, null, 7, main, off, main, token),
                context(false, false, null, 7, main, main, off, token),
                context(false, false, null, 7, main, main, main, new Object()))) {
            assertNotEquals(original, changed);
        }
    }

    @Test
    void modelYawReferenceAvailabilityRejectsMissingAndNonFiniteValues() {
        assertFalse(ParallelAnimationProgram.hasModelYawReference(null));
        assertFalse(ParallelAnimationProgram.hasModelYawReference(Float.NaN));
        assertFalse(ParallelAnimationProgram.hasModelYawReference(Float.POSITIVE_INFINITY));
        assertFalse(ParallelAnimationProgram.hasModelYawReference(Float.NEGATIVE_INFINITY));
        assertTrue(ParallelAnimationProgram.hasModelYawReference(0.0F));
        assertTrue(ParallelAnimationProgram.hasModelYawReference(45.0F));
        assertTrue(ParallelAnimationProgram.hasModelYawReference(-135.0F));
    }

    @Test
    void aRealBowDrawCannotReuseTheTickOutputsUnreferencedPose() {
        Object token = new Object();
        ParallelAnimationProgram.EvaluationContext output = bowContext(null, token);
        ParallelAnimationProgram.EvaluationContext rendered = bowContext(45.0F, token);
        ParallelAnimationProgram.EvaluationContext turned = bowContext(-30.0F, token);
        AnimationEvaluationRateLimiter<ParallelAnimationProgram.EvaluationContext> limiter =
                new AnimationEvaluationRateLimiter<>();
        assertTrue(limiter.shouldEvaluate(0, 60, output, false));
        limiter.evaluated(0, 60, output);
        assertNotEquals(output, rendered);
        assertTrue(limiter.shouldEvaluate(0.001, 60, rendered, false));
        limiter.evaluated(0.001, 60, rendered);
        assertFalse(limiter.shouldEvaluate(0.002, 60, rendered, false));
        assertEquals(rendered, turned, "Continuous yaw changes must not defeat the configured cadence");
        assertFalse(limiter.shouldEvaluate(0.002, 60, turned, false));
        assertTrue(limiter.shouldEvaluate(0.003, 60, output, false));
        limiter.evaluated(0.003, 60, output);
        assertTrue(limiter.shouldEvaluate(0.004, 60, rendered, false));
        assertTrue(limiter.shouldEvaluate(0.004, 0, rendered, false));
    }

    @Test
    void liveContextCapturesReferenceAvailabilityBeforeAdmissionButUnlimitedModeBypassesIt()
            throws IOException {
        Code code = sample();
        int reference = code.call(PROGRAM, "hasModelYawReference");
        int context = code.call(PROGRAM + "$EvaluationContext", "<init>");
        int gate = code.call(LIMITER, "shouldEvaluate");
        assertTrue(reference < context && context < gate);
        assertEquals(1, code.calls(PROGRAM, "hasModelYawReference"));
        int enabledGuard = code.previousOpcode(reference, Opcodes.IFGT);
        int enabledStart = code.label(code.steps.get(enabledGuard).target);
        assertTrue(enabledStart < reference);
        int configured = code.call("net/okitsu/ysmepicfightcompat/config/ClientPreferences",
                "animationEvaluationRateLimitHz");
        Step rateStore = code.steps.get(configured + 1);
        Step rateLoad = code.steps.get(enabledGuard - 1);
        assertEquals(Opcodes.ISTORE, rateStore.opcode);
        assertEquals(Opcodes.ILOAD, rateLoad.opcode);
        assertEquals(rateStore.variable, rateLoad.variable);
        List<Step> disabled = code.steps.subList(enabledGuard + 1, enabledStart);
        assertEquals(Opcodes.ACONST_NULL, disabled.get(0).opcode);
        Step bypass = disabled.get(disabled.size() - 1);
        assertEquals(Opcodes.GOTO, bypass.opcode);
        assertTrue(code.label(bypass.target) > context && code.label(bypass.target) < gate);
    }

    @Test
    void stowingAndReturningWeaponsInvalidateTheCappedFrameInBothViews() throws IOException {
        Code code = sample();
        assertTrue(code.call("net/okitsu/ysmepicfightcompat/integration/parcool/EpicParCoolAnimationAccess",
                "fastRunToolsOnBack") < code.call(LIMITER, "shouldEvaluate"));
        for (boolean firstPerson : List.of(false, true)) {
            ParallelAnimationProgram.EvaluationContext holding = fastRunContext(firstPerson, false);
            ParallelAnimationProgram.EvaluationContext stowed = fastRunContext(firstPerson, true);
            AnimationEvaluationRateLimiter<ParallelAnimationProgram.EvaluationContext> limiter =
                    new AnimationEvaluationRateLimiter<>();
            limiter.evaluated(0.0D, 60, holding);
            assertFalse(limiter.shouldEvaluate(0.001D, 60, holding, false));
            assertTrue(limiter.shouldEvaluate(0.001D, 60, stowed, false));
            limiter.evaluated(0.001D, 60, stowed);
            assertFalse(limiter.shouldEvaluate(0.002D, 60, stowed, false));
            assertTrue(limiter.shouldEvaluate(0.002D, 60, holding, false));
        }
    }

    private static ParallelAnimationProgram.EvaluationContext fastRunContext(
            boolean firstPerson, boolean toolsOnBack) {
        return new ParallelAnimationProgram.EvaluationContext(firstPerson, false, false,
                null, List.of("parcool:fast_running", "hold_mainhand:sword"),
                "parcool:fast_running", null, ModAnimationType.PARCOOL,
                OfficialRoamingVariables.RouletteState.NONE,
                Set.of(), Set.of(), Set.of(), true, false, toolsOnBack,
                true, true, null, true, null);
    }

    @Test
    void contextOwnsImmutableCollectionSnapshotsAndHasNoContinuouslyAdvancingClockFields() {
        List<String> clips = new ArrayList<>(List.of("idle"));
        Set<InteractionHand> hands = EnumSet.of(InteractionHand.MAIN_HAND);
        ParallelAnimationProgram.EvaluationContext context = new ParallelAnimationProgram.EvaluationContext(
                false, false, false, null, clips, "idle", MovementAnimationType.WALK, null,
                new OfficialRoamingVariables.RouletteState("", false, 0),
                hands, hands, hands, true, false, false, true, false, null, true, new Object());
        clips.clear();
        hands.clear();
        assertEquals(List.of("idle"), context.clips());
        assertEquals(Set.of(InteractionHand.MAIN_HAND), context.replacementHands());
        assertEquals(Set.of(InteractionHand.MAIN_HAND), context.activeReplacementHands());
        assertEquals(Set.of(InteractionHand.MAIN_HAND), context.itemAnimationHands());
        List<RecordComponent> components = Arrays.asList(
                ParallelAnimationProgram.EvaluationContext.class.getRecordComponents());
        assertFalse(components.stream().anyMatch(component -> component.getType() == double.class
                || component.getType() == float.class));
        assertFalse(components.stream().map(RecordComponent::getName).anyMatch(name ->
                name.contains("elapsed") || name.contains("partialTick")
                        || name.contains("Yaw") && !name.equals("modelYawAvailable")));
        assertTrue(components.stream().anyMatch(component -> component.getName().equals("modelYawAvailable")
                && component.getType() == boolean.class));
    }

    private static ParallelAnimationProgram.EvaluationContext bowContext(Float modelYaw, Object token) {
        Set<InteractionHand> hands = Set.of(InteractionHand.MAIN_HAND);
        return new ParallelAnimationProgram.EvaluationContext(false,
                ParallelAnimationProgram.hasModelYawReference(modelYaw), false, null,
                List.of("use_mainhand:bow"), "idle", null, null,
                new OfficialRoamingVariables.RouletteState("", false, 0),
                hands, hands, hands, true, false, false, true, false, null, true, token);
    }

    private static ParallelAnimationProgram.EvaluationContext context(
            boolean firstPerson, boolean action, MovementAnimationType renderedMovement,
            long rouletteGeneration, Set<InteractionHand> replacement,
            Set<InteractionHand> activeReplacement, Set<InteractionHand> itemAnimation, Object token) {
        return new ParallelAnimationProgram.EvaluationContext(firstPerson, false, action, renderedMovement,
                List.of("idle"), "idle", MovementAnimationType.WALK, null,
                new OfficialRoamingVariables.RouletteState("dance", true, rouletteGeneration),
                replacement, activeReplacement, itemAnimation, true, false, false,
                true, false, null, true, token);
    }

    private static Code sample() throws IOException {
        return read(PROGRAM, "sample", SAMPLE);
    }

    private static Code read(String owner, String methodName, String methodDescriptor) throws IOException {
        Code code = new Code();
        try (InputStream input = AnimationEvaluationWiringTest.class.getClassLoader()
                .getResourceAsStream(owner + ".class")) {
            assertNotNull(input);
            new ClassReader(input).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                 String signature, String[] exceptions) {
                    if (!methodName.equals(name) || !methodDescriptor.equals(descriptor)) return null;
                    code.access = access;
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override public void visitLabel(Label label) { code.labels.put(label, code.steps.size()); }
                        @Override public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
                            code.steps.add(new Step(opcode, owner, name, -1, null));
                        }
                        @Override public void visitMethodInsn(int opcode, String owner, String name,
                                                              String descriptor, boolean isInterface) {
                            code.descriptors.put(code.steps.size(), descriptor);
                            code.steps.add(new Step(opcode, owner, name, -1, null));
                        }
                        @Override public void visitLdcInsn(Object value) {
                            code.constants.put(code.steps.size(), value);
                            code.steps.add(new Step(Opcodes.LDC, null, null, -1, null));
                        }
                        @Override public void visitInsn(int opcode) {
                            code.steps.add(new Step(opcode, null, null, -1, null));
                        }
                        @Override public void visitVarInsn(int opcode, int variable) {
                            code.steps.add(new Step(opcode, null, null, variable, null));
                        }
                        @Override public void visitJumpInsn(int opcode, Label label) {
                            code.steps.add(new Step(opcode, null, null, -1, label));
                        }
                    };
                }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }
        assertFalse(code.steps.isEmpty(), methodName + methodDescriptor);
        return code;
    }

    private record Step(int opcode, String owner, String name, int variable, Label target) {
        boolean matches(int expectedOpcode, String expectedOwner, String expectedName) {
            return opcode == expectedOpcode && expectedOwner.equals(owner) && expectedName.equals(name);
        }
    }

    private static final class Code {
        private final List<Step> steps = new ArrayList<>();
        private final Map<Label, Integer> labels = new IdentityHashMap<>();
        private final Map<Integer, String> descriptors = new java.util.HashMap<>();
        private final Map<Integer, Object> constants = new java.util.HashMap<>();
        private int access;

        int label(Label target) {
            Integer index = labels.get(target);
            assertNotNull(index, "Missing jump target");
            return index;
        }
        int call(String owner, String name) { return index(owner, name, -1, false); }
        int lastCall(String owner, String name) { return index(owner, name, -1, true); }
        int field(int opcode, String owner, String name) { return index(owner, name, opcode, false); }
        int lastField(int opcode, String owner, String name) { return index(owner, name, opcode, true); }
        long calls(String owner, String name) {
            return steps.stream().filter(step -> owner.equals(step.owner) && name.equals(step.name)).count();
        }
        int previousOpcode(int before, int opcode) {
            for (int index = before - 1; index >= 0; index--) {
                if (steps.get(index).opcode == opcode) return index;
            }
            throw new AssertionError("Missing preceding opcode " + opcode);
        }
        private int index(String owner, String name, int opcode, boolean last) {
            int found = -1;
            for (int index = 0; index < steps.size(); index++) {
                Step step = steps.get(index);
                if (owner.equals(step.owner) && name.equals(step.name) && (opcode < 0 || opcode == step.opcode)) {
                    found = index;
                    if (!last) break;
                }
            }
            assertTrue(found >= 0, owner + "#" + name);
            return found;
        }
    }
}
