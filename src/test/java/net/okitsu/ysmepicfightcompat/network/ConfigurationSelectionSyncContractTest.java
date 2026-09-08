package net.okitsu.ysmepicfightcompat.network;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Checks selection/configuration ordering without bootstrapping a server or a client. */
class ConfigurationSelectionSyncContractTest {
    private static final String ROOT = "net/okitsu/ysmepicfightcompat/";
    private static final String SELECTION = ROOT + "network/SelectionBroadcaster";
    private static final String CONFIGURATION = ROOT + "network/ConfigurationVariableBroadcaster";
    private static final String SNAPSHOT = ROOT + "network/ModelConfigurationSnapshot";
    private static final String NETWORK = ROOT + "network/CompatNetwork";
    private static final String OFFICIAL = ROOT + "animation/OfficialConfigurationVariables";
    private static final String OVERRIDES = ROOT + "animation/ModelConfigurationOverrides";
    private static final String RESOLVER = ROOT + "render/PlayerSelectionResolver";

    @Test
    void selectionSynchronizationPublishesSelectionBeforeItsConfigurationSnapshot() throws IOException {
        List<MethodCode> methods = read(SELECTION);
        MethodCode synchronize = method(methods, "synchronize");
        int selection = only(synchronize.calls(SELECTION, "broadcast"));
        int configuration = only(synchronize.calls(CONFIGURATION, "synchronize"));

        assertTrue(selection < configuration);
        assertTrue(synchronize.descriptor.endsWith(")Z"));
        assertEquals(Opcodes.ICONST_1, synchronize.steps.get(configuration + 1).opcode);
        assertEquals(Opcodes.IRETURN, synchronize.steps.get(configuration + 2).opcode,
                "A model-change snapshot must report that variables were already sent");
        assertTrue(synchronize.calls(CONFIGURATION, "reset").isEmpty());
        assertEquals(1, method(methods, "broadcast").calls(NETWORK, "toTrackersAndSelf").size());
    }

    @Test
    void acceptedChangesAreMergedBeforePublishingAndDoNotSendADuplicateFirstSnapshot()
            throws IOException {
        MethodCode accept = method(read(CONFIGURATION), "accept");
        int merge = only(accept.calls("java/util/Map", "compute"));
        List<Integer> synchronizations = accept.calls(SELECTION, "synchronize");
        assertEquals(2, synchronizations.size(), "Both null and selected-model paths synchronize");
        int selectedModelSync = synchronizations.get(1);
        int send = only(accept.calls(NETWORK, "toTrackersAndSelf"));

        assertTrue(merge < selectedModelSync, "The first new-model snapshot must include the edit");
        assertTrue(synchronizations.stream().allMatch(index -> index < send));
        Step skipDuplicate = accept.steps.get(selectedModelSync + 1);
        assertEquals(Opcodes.IFNE, skipDuplicate.opcode,
                "A snapshot sent by selection synchronization must suppress the extra send");
        assertTrue(accept.labels.get(skipDuplicate.target) > send);
        assertTrue(accept.calls(CONFIGURATION, "reset").isEmpty());
    }

    @Test
    void pollingUsesTheSameOrderedSynchronizationPathWithoutResettingValues() throws IOException {
        MethodCode tick = method(read(SELECTION), "serverTick");

        assertEquals(1, tick.calls(SELECTION, "synchronize").size());
        assertTrue(tick.calls(SELECTION, "broadcast").isEmpty());
        assertTrue(tick.calls(CONFIGURATION, "reset").isEmpty());
        assertTrue(tick.calls(CONFIGURATION, "synchronize").isEmpty(),
                "Polling must not bypass the shared selection-first path");
    }

    @Test
    void configurationSynchronizationReconcilesTheModelInsteadOfClearingItsValues()
            throws IOException {
        List<MethodCode> methods = read(CONFIGURATION);
        MethodCode synchronize = method(methods, "synchronize");
        assertTrue(only(synchronize.calls("java/util/Map", "compute"))
                < only(synchronize.calls(NETWORK, "toTrackersAndSelf")));
        assertTrue(synchronize.calls(CONFIGURATION, "reset").isEmpty());
        assertTrue(synchronize.calls("java/util/Map", "remove").isEmpty());
        assertTrue(synchronize.calls("java/util/Map", "clear").isEmpty());
        MethodCode reconcile = only(methods.stream()
                .filter(candidate -> candidate.name.startsWith("lambda$synchronize$")).toList());
        assertEquals(1, reconcile.calls(SNAPSHOT, "reconcile").size());
        assertTrue(reconcile.calls(SNAPSHOT, "merge").isEmpty());
    }

    @Test
    void loginAndTrackingSendSelectionBeforeConfiguration() throws IOException {
        List<MethodCode> methods = read(SELECTION);
        for (String name : List.of("playerLoggedIn", "startedTracking")) {
            MethodCode notification = method(methods, name);
            assertTrue(only(notification.calls(SELECTION, "send"))
                    < only(notification.calls(CONFIGURATION, "send")), name);
        }
    }

    @Test
    void renderSelectionReadsCannotResetANewerConfigurationSnapshot() throws IOException {
        for (MethodCode resolverMethod : read(RESOLVER)) {
            assertTrue(resolverMethod.calls(OFFICIAL, "reset").isEmpty(), resolverMethod.name);
        }
        MethodCode lookup = method(read(OFFICIAL), "lookup");
        assertEquals(1, lookup.calls(OVERRIDES, "lookup").size());
        assertTrue(lookup.calls(OVERRIDES, "accept").isEmpty());
        assertTrue(lookup.calls(OVERRIDES, "evaluate").isEmpty());
        assertTrue(lookup.calls("java/util/Map", "remove").isEmpty());
        assertTrue(lookup.calls("java/util/Map", "computeIfAbsent").isEmpty());
    }

    @Test
    void configurationEditsUseTheLiveOfficialSelectionRatherThanTheRenderCache()
            throws IOException {
        MethodCode apply = method(read(OFFICIAL), "apply");
        int liveSelection = only(apply.calls(ROOT + "network/PlayerSelectionNbt", "read"));
        int evaluate = only(apply.calls(OVERRIDES, "evaluate"));

        assertTrue(liveSelection < evaluate);
        assertTrue(apply.calls(RESOLVER, "current").isEmpty());
    }

    private static MethodCode method(List<MethodCode> methods, String name) {
        return only(methods.stream().filter(method -> method.name.equals(name)).toList());
    }

    private static <T> T only(List<T> values) {
        assertEquals(1, values.size(), "Expected one matching method or call");
        return values.get(0);
    }

    private static List<MethodCode> read(String type) throws IOException {
        List<MethodCode> methods = new ArrayList<>();
        try (InputStream stream = ConfigurationSelectionSyncContractTest.class.getClassLoader()
                .getResourceAsStream(type + ".class")) {
            assertNotNull(stream, type);
            new ClassReader(stream).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                 String signature, String[] exceptions) {
                    MethodCode method = new MethodCode(name, descriptor);
                    methods.add(method);
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override
                        public void visitLabel(Label label) {
                            method.labels.put(label, method.steps.size());
                        }

                        @Override
                        public void visitInsn(int opcode) {
                            method.steps.add(Step.opcode(opcode));
                        }

                        @Override
                        public void visitVarInsn(int opcode, int variable) {
                            visitInsn(opcode);
                        }

                        @Override
                        public void visitIntInsn(int opcode, int operand) {
                            visitInsn(opcode);
                        }

                        @Override
                        public void visitTypeInsn(int opcode, String type) {
                            visitInsn(opcode);
                        }

                        @Override
                        public void visitFieldInsn(int opcode, String owner, String name,
                                                   String descriptor) {
                            method.steps.add(new Step(opcode, owner, name, null));
                        }

                        @Override
                        public void visitMethodInsn(int opcode, String owner, String name,
                                                    String descriptor, boolean isInterface) {
                            method.steps.add(new Step(opcode, owner, name, null));
                        }

                        @Override
                        public void visitInvokeDynamicInsn(String name, String descriptor,
                                                           Handle bootstrap, Object... arguments) {
                            visitInsn(Opcodes.INVOKEDYNAMIC);
                        }

                        @Override
                        public void visitJumpInsn(int opcode, Label target) {
                            method.steps.add(new Step(opcode, null, null, target));
                        }

                        @Override
                        public void visitLdcInsn(Object value) {
                            visitInsn(Opcodes.LDC);
                        }

                        @Override
                        public void visitIincInsn(int variable, int increment) {
                            visitInsn(Opcodes.IINC);
                        }
                    };
                }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }
        return methods;
    }

    private record Step(int opcode, String owner, String name, Label target) {
        private static Step opcode(int opcode) {
            return new Step(opcode, null, null, null);
        }
    }

    private static final class MethodCode {
        private final String name;
        private final String descriptor;
        private final List<Step> steps = new ArrayList<>();
        private final Map<Label, Integer> labels = new IdentityHashMap<>();

        private MethodCode(String name, String descriptor) {
            this.name = name;
            this.descriptor = descriptor;
        }

        private List<Integer> calls(String owner, String name) {
            return IntStream.range(0, steps.size()).filter(index -> {
                Step step = steps.get(index);
                return owner.equals(step.owner) && name.equals(step.name)
                        && (step.opcode == Opcodes.INVOKESTATIC || step.opcode == Opcodes.INVOKEVIRTUAL
                        || step.opcode == Opcodes.INVOKEINTERFACE || step.opcode == Opcodes.INVOKESPECIAL);
            }).boxed().toList();
        }
    }
}
