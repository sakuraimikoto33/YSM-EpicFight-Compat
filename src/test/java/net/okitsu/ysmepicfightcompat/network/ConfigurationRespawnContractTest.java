package net.okitsu.ysmepicfightcompat.network;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Locks the Forge event wiring without loading Minecraft's static registries. */
class ConfigurationRespawnContractTest {
    private static final String ROOT = "net/okitsu/ysmepicfightcompat/network/";
    private static final String CONFIGURATION = ROOT + "ConfigurationVariableBroadcaster";
    private static final String SELECTION = ROOT + "SelectionBroadcaster";
    private static final String TRANSITIONS = ROOT + "ConfigurationPlayerTransitions";
    private static final String SESSION = ROOT + "ConfigurationSyncSession";
    private static final String NBT = ROOT + "PlayerSelectionNbt";
    private static final String NETWORK = ROOT + "CompatNetwork";
    private static final String MAP = "java/util/Map";
    private static final String SUBSCRIBE = "Lnet/minecraftforge/eventbus/api/SubscribeEvent;";

    @Test
    void forgeEventsForwardTheirDeathFlagsToTheTransitionBoundary() throws IOException {
        List<MethodCode> methods = read(SELECTION);
        MethodCode cloned = method(methods, "playerCloned");
        assertTrue(cloned.annotations.contains(SUBSCRIBE));
        assertEquals(only(cloned.calls("net/minecraftforge/event/entity/player/PlayerEvent$Clone",
                "isWasDeath")) + 1, only(cloned.calls(CONFIGURATION, "cloned")),
                "The death flag must be forwarded without negation or replacement");
        MethodCode respawned = method(methods, "playerRespawned");
        assertTrue(respawned.annotations.contains(SUBSCRIBE));
        assertEquals(only(respawned.calls(
                "net/minecraftforge/event/entity/player/PlayerEvent$PlayerRespawnEvent",
                "isEndConquered")) + 1, only(respawned.calls(CONFIGURATION, "respawned")),
                "The End-return flag must be forwarded without negation or replacement");
        for (MethodCode handler : List.of(cloned, respawned)) {
            assertNoSelectionReadOrNotification(handler);
            assertTrue(handler.calls(CONFIGURATION, "reset").isEmpty());
        }
    }

    @Test
    void cloneValidatesTheOnlineOriginalAndRecordsTheTransitionBeforeClearingTheOldLife()
            throws IOException {
        MethodCode cloned = method(read(CONFIGURATION), "cloned");
        int online = only(cloned.calls(CONFIGURATION, "onlinePlayer"));
        int begin = only(cloned.calls(TRANSITIONS, "begin"));
        int remove = only(cloned.calls(MAP, "remove"));
        int replacementSession = only(cloned.calls(SESSION, "<init>"));
        int put = only(cloned.calls(MAP, "put"));

        assertTrue(online < begin && begin < remove);
        assertTrue(remove < replacementSession && replacementSession < put);
        assertNotReached(cloned, begin, false, remove, replacementSession, put);
        assertTrue(cloned.calls(SESSION, "isOwner").isEmpty(),
                "A lazily replaced End-return ACK owner must not veto the next genuine death");
    }

    @Test
    void nonDeathCloneCannotClearValuesOrCreateANewAcknowledgementSession() throws IOException {
        MethodCode cloned = method(read(CONFIGURATION), "cloned");
        List<Integer> deathLoads = IntStream.range(0, cloned.steps.size() - 1)
                .filter(index -> cloned.steps.get(index).opcode == Opcodes.ILOAD
                        && cloned.steps.get(index).operand == 2
                        && isBooleanJump(cloned.steps.get(index + 1).opcode))
                .boxed().toList();
        int deathLoad = only(deathLoads);
        int remove = only(cloned.calls(MAP, "remove"));
        int replacementSession = only(cloned.calls(SESSION, "<init>"));
        int put = only(cloned.calls(MAP, "put"));
        int values = only(cloned.fields(CONFIGURATION, "STATES"));

        assertTrue(only(cloned.calls(TRANSITIONS, "begin")) < deathLoad);
        assertNotReached(cloned, deathLoad, false, values, remove, replacementSession, put);
        Set<Integer> deathPath = cloned.reachableAfterBoolean(deathLoad, true);
        assertTrue(deathPath.containsAll(List.of(values, remove, replacementSession, put)),
                "A genuine death must invalidate values and ACK identity before tracking");
    }

    @Test
    void cloneNeverReadsAnUnrestoredSelectionOrSendsAnEarlySnapshot() throws IOException {
        MethodCode cloned = method(read(CONFIGURATION), "cloned");
        assertNoSelectionReadOrNotification(cloned);
        assertTrue(cloned.calls(CONFIGURATION, "current").isEmpty());
        assertTrue(cloned.calls(CONFIGURATION, "message").isEmpty());
        assertTrue(cloned.calls(CONFIGURATION, "session").isEmpty());
        assertTrue(cloned.calls(CONFIGURATION, "reset").isEmpty());
    }

    @Test
    void respawnOnlyMarksTheMatchingTransitionReadyWithoutASecondReset() throws IOException {
        MethodCode respawned = method(read(CONFIGURATION), "respawned");
        assertEquals(1, respawned.calls(TRANSITIONS, "respawned").size());
        assertNoSelectionReadOrNotification(respawned);
        assertNoValueOrAcknowledgementMutation(respawned);
        assertTrue(respawned.calls(CONFIGURATION, "session").isEmpty());
        assertTrue(respawned.calls(CONFIGURATION, "message").isEmpty());
        assertTrue(respawned.calls(TRANSITIONS, "remove").isEmpty());
    }

    @Test
    void readyRespawnsFlushEveryEndTickBeforeTheTwentyTickSelectionPoll() throws IOException {
        MethodCode tick = method(read(SELECTION), "serverTick");
        assertTrue(tick.annotations.contains(SUBSCRIBE));
        int end = only(tick.fields("net/minecraftforge/event/TickEvent$Phase", "END"));
        int server = only(tick.calls("net/minecraftforge/event/TickEvent$ServerTickEvent", "getServer"));
        int flush = only(tick.calls(CONFIGURATION, "flushRespawns"));
        int tickCount = only(tick.calls("net/minecraft/server/MinecraftServer", "getTickCount"));
        int modulo = only(tick.opcodes(Opcodes.IREM));
        int synchronization = only(tick.calls(SELECTION, "synchronize"));

        assertTrue(end < server && server < flush && flush < tickCount);
        assertTrue(tickCount < modulo && modulo < synchronization);
        Step phaseGuard = tick.steps.get(end + 1);
        assertEquals(Opcodes.IF_ACMPEQ, phaseGuard.opcode,
                "Only END ticks may enter the flush path");
        assertEquals(Opcodes.RETURN, tick.steps.get(end + 2).opcode,
                "START ticks must return before reaching the respawn flush");
        assertTrue(tick.labels.get(phaseGuard.target) > end + 2
                && tick.labels.get(phaseGuard.target) < server);
        assertEquals(Opcodes.BIPUSH, tick.steps.get(tickCount + 1).opcode);
        assertEquals(20, tick.steps.get(tickCount + 1).operand);
        assertTrue(tick.steps.subList(flush + 1, tickCount).stream()
                .noneMatch(step -> step.target != null),
                "The selection poll interval must not gate the respawn flush");
    }

    @Test
    void flushRechecksTheOnlineReplacementAndOnlyNotifiesForAPendingDeathBoundary() throws IOException {
        MethodCode flush = method(read(CONFIGURATION), "flushRespawns");
        int ready = only(flush.calls(TRANSITIONS, "ready"));
        int online = only(flush.calls(CONFIGURATION, "onlinePlayer"));
        int connected = only(flush.calls(NETWORK, "isConnected"));
        int death = only(flush.calls(TRANSITIONS + "$Pending", "deathNotificationRequired"));
        int notify = only(flush.calls(SELECTION, "resynchronize"));
        int cleanup = only(flush.calls(TRANSITIONS, "remove"));

        assertTrue(ready < online && online < connected && connected < death);
        assertTrue(death < notify && notify < cleanup);
        Step onlineIdentity = flush.steps.get(online + 2);
        assertEquals(Opcodes.IF_ACMPNE, onlineIdentity.opcode,
                "The pending player must still be the registered player by identity");
        assertTrue(flush.labels.get(onlineIdentity.target) > notify,
                "A stale online identity must skip notification");
        assertNotReachedBeforeLoop(flush, connected, false, notify);
        assertNotReachedBeforeLoop(flush, death, false, notify);
        assertTrue(flush.reachableAfterBooleanBeforeLoop(death, false).contains(cleanup),
                "Ordinary End-return transitions need cleanup without an extra snapshot");
        assertTrue(flush.calls(TRANSITIONS + "$Pending", "wasDeath").isEmpty(),
                "A later End clone must not suppress an earlier unflushed death notification");
        assertNoValueOrAcknowledgementMutation(flush);
        assertTrue(flush.calls(NBT, "read").isEmpty());
    }

    @Test
    void forcedResynchronizationAlwaysPublishesSelectionThenCurrentValuesWithoutResetting()
            throws IOException {
        MethodCode resynchronize = method(read(SELECTION), "resynchronize");
        int guard = only(resynchronize.calls(CONFIGURATION, "isCurrentPlayer"));
        int snapshot = only(resynchronize.calls(SELECTION, "snapshot"));
        int cache = only(resynchronize.calls(MAP, "put"));
        int selection = only(resynchronize.calls(SELECTION, "broadcast"));
        int configuration = only(resynchronize.calls(CONFIGURATION, "synchronize"));

        assertTrue(guard < snapshot && snapshot < cache && cache < selection && selection < configuration);
        assertTrue(resynchronize.steps.get(snapshot).descriptor.contains("ServerPlayer;"),
                "The deferred notification must read the replacement's current selection");
        assertTrue(resynchronize.steps.subList(snapshot + 1, configuration).stream()
                .noneMatch(step -> step.target != null),
                "Unchanged model and texture must not suppress a new-life notification");
        assertNotReached(resynchronize, guard, false, snapshot, cache, selection, configuration);
        assertNoValueOrAcknowledgementMutation(resynchronize);
    }

    @Test
    void valueAndSelectionWritesRejectStalePlayerInstancesBeforeReadingOrPublishing()
            throws IOException {
        for (String type : List.of(CONFIGURATION, SELECTION)) {
            List<MethodCode> methods = read(type);
            List<String> names = type.equals(CONFIGURATION)
                    ? List.of("send", "reset", "synchronize")
                    : List.of("send", "synchronize", "resynchronize");
            for (String name : names) {
                MethodCode handler = method(methods, name);
                int guard = only(handler.calls(CONFIGURATION, "isCurrentPlayer"));
                Set<Integer> rejected = handler.reachableAfterBoolean(guard, false);
                for (int index = 0; index < handler.steps.size(); index++) {
                    Step step = handler.steps.get(index);
                    if (isStateWork(step)) {
                        assertTrue(guard < index, type + "." + name);
                        assertFalse(rejected.contains(index),
                                type + "." + name + " must reject stale owners before " + step.name);
                    }
                }
            }
        }
        MethodCode guard = method(read(CONFIGURATION), "isCurrentPlayer");
        assertTrue(only(guard.calls(CONFIGURATION, "onlinePlayer"))
                < only(guard.calls(TRANSITIONS, "allows")));
    }

    @Test
    void delayedDisconnectCannotClearANewerOwnersState() throws IOException {
        List<MethodCode> methods = read(CONFIGURATION);
        MethodCode remove = method(methods, "remove");
        int allowed = only(remove.calls(CONFIGURATION, "mayRemove"));
        List<Integer> mutations = new ArrayList<>(remove.calls(MAP, "remove"));
        mutations.addAll(remove.calls(TRANSITIONS, "remove"));

        assertEquals(3, mutations.size());
        for (int mutation : mutations) {
            assertNotReached(remove, allowed, false, mutation);
        }
        MethodCode mayRemove = method(methods, "mayRemove");
        assertTrue(only(mayRemove.calls(CONFIGURATION, "onlinePlayer"))
                < only(mayRemove.calls(TRANSITIONS, "allows")));
        assertTrue(mayRemove.calls(SESSION, "isOwner").isEmpty(),
                "A lazy End-return ACK owner must not veto the current player's disconnect");
    }

    @Test
    void stoppingTheServerClearsValuesAcknowledgementsAndPendingTransitions() throws IOException {
        MethodCode clear = method(read(CONFIGURATION), "clear");
        assertEquals(1, clear.fields(CONFIGURATION, "STATES").size());
        assertEquals(1, clear.fields(CONFIGURATION, "SESSIONS").size());
        assertEquals(2, clear.calls(MAP, "clear").size());
        assertEquals(1, clear.calls(TRANSITIONS, "clear").size());
    }

    private static void assertNoSelectionReadOrNotification(MethodCode method) {
        assertTrue(method.calls(NBT, "read").isEmpty(), method.name);
        assertTrue(method.calls(NETWORK, "toPlayer").isEmpty(), method.name);
        assertTrue(method.calls(NETWORK, "toTrackersAndSelf").isEmpty(), method.name);
        for (String name : List.of("send", "broadcast", "synchronize", "resynchronize")) {
            assertTrue(method.calls(SELECTION, name).isEmpty(), method.name);
        }
        for (String name : List.of("send", "synchronize")) {
            assertTrue(method.calls(CONFIGURATION, name).isEmpty(), method.name);
        }
    }

    private static void assertNoValueOrAcknowledgementMutation(MethodCode method) {
        assertTrue(method.fields(CONFIGURATION, "STATES").isEmpty(), method.name);
        assertTrue(method.fields(CONFIGURATION, "SESSIONS").isEmpty(), method.name);
        assertTrue(method.calls(CONFIGURATION, "reset").isEmpty(), method.name);
        assertTrue(method.calls(CONFIGURATION, "session").isEmpty(), method.name);
        assertTrue(method.calls(CONFIGURATION, "message").isEmpty(), method.name);
        assertTrue(method.calls(SESSION, "<init>").isEmpty(), method.name);
        assertTrue(method.calls(SESSION, "invalidate").isEmpty(), method.name);
    }

    private static boolean isStateWork(Step step) {
        return CONFIGURATION.equals(step.owner)
                && Set.of("STATES", "SESSIONS", "current", "session", "message", "synchronize")
                .contains(step.name)
                || SELECTION.equals(step.owner)
                && Set.of("LAST_SENT", "snapshot", "broadcast", "message").contains(step.name)
                || NETWORK.equals(step.owner) && step.name.startsWith("to")
                || MAP.equals(step.owner) && Set.of("put", "remove", "compute", "clear").contains(step.name);
    }

    private static void assertNotReached(MethodCode method, int booleanProducer, boolean value,
                                         int... forbidden) {
        Set<Integer> reachable = method.reachableAfterBoolean(booleanProducer, value);
        for (int index : forbidden) {
            assertFalse(reachable.contains(index), method.name + " must not reach step " + index);
        }
    }

    private static void assertNotReachedBeforeLoop(MethodCode method, int booleanProducer,
                                                   boolean value, int forbidden) {
        assertFalse(method.reachableAfterBooleanBeforeLoop(booleanProducer, value).contains(forbidden),
                method.name + " must not notify this rejected transition");
    }

    private static boolean isBooleanJump(int opcode) {
        return opcode == Opcodes.IFEQ || opcode == Opcodes.IFNE;
    }

    private static MethodCode method(List<MethodCode> methods, String name) {
        return only(methods.stream().filter(method -> method.name.equals(name)).toList());
    }

    private static <T> T only(List<T> values) {
        assertEquals(1, values.size(), "Expected one matching method, instruction, or call");
        return values.get(0);
    }

    private static List<MethodCode> read(String type) throws IOException {
        List<MethodCode> methods = new ArrayList<>();
        try (InputStream stream = ConfigurationRespawnContractTest.class.getClassLoader()
                .getResourceAsStream(type + ".class")) {
            assertNotNull(stream, type);
            new ClassReader(stream).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                 String signature, String[] exceptions) {
                    MethodCode method = new MethodCode(name);
                    methods.add(method);
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override
                        public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                            method.annotations.add(descriptor);
                            return null;
                        }

                        @Override
                        public void visitLabel(Label label) {
                            method.labels.put(label, method.steps.size());
                        }

                        @Override
                        public void visitInsn(int opcode) {
                            method.steps.add(new Step(opcode, null, null, null, null, -1));
                        }

                        @Override
                        public void visitVarInsn(int opcode, int variable) {
                            method.steps.add(new Step(opcode, null, null, null, null, variable));
                        }

                        @Override
                        public void visitIntInsn(int opcode, int operand) {
                            method.steps.add(new Step(opcode, null, null, null, null, operand));
                        }

                        @Override
                        public void visitTypeInsn(int opcode, String type) {
                            visitInsn(opcode);
                        }

                        @Override
                        public void visitFieldInsn(int opcode, String owner, String name,
                                                   String descriptor) {
                            method.steps.add(new Step(opcode, owner, name, descriptor, null, -1));
                        }

                        @Override
                        public void visitMethodInsn(int opcode, String owner, String name,
                                                    String descriptor, boolean isInterface) {
                            method.steps.add(new Step(opcode, owner, name, descriptor, null, -1));
                        }

                        @Override
                        public void visitInvokeDynamicInsn(String name, String descriptor,
                                                           Handle bootstrap, Object... arguments) {
                            visitInsn(Opcodes.INVOKEDYNAMIC);
                        }

                        @Override
                        public void visitJumpInsn(int opcode, Label target) {
                            method.steps.add(new Step(opcode, null, null, null, target, -1));
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

    private record Step(int opcode, String owner, String name, String descriptor, Label target,
                        int operand) {
    }

    private static final class MethodCode {
        private final String name;
        private final List<Step> steps = new ArrayList<>();
        private final Map<Label, Integer> labels = new IdentityHashMap<>();
        private final Set<String> annotations = new HashSet<>();

        private MethodCode(String name) {
            this.name = name;
        }

        private List<Integer> calls(String owner, String name) {
            return matching(owner, name).stream().filter(index -> {
                int opcode = steps.get(index).opcode;
                return opcode == Opcodes.INVOKESTATIC || opcode == Opcodes.INVOKEVIRTUAL
                        || opcode == Opcodes.INVOKEINTERFACE || opcode == Opcodes.INVOKESPECIAL;
            }).toList();
        }

        private List<Integer> fields(String owner, String name) {
            return matching(owner, name).stream().filter(index -> {
                int opcode = steps.get(index).opcode;
                return opcode == Opcodes.GETSTATIC || opcode == Opcodes.PUTSTATIC
                        || opcode == Opcodes.GETFIELD || opcode == Opcodes.PUTFIELD;
            }).toList();
        }

        private List<Integer> matching(String owner, String name) {
            return IntStream.range(0, steps.size()).filter(index -> {
                Step step = steps.get(index);
                return owner.equals(step.owner) && name.equals(step.name);
            }).boxed().toList();
        }

        private List<Integer> opcodes(int opcode) {
            return IntStream.range(0, steps.size())
                    .filter(index -> steps.get(index).opcode == opcode).boxed().toList();
        }

        private Set<Integer> reachableAfterBoolean(int producer, boolean value) {
            return reachableAfterBoolean(producer, value, false);
        }

        private Set<Integer> reachableAfterBooleanBeforeLoop(int producer, boolean value) {
            return reachableAfterBoolean(producer, value, true);
        }

        private Set<Integer> reachableAfterBoolean(int producer, boolean value, boolean stopAtLoop) {
            Step branch = steps.get(producer + 1);
            assertTrue(isBooleanJump(branch.opcode), name + " must branch on its boolean result");
            boolean jump = branch.opcode == Opcodes.IFEQ ? !value : value;
            int start = jump ? labels.get(branch.target) : producer + 2;
            Set<Integer> reached = new HashSet<>();
            ArrayDeque<Integer> pending = new ArrayDeque<>();
            pending.add(start);
            while (!pending.isEmpty()) {
                int index = pending.removeFirst();
                if (index >= steps.size() || stopAtLoop && index <= producer || !reached.add(index)) {
                    continue;
                }
                Step step = steps.get(index);
                if (step.opcode >= Opcodes.IRETURN && step.opcode <= Opcodes.RETURN
                        || step.opcode == Opcodes.ATHROW) {
                    continue;
                }
                if (step.target != null) {
                    pending.add(labels.get(step.target));
                    if (step.opcode == Opcodes.GOTO) {
                        continue;
                    }
                }
                pending.add(index + 1);
            }
            return reached;
        }
    }
}
