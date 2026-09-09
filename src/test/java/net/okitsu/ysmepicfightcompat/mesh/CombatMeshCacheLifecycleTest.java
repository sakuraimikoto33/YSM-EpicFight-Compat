package net.okitsu.ysmepicfightcompat.mesh;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exercises production completion handlers without starting Minecraft or conversion workers. */
@ResourceLock("CombatMeshCache.lifecycle")
class CombatMeshCacheLifecycleTest {
    private static final String MODEL = "lifecycle:model";
    private static final String OTHER = "lifecycle:other";
    private static final String CACHE = "net/okitsu/ysmepicfightcompat/mesh/CombatMeshCache";
    private static final int OLD_GENERATION = 100;
    private static final int CURRENT_GENERATION = 101;

    @Test
    void oldCompletionCannotRemoveTheReloadedModelsPendingConversion() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.pending.addAll(Set.of(MODEL, OTHER));
            fixture.failed.put(OTHER, 20L);

            completeNull(OLD_GENERATION);

            assertEquals(Set.of(MODEL, OTHER), fixture.pending,
                    "The next render must still see the new-generation conversion as pending");
            assertEquals(Map.of(OTHER, 20L), fixture.failed);
        }
    }

    @Test
    void oldFailureCannotPoisonTheRepairedModelsNewGeneration() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.pending.addAll(Set.of(MODEL, OTHER));
            fixture.failed.put(OTHER, 20L);

            completeFailure(OLD_GENERATION, 500L);

            assertEquals(Set.of(MODEL, OTHER), fixture.pending);
            assertEquals(Map.of(OTHER, 20L), fixture.failed,
                    "An old failure must not stamp the repaired model as failed");
        }
    }

    @Test
    void oldFailureCannotReplaceANewerFailureStamp() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.pending.add(OTHER);
            fixture.failed.putAll(Map.of(MODEL, 700L, OTHER, 20L));

            completeFailure(OLD_GENERATION, 500L);

            assertEquals(Set.of(OTHER), fixture.pending);
            assertEquals(Map.of(MODEL, 700L, OTHER, 20L), fixture.failed);
        }
    }

    @Test
    void oldInterruptionCannotRemoveTheNewGenerationsPendingConversion() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.pending.addAll(Set.of(MODEL, OTHER));
            fixture.failed.putAll(Map.of(MODEL, 700L, OTHER, 20L));

            completeInterruption(OLD_GENERATION);

            assertEquals(Set.of(MODEL, OTHER), fixture.pending);
            assertEquals(Map.of(MODEL, 700L, OTHER, 20L), fixture.failed);
        }
    }

    @Test
    void currentFailureEndsOnlyItsPendingConversionAndRecordsTheCapturedStamp() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.pending.addAll(Set.of(MODEL, OTHER));
            fixture.failed.putAll(Map.of(MODEL, 100L, OTHER, 20L));

            completeFailure(CURRENT_GENERATION, 500L);

            assertEquals(Set.of(OTHER), fixture.pending);
            assertEquals(Map.of(MODEL, 500L, OTHER, 20L), fixture.failed);
        }
    }

    @Test
    void currentInterruptionEndsOnlyItsPendingConversionWithoutRecordingFailure() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.pending.addAll(Set.of(MODEL, OTHER));
            fixture.failed.put(OTHER, 20L);

            completeInterruption(CURRENT_GENERATION);

            assertEquals(Set.of(OTHER), fixture.pending);
            assertEquals(Map.of(OTHER, 20L), fixture.failed);
        }
    }

    @Test
    void currentNullCompletionStillRecordsTheRemoteFailureAndEndsPendingWork() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.pending.addAll(Set.of(MODEL, OTHER));
            fixture.failed.putAll(Map.of(MODEL, 100L, OTHER, 20L));

            completeNull(CURRENT_GENERATION);

            assertEquals(Set.of(OTHER), fixture.pending);
            assertEquals(Map.of(MODEL, 0L, OTHER, 20L), fixture.failed);
        }
    }

    @Test
    void failureStampPreservesTheCapturedSourceVersion() throws Exception {
        Throwable failure = new IllegalStateException("Conversion failure");

        assertEquals(500L, failureStamp(() -> 500L, failure));
        assertEquals(0, failure.getSuppressed().length);
    }

    @Test
    void sourceInspectionFailureStillAllowsCurrentPendingWorkToFinish() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.pending.addAll(Set.of(MODEL, OTHER));
            fixture.failed.put(OTHER, 20L);
            Throwable failure = new IllegalStateException("Conversion failure");
            RuntimeException stampFailure = new UncheckedIOException(
                    new IOException("Source changed during metadata traversal"));

            long stamp = failureStamp(() -> {
                throw stampFailure;
            }, failure);
            completeFailure(CURRENT_GENERATION, stamp);

            assertEquals(-1L, stamp);
            assertEquals(List.of(stampFailure), List.of(failure.getSuppressed()));
            assertEquals(Set.of(OTHER), fixture.pending);
            assertEquals(Map.of(MODEL, -1L, OTHER, 20L), fixture.failed);
        }
    }

    @Test
    void repeatedSourceFailureDoesNotAttemptToSuppressItself() throws Exception {
        RuntimeException failure = new IllegalStateException("Repeated source failure");

        assertEquals(-1L, failureStamp(() -> {
            throw failure;
        }, failure));
        assertEquals(0, failure.getSuppressed().length);
    }

    @Test
    void failureRetryRemainsUnavailableUntilSourceMetadataCanBeReadAgain() throws Exception {
        LongSupplier unavailable = () -> {
            throw new UncheckedIOException(new IOException("Source remains unavailable"));
        };

        assertEquals(-1L, failureStamp(unavailable, null));
        assertEquals(-1L, failureStamp(unavailable, null));
        assertEquals(500L, failureStamp(() -> 500L, null));
    }

    @Test
    void failureStampReadersAlsoGuardMetadataErrorsDuringRetriesAndNullCompletion()
            throws IOException {
        Map<String, MethodCode> methods = readMethods();
        for (String name : List.of("ensure", "retryChangedFailures", "completeConversion")) {
            MethodCode method = methods.get(name);
            assertNotNull(method, name);
            assertTrue(method.calls.contains(CACHE + "#failureStamp"), name);
            assertFalse(method.calls.contains(
                    "net/okitsu/ysmepicfightcompat/assets/LocalModelRepository#metadataStamp"), name);
        }
    }

    @Test
    void workerFencesSuccessAndQueuesFailureAndInterruptionOnTheClient() throws IOException {
        Map<String, MethodCode> methods = readMethods();
        MethodCode worker = methods.get("convert");
        assertNotNull(worker);
        assertTrue(worker.lifecycleFields.isEmpty(),
                "Worker success, failure and interruption must not mutate shared lifecycle state");
        assertEquals(2, worker.calls.stream().filter(call -> call.equals(
                "net/minecraft/client/Minecraft#execute")).count());
        assertEquals(1, worker.calls.stream().filter(call -> call.equals(
                CACHE + "#queueConversionCompletion")).count());
        assertTrue(worker.calls.contains(CACHE + "#bake"));
        assertTrue(worker.calls.indexOf(CACHE + "#bake")
                        < worker.calls.indexOf(CACHE + "#queueConversionCompletion"),
                "Both mesh constructors must finish queuing initialization before the fence");
        assertTrue(worker.calls.contains("java/lang/Thread#interrupt"),
                "Cancellation must retain the worker's interrupted status");
        assertTrue(worker.calls.contains(CACHE + "#failureStamp"),
                "Secondary source-inspection failures must not strand pending conversions");
        Set<String> handlers = Set.of("completeConversion", "completeConversionFailure",
                "completeConversionInterruption");
        Set<String> delegated = new HashSet<>();
        for (String callbackName : worker.lambdaTargets) {
            MethodCode callback = methods.get(callbackName);
            assertNotNull(callback, callbackName);
            assertTrue(callback.lifecycleFields.isEmpty(), callbackName);
            for (String handler : handlers) {
                if (callback.calls.contains(CACHE + '#' + handler)) {
                    delegated.add(handler);
                }
            }
        }
        assertEquals(handlers, delegated,
                "Every worker outcome must reach its guarded client-thread handler");
        assertFalse(worker.calls.stream().anyMatch(call -> handlers.stream()
                .anyMatch(handler -> call.equals(CACHE + '#' + handler))),
                "The worker must enqueue callbacks instead of invoking handlers directly");
    }

    @Test
    void productionFenceOnlyForwardsCompletionToTheForcedClientQueue() throws IOException {
        Map<String, MethodCode> methods = readMethods();
        MethodCode worker = methods.get("convert");
        List<MethodCode> callbacks = worker.lambdaTargets.stream().map(methods::get).toList();
        assertTrue(callbacks.get(0).calls.contains(
                "com/mojang/blaze3d/systems/RenderSystem#recordRenderCall"));
        assertTrue(callbacks.get(1).calls.contains("net/minecraft/client/Minecraft#tell"),
                "execute would run inline while RenderSystem replays its queue");
        assertFalse(callbacks.get(1).calls.contains("net/minecraft/client/Minecraft#execute"));
        assertTrue(callbacks.get(2).calls.contains(CACHE + "#completeConversion"));

        MethodCode scheduler = methods.get("queueConversionCompletion");
        assertEquals(List.of("java/util/function/Consumer#accept"), scheduler.calls);
        assertEquals(1, scheduler.lambdaTargets.size());
        MethodCode fence = methods.get(scheduler.lambdaTargets.get(0));
        assertEquals(List.of("java/util/function/Consumer#accept"), fence.calls,
                "Render replay must only enqueue the client callback, never run it");
        assertTrue(fence.lifecycleFields.isEmpty());
        assertTrue(fence.lambdaTargets.isEmpty());
    }

    @Test
    void bothMeshInitializationsPrecedePublicationEvenWhenClientTasksDrainFirst() {
        for (boolean glow : new boolean[]{false, true}) {
            CompletionQueues queues = new CompletionQueues(glow);
            queues.enqueue(() -> {
                assertEquals(queues.initializations, queues.events);
                queues.events.add("publish");
            });

            queues.drainClient();
            assertTrue(queues.events.isEmpty());
            queues.drainRender();
            assertEquals(queues.initializations, queues.events);
            assertEquals(1, queues.client.size(), "The fence must not publish inline");
            queues.drainClient();
            assertEquals(queues.withCompletion("publish"), queues.events);
        }
    }

    @Test
    void reloadBeforeOrAfterTheFenceKeepsNewPendingWorkAndDisposesOnlyAfterInitialization()
            throws Exception {
        for (boolean reloadBeforeFence : new boolean[]{false, true}) {
            try (Fixture fixture = new Fixture()) {
                fixture.generation.set(OLD_GENERATION);
                fixture.pending.add(MODEL);
                CompletionQueues queues = new CompletionQueues(true);
                queues.enqueue(() -> {
                    assertEquals(queues.initializations, queues.events,
                            "Stale disposal must run after both base and glow initialization");
                    assertDoesNotThrow(() -> completeNull(OLD_GENERATION));
                    queues.events.add("dispose");
                });

                if (!reloadBeforeFence) {
                    queues.drainRender();
                }
                fixture.generation.set(CURRENT_GENERATION);
                fixture.pending.clear();
                fixture.pending.addAll(Set.of(MODEL, OTHER));
                fixture.failed.put(OTHER, 20L);
                if (reloadBeforeFence) {
                    queues.drainClient();
                    assertTrue(queues.events.isEmpty());
                    queues.drainRender();
                }

                assertEquals(Set.of(MODEL, OTHER), fixture.pending);
                queues.drainClient();
                assertEquals(queues.withCompletion("dispose"), queues.events);
                assertEquals(Set.of(MODEL, OTHER), fixture.pending);
                assertEquals(Map.of(OTHER, 20L), fixture.failed);
            }
        }
    }

    @Test
    void fencedNullConversionStillSettlesCurrentPendingWork() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.pending.addAll(Set.of(MODEL, OTHER));
            CompletionQueues queues = new CompletionQueues(List.of());
            queues.enqueue(() -> assertDoesNotThrow(() -> completeNull(CURRENT_GENERATION)));

            queues.drainRender();
            assertEquals(Set.of(MODEL, OTHER), fixture.pending);
            queues.drainClient();
            assertEquals(Set.of(OTHER), fixture.pending);
            assertEquals(Map.of(MODEL, 0L), fixture.failed);
        }
    }

    @Test
    void completionExceptionsAreDeferredToTheClientTaskBoundary() {
        CompletionQueues queues = new CompletionQueues(true);
        IllegalStateException failure = new IllegalStateException("Completion failed");
        queues.enqueue(() -> {
            throw failure;
        });
        queues.render.add(() -> queues.events.add("later-render-call"));

        assertDoesNotThrow(queues::drainRender);
        assertEquals(queues.withCompletion("later-render-call"), queues.events);
        assertSame(failure, assertThrows(IllegalStateException.class, queues::drainClient));
    }

    @Test
    void allCompletionHandlersShareTheCacheMonitorAndFailureUsesNoClientThreadDiskLookup()
            throws IOException {
        Map<String, MethodCode> methods = readMethods();
        for (String name : List.of("completeConversion", "completeConversionFailure",
                "completeConversionInterruption")) {
            MethodCode handler = methods.get(name);
            assertNotNull(handler, name);
            assertTrue(Modifier.isStatic(handler.access), name);
            assertTrue(Modifier.isSynchronized(handler.access), name);
        }
        assertFalse(methods.get("completeConversionFailure").calls.contains(
                "net/okitsu/ysmepicfightcompat/assets/LocalModelRepository#metadataStamp"),
                "The failure stamp must be captured on the conversion worker");
    }

    private static void completeNull(int generation) throws Exception {
        Class<?> conversion = Class.forName(CombatMeshCache.class.getName() + "$Conversion",
                false, CombatMeshCache.class.getClassLoader());
        Method method = handler("completeConversion", String.class, boolean.class,
                int.class, conversion);
        // A null conversion exercises bookkeeping without allocating a mesh or invoking GL.
        method.invoke(null, MODEL, false, generation, null);
    }

    private static long failureStamp(LongSupplier sourceStamp, Throwable conversionFailure)
            throws Exception {
        return (long) handler("failureStamp", LongSupplier.class, Throwable.class)
                .invoke(null, sourceStamp, conversionFailure);
    }

    private static void completeFailure(int generation, long failedStamp) throws Exception {
        handler("completeConversionFailure", String.class, int.class, long.class, Throwable.class)
                .invoke(null, MODEL, generation, failedStamp,
                        new IllegalStateException("Expected conversion lifecycle test failure"));
    }

    private static void completeInterruption(int generation) throws Exception {
        handler("completeConversionInterruption", String.class, int.class)
                .invoke(null, MODEL, generation);
    }

    private static Method handler(String name, Class<?>... parameterTypes) throws Exception {
        Method method = CombatMeshCache.class.getDeclaredMethod(name, parameterTypes);
        method.setAccessible(true);
        return method;
    }

    private static Object field(String name) throws Exception {
        Field field = CombatMeshCache.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(null);
    }

    /** Simulates Epic Fight's constructor submissions, using the production fence scheduler. */
    private static final class CompletionQueues {
        private final Queue<Runnable> render = new ArrayDeque<>();
        private final Queue<Runnable> client = new ArrayDeque<>();
        private final List<String> events = new ArrayList<>();
        private final List<String> initializations;

        private CompletionQueues(boolean glow) {
            this(glow ? List.of("base:init", "glow:init") : List.of("base:init"));
        }

        private CompletionQueues(List<String> initializations) {
            this.initializations = List.copyOf(initializations);
            initializations.forEach(event -> render.add(() -> events.add(event)));
        }

        private void enqueue(Runnable completion) {
            CombatMeshCache.queueConversionCompletion(render::add, client::add, completion);
        }

        private void drainRender() {
            drain(render);
        }

        private void drainClient() {
            drain(client);
        }

        private static void drain(Queue<Runnable> queue) {
            Runnable task;
            while ((task = queue.poll()) != null) {
                task.run();
            }
        }

        private List<String> withCompletion(String completion) {
            List<String> result = new ArrayList<>(initializations);
            result.add(completion);
            return result;
        }
    }

    private static final class Fixture implements AutoCloseable {
        private final AtomicInteger generation;
        private final int previousGeneration;
        private final Set<String> pending;
        private final Set<String> previousPending;
        private final Map<String, Long> failed;
        private final Map<String, Long> previousFailed;

        @SuppressWarnings("unchecked")
        private Fixture() throws Exception {
            generation = (AtomicInteger) field("GENERATION");
            pending = (Set<String>) field("PENDING_MODELS");
            failed = (Map<String, Long>) field("FAILED_STAMPS");
            previousGeneration = generation.get();
            previousPending = new HashSet<>(pending);
            previousFailed = new HashMap<>(failed);
            generation.set(CURRENT_GENERATION);
            pending.clear();
            failed.clear();
        }

        @Override
        public void close() {
            pending.clear();
            pending.addAll(previousPending);
            failed.clear();
            failed.putAll(previousFailed);
            generation.set(previousGeneration);
        }
    }

    private static Map<String, MethodCode> readMethods() throws IOException {
        Map<String, MethodCode> methods = new HashMap<>();
        try (InputStream stream = CombatMeshCacheLifecycleTest.class.getClassLoader()
                .getResourceAsStream(CACHE + ".class")) {
            assertNotNull(stream);
            new ClassReader(stream).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                 String signature, String[] exceptions) {
                    MethodCode method = new MethodCode(access);
                    methods.put(name, method);
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override
                        public void visitFieldInsn(int opcode, String owner, String name,
                                                   String descriptor) {
                            if (CACHE.equals(owner) && (name.equals("PENDING_MODELS")
                                    || name.equals("FAILED_STAMPS"))) {
                                method.lifecycleFields.add(name);
                            }
                        }

                        @Override
                        public void visitMethodInsn(int opcode, String owner, String name,
                                                    String descriptor, boolean isInterface) {
                            method.calls.add(owner + '#' + name);
                        }

                        @Override
                        public void visitInvokeDynamicInsn(String name, String descriptor,
                                                           Handle bootstrap, Object... arguments) {
                            for (Object argument : arguments) {
                                if (argument instanceof Handle handle && CACHE.equals(handle.getOwner())) {
                                    method.lambdaTargets.add(handle.getName());
                                }
                            }
                        }
                    };
                }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }
        return methods;
    }

    private static final class MethodCode {
        private final int access;
        private final List<String> calls = new ArrayList<>();
        private final Set<String> lifecycleFields = new HashSet<>();
        private final List<String> lambdaTargets = new ArrayList<>();

        private MethodCode(int access) {
            this.access = access;
        }
    }
}
