package net.okitsu.ysmepicfightcompat.mesh;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Checks production wiring without a Minecraft window, native image, or GL context. */
class CombatTextureUploadContractTest {
    private static final String CACHE = "net/okitsu/ysmepicfightcompat/mesh/CombatMeshCache";
    private static final String QUEUE = "net/okitsu/ysmepicfightcompat/mesh/FrameUploadQueue";
    private static final String EVENTS = "net/okitsu/ysmepicfightcompat/event/ClientMaintenanceEvents";

    @Test
    void uploadDrainIsHookedOnlyToRenderFrameStart() throws IOException {
        Map<String, MethodCode> events = readMethods(EVENTS);
        MethodCode frame = events.get("renderFrame");
        assertNotNull(frame);
        assertEquals("(Lnet/minecraftforge/event/TickEvent$RenderTickEvent;)V", frame.descriptor);
        assertTrue(frame.annotations.contains("Lnet/minecraftforge/eventbus/api/SubscribeEvent;"));
        assertTrue(frame.fields.contains("net/minecraftforge/event/TickEvent$Phase#START"));
        assertEquals(1, frame.calls.stream()
                .filter(call -> call.equals(CACHE + "#uploadReadyTextures")).count());
        for (var entry : events.entrySet()) {
            if (!entry.getKey().equals("renderFrame")) {
                assertFalse(entry.getValue().calls.contains(CACHE + "#uploadReadyTextures"),
                        "No tick/disconnect/reload handler may open another upload budget");
            }
        }
    }

    @Test
    void workersOnlyPrepareOrQueueAndNeverScheduleAnotherDrain() throws IOException {
        Map<String, MethodCode> cache = readMethods(CACHE);
        for (var entry : cache.entrySet()) {
            assertFalse(entry.getValue().calls.contains(CACHE + "#uploadReadyTextures"), entry.getKey());
            assertFalse(entry.getValue().lambdaTargets.contains("uploadReadyTextures"), entry.getKey());
        }
        for (String name : List.of("requestTextureUpload", "prepareTextureUpload", "decodeTextureUpload")) {
            MethodCode worker = cache.get(name);
            assertNotNull(worker);
            assertFalse(worker.calls.contains("net/minecraft/client/Minecraft#execute"), name);
            assertFalse(worker.calls.contains(QUEUE + "#drainFrame"), name);
        }
        assertTrue(cache.get("prepareTextureUpload").calls.contains(QUEUE + "#plan"));
        assertTrue(cache.get("decodeTextureUpload").calls.contains(QUEUE + "#complete"));
        MethodCode drain = cache.get("uploadReadyTextures");
        assertTrue(drain.calls.contains("com/mojang/blaze3d/systems/RenderSystem#assertOnRenderThread"));
        assertTrue(drain.calls.contains(QUEUE + "#drainFrame"));
        assertTrue(drain.calls.contains(QUEUE + "#startAvailable"));
        assertFalse(drain.calls.contains("net/minecraft/client/Minecraft#execute"));
    }

    @Test
    void reloadEvictionAndOfficialTextureAdoptionRetirePendingFallbackUploads() throws IOException {
        Map<String, MethodCode> cache = readMethods(CACHE);
        assertTrue(cache.get("clear").calls.contains(QUEUE + "#clear"));
        assertTrue(cache.get("discardFallbacks").calls.contains(QUEUE + "#cancel"));
        MethodCode release = cache.get("releaseUploadedFallbacks");
        assertTrue(release.lambdaTargets.stream()
                .map(cache::get).anyMatch(method -> method.calls.contains(QUEUE + "#cancel")));
    }

    @Test
    void metadataReservationChecksDimensionsAndMultiplicationBeforeNativeAllocation() throws Exception {
        Method rgbaBytes = CombatMeshCache.class.getDeclaredMethod("rgbaBytes", int.class, int.class);
        rgbaBytes.setAccessible(true);
        assertEquals(4L * 1024 * 2048, rgbaBytes.invoke(null, 1024, 2048));
        assertEquals(4L * Integer.MAX_VALUE, rgbaBytes.invoke(null, Integer.MAX_VALUE, 1));
        for (int[] dimensions : List.of(new int[] { 0, 1 }, new int[] { 1, -1 },
                new int[] { Integer.MAX_VALUE, Integer.MAX_VALUE })) {
            InvocationTargetException failure = assertThrows(InvocationTargetException.class,
                    () -> rgbaBytes.invoke(null, dimensions[0], dimensions[1]));
            assertInstanceOf(IOException.class, failure.getCause());
        }
    }

    @Test
    void pbrReservationIncludesBothSnapshotsAndTheTemporaryCopy() throws Exception {
        Method peak = CombatMeshCache.class.getDeclaredMethod(
                "textureUploadPeakBytes", long.class, long.class, long.class);
        peak.setAccessible(true);
        assertEquals(20L, peak.invoke(null, 20L, 0L, 0L));
        assertEquals(80L, peak.invoke(null, 20L, 30L, 0L));
        assertEquals(100L, peak.invoke(null, 20L, 0L, 40L));
        assertEquals(130L, peak.invoke(null, 20L, 30L, 40L));
        assertEquals(130L, peak.invoke(null, 20L, 40L, 30L));
        InvocationTargetException failure = assertThrows(InvocationTargetException.class,
                () -> peak.invoke(null, Long.MAX_VALUE, 1L, 0L));
        assertInstanceOf(ArithmeticException.class, failure.getCause());

        MethodCode prepare = readMethods(CACHE).get("prepareTextureUpload");
        assertTrue(prepare.calls.contains(CACHE + "#textureUploadPeakBytes"));
        assertTrue(prepare.calls.contains(CACHE + "#decodedTextureBytes"));
        assertEquals(2, prepare.calls.stream()
                .filter(call -> call.equals(CACHE + "#preparePbrTexture")).count());
    }

    private static Map<String, MethodCode> readMethods(String owner) throws IOException {
        Map<String, MethodCode> methods = new HashMap<>();
        try (InputStream stream = CombatTextureUploadContractTest.class.getClassLoader()
                .getResourceAsStream(owner + ".class")) {
            assertNotNull(stream);
            new ClassReader(stream).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor,
                        String signature, String[] exceptions) {
                    MethodCode method = new MethodCode(descriptor);
                    methods.put(name, method);
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override
                        public org.objectweb.asm.AnnotationVisitor visitAnnotation(
                                String descriptor, boolean visible) {
                            method.annotations.add(descriptor);
                            return null;
                        }

                        @Override
                        public void visitFieldInsn(int opcode, String owner, String name,
                                String descriptor) {
                            method.fields.add(owner + '#' + name);
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
                                if (argument instanceof Handle handle && owner.equals(handle.getOwner())) {
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
        private final String descriptor;
        private final List<String> annotations = new ArrayList<>();
        private final List<String> fields = new ArrayList<>();
        private final List<String> calls = new ArrayList<>();
        private final List<String> lambdaTargets = new ArrayList<>();

        private MethodCode(String descriptor) {
            this.descriptor = descriptor;
        }
    }
}
