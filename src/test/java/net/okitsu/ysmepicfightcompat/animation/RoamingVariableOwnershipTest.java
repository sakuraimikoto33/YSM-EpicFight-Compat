package net.okitsu.ysmepicfightcompat.animation;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoamingVariableOwnershipTest {
    private static final String VISIBLE = "v.roaming.visible";
    private static final String PREVIOUS = "v.roaming.previous";

    @Test
    void ownerCommitsTwoDependentTimelineWritesOnEveryActivation() {
        VariableEnvironment owner = new VariableEnvironment(false, false);
        AnimationClip clip = toggleClip();
        for (int activation = 1; activation <= 3; activation++) {
            double expected = activation % 2;
            Map<String, Float> clock = new HashMap<>();

            ParallelAnimationProgram.fireTimeline(clip, 0.21F, owner, clock);
            assertEquals(expected, owner.value(VISIBLE));
            assertEquals(1.0D - expected, owner.value(PREVIOUS));

            ParallelAnimationProgram.fireTimeline(clip, 0.25F, owner, clock);
            assertState(owner, expected);
        }
        assertEquals(6, owner.providerWrites);
    }

    @Test
    void delayedRemoteTimelineCannotInvertAnAlreadyReceivedOwnerSnapshot() {
        for (boolean separateFrames : List.of(false, true)) {
            VariableEnvironment owner = new VariableEnvironment(false, false);
            VariableEnvironment remote = new VariableEnvironment(false, true);
            AnimationClip clip = toggleClip();
            for (int activation = 1; activation <= 3; activation++) {
                double expected = activation % 2;
                ParallelAnimationProgram.fireTimeline(
                        clip, 0.25F, owner, new HashMap<>());
                remote.receive(owner);
                assertState(remote, expected);

                Map<String, Float> remoteClock = new HashMap<>();
                if (separateFrames) {
                    ParallelAnimationProgram.fireTimeline(clip, 0.21F, remote, remoteClock);
                    assertState(remote, expected);
                }
                ParallelAnimationProgram.fireTimeline(clip, 0.25F, remote, remoteClock);
                assertState(remote, expected);
                assertEquals(0, remote.providerWrites);
                assertTrue(remote.local.isEmpty());
            }
        }
    }

    @Test
    void remoteNumericAndTypedAssignmentsPreserveSharedValuesAndLocalOrdinaryVariables() {
        VariableEnvironment remote = new VariableEnvironment(false, true);
        remote.provider.put(ExpressionEngine.slot(VISIBLE), 1.0D);

        remote.writeVariable(ExpressionEngine.slot(VISIBLE), 0.0D);
        ExpressionEngine.compile("variable.roaming.visible='replacement';"
                + "v.local=v.roaming.visible;v.label='kept';").evaluate(remote);

        assertEquals(1.0D, remote.value(VISIBLE));
        assertEquals(1.0D, remote.value("v.local"));
        assertEquals("kept", remote.readVariableValue(ExpressionEngine.slot("v.label")));
        assertFalse(remote.local.containsKey(ExpressionEngine.slot(VISIBLE)));
        assertEquals(0, remote.providerWrites);
    }

    @Test
    void remoteWritesCannotCreateLocalShadowsBeforeTheProviderArrives() {
        VariableEnvironment remote = new VariableEnvironment(false, true, false);

        remote.writeVariable(ExpressionEngine.slot(VISIBLE), 1.0D);
        ExpressionEngine.compile("variable.roaming.previous='replacement';"
                + "v.local=7;v.label='kept';").evaluate(remote);

        assertFalse(remote.hasVariable(ExpressionEngine.slot(VISIBLE)));
        assertFalse(remote.hasVariable(ExpressionEngine.slot(PREVIOUS)));
        assertEquals(7.0D, remote.value("v.local"));
        assertEquals("kept", remote.readVariableValue(ExpressionEngine.slot("v.label")));
        assertTrue(remote.provider.isEmpty());
        assertEquals(0, remote.providerWrites);

        VariableEnvironment owner = new VariableEnvironment(false, false);
        ParallelAnimationProgram.fireTimeline(toggleClip(), 0.25F, owner, new HashMap<>());
        remote.receive(owner);
        assertState(remote, 1.0D);
        assertFalse(remote.local.containsKey(ExpressionEngine.slot(VISIBLE)));
        assertFalse(remote.local.containsKey(ExpressionEngine.slot(PREVIOUS)));
    }

    @Test
    void previewsKeepTheirOwnToggleAndNonPlayerEntitiesCanStillWrite() {
        VariableEnvironment preview = new VariableEnvironment(true, true);
        AnimationClip clip = toggleClip();
        ParallelAnimationProgram.fireTimeline(clip, 0.25F, preview, new HashMap<>());
        assertState(preview, 1.0D);
        assertTrue(preview.provider.isEmpty());
        assertEquals(0, preview.providerWrites);
        ExpressionEngine.compile("v.roaming.label='preview';").evaluate(preview);
        assertEquals("preview", preview.readVariableValue(
                ExpressionEngine.slot("v.roaming.label")));

        // Non-player entities have no live player provider and retain local fallback state.
        VariableEnvironment nonPlayer = new VariableEnvironment(false, false, false);
        ParallelAnimationProgram.fireTimeline(clip, 0.25F, nonPlayer, new HashMap<>());
        assertState(nonPlayer, 1.0D);
        assertEquals(1.0D, nonPlayer.local.get(ExpressionEngine.slot(VISIBLE)));
        assertEquals(1.0D, nonPlayer.local.get(ExpressionEngine.slot(PREVIOUS)));
        assertTrue(nonPlayer.provider.isEmpty());
        assertEquals(0, nonPlayer.providerWrites);
    }

    @Test
    void bothProductionWriteEntrancesRejectBeforeAnyVariableMutation() throws IOException {
        Map<String, WriteMethod> methods = writeMethods();
        assertEquals(Set.of("writeVariable", "writeVariableValue"), methods.keySet());
        methods.forEach((name, method) -> {
            assertFalse(method.reachesMutation(false), name);
            assertTrue(method.reachesMutation(true), name + " must retain allowed writes");
        });
    }

    private static AnimationClip toggleClip() {
        AnimationClip clip = new AnimationClip("synthetic_toggle");
        clip.timeline().add(new AnimationClip.TimelineEvent(
                0.2F, List.of("v.roaming.visible=1-v.roaming.previous;")));
        clip.timeline().add(new AnimationClip.TimelineEvent(
                0.24F, List.of("v.roaming.previous=v.roaming.visible;")));
        return clip;
    }

    private static void assertState(VariableEnvironment environment, double expected) {
        assertEquals(expected, environment.value(VISIBLE));
        assertEquals(expected, environment.value(PREVIOUS));
        assertEquals(1.0D - expected,
                ExpressionEngine.compile("1-v.roaming.visible").evaluate(environment));
    }

    /** Fake synchronized provider; expression and timeline execution use the production engine. */
    private static final class VariableEnvironment implements ExpressionEngine.Environment {
        private final boolean preview;
        private final boolean remotePlayer;
        private boolean providerAvailable;
        private final Map<Integer, Double> provider = new HashMap<>();
        private final Map<Integer, Object> local = new HashMap<>();
        private int providerWrites;

        private VariableEnvironment(boolean preview, boolean remotePlayer) {
            this(preview, remotePlayer, true);
        }

        private VariableEnvironment(boolean preview, boolean remotePlayer, boolean providerAvailable) {
            this.preview = preview;
            this.remotePlayer = remotePlayer;
            this.providerAvailable = providerAvailable;
        }

        private void receive(VariableEnvironment owner) {
            providerAvailable = true;
            provider.clear();
            provider.putAll(owner.provider);
        }

        private double value(String name) { return readVariable(ExpressionEngine.slot(name)); }

        @Override public double readVariable(int slot) {
            Object value = readVariableValue(slot);
            return value instanceof Number number ? number.doubleValue() : 0.0D;
        }

        @Override public Object readVariableValue(int slot) {
            if (local.containsKey(slot)) return local.get(slot);
            return provider.getOrDefault(slot, 0.0D);
        }

        @Override public boolean hasVariable(int slot) {
            return local.containsKey(slot) || provider.containsKey(slot);
        }

        @Override public void writeVariable(int slot, double value) {
            writeVariableValue(slot, value);
        }

        @Override public void writeVariableValue(int slot, Object value) {
            String name = ExpressionEngine.slotName(slot);
            if (!EntityAnimationEnvironment.permitsVariableWrite(preview, remotePlayer, name)) return;
            if (!preview && providerAvailable && RoamingVariableLookup.isRoaming(name)
                    && value instanceof Number number) {
                provider.put(slot, number.doubleValue());
                providerWrites++;
            } else {
                local.put(slot, value);
            }
        }

        @Override public double readQuery(int slot) { return 0.0D; }
        @Override public double invoke(String name, double[] arguments) { return 0.0D; }
        @Override public double invokeWithText(String name, String[] arguments) { return 0.0D; }
    }

    /** Verifies the real entry points without bootstrapping Minecraft player instances. */
    private static Map<String, WriteMethod> writeMethods() throws IOException {
        String type = "net/okitsu/ysmepicfightcompat/animation/EntityAnimationEnvironment";
        Map<String, WriteMethod> methods = new HashMap<>();
        try (InputStream stream = RoamingVariableOwnershipTest.class.getClassLoader()
                .getResourceAsStream(type + ".class")) {
            assertNotNull(stream);
            new ClassReader(stream).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                            String signature, String[] exceptions) {
                    if (!Set.of("writeVariable", "writeVariableValue").contains(name)) return null;
                    WriteMethod method = new WriteMethod(type);
                    methods.put(name, method);
                    return method;
                }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }
        return methods;
    }

    private record Step(int opcode, Label target, boolean permission, boolean mutation) { }
    private record Position(int index, Boolean permission) { }

    private static final class WriteMethod extends MethodVisitor {
        private final String environmentType;
        private final List<Step> steps = new ArrayList<>();
        private final Map<Label, Integer> labels = new IdentityHashMap<>();

        private WriteMethod(String environmentType) {
            super(Opcodes.ASM9);
            this.environmentType = environmentType;
        }

        @Override public void visitLabel(Label label) { labels.put(label, steps.size()); }

        @Override public void visitMethodInsn(int opcode, String owner, String name,
                                             String descriptor, boolean isInterface) {
            boolean permission = owner.equals(environmentType)
                    && name.equals("permitsVariableWrite") && descriptor.equals("(I)Z");
            boolean mutation = Set.of("put", "remove", "add", "clear", "writeRoaming",
                    "writeVariable", "writeVariableValue").contains(name);
            steps.add(new Step(opcode, null, permission, mutation));
        }

        @Override public void visitJumpInsn(int opcode, Label target) {
            steps.add(new Step(opcode, target, false, false));
        }

        @Override public void visitInsn(int opcode) {
            if (opcode >= Opcodes.IRETURN && opcode <= Opcodes.RETURN || opcode == Opcodes.ATHROW) {
                steps.add(new Step(opcode, null, false, false));
            }
        }

        private boolean reachesMutation(boolean allowed) {
            ArrayDeque<Position> pending = new ArrayDeque<>();
            Set<Position> visited = new HashSet<>();
            pending.add(new Position(0, null));
            while (!pending.isEmpty()) {
                Position position = pending.removeFirst();
                if (position.index >= steps.size() || !visited.add(position)) continue;
                Step step = steps.get(position.index);
                if (step.mutation) return true;
                if (step.permission) {
                    pending.add(new Position(position.index + 1, allowed));
                } else if (step.opcode >= Opcodes.IRETURN && step.opcode <= Opcodes.RETURN
                        || step.opcode == Opcodes.ATHROW) {
                    // No successor.
                } else if (step.target != null) {
                    Boolean jump = position.permission == null ? null
                            : step.opcode == Opcodes.IFEQ ? !position.permission
                            : step.opcode == Opcodes.IFNE ? position.permission : null;
                    if (jump == null || jump) pending.add(new Position(labels.get(step.target), null));
                    if (step.opcode != Opcodes.GOTO && (jump == null || !jump)) {
                        pending.add(new Position(position.index + 1, null));
                    }
                } else {
                    pending.add(new Position(position.index + 1, null));
                }
            }
            return false;
        }
    }
}
