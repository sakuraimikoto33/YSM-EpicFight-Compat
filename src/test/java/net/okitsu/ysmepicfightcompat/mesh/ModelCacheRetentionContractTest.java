package net.okitsu.ysmepicfightcompat.mesh;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies retention wiring without starting Minecraft or deleting real render resources. */
class ModelCacheRetentionContractTest {
    private static final String ROOT = "net/okitsu/ysmepicfightcompat/";
    private static final String CACHE = ROOT + "mesh/CombatMeshCache";
    private static final String PREFERENCES = ROOT + "network/ClientSubEntityModelPreferences";

    @Test
    void maintenanceFollowsSelectionObservationAndPendingQueryRetriesAtTickEnd() throws IOException {
        Code tick = method(read(ROOT + "event/ClientMaintenanceEvents"), "clientTick");
        int observe = tick.calls.indexOf(CACHE + "#advanceAnimationOutputs");
        int query = tick.calls.indexOf(PREFERENCES + "#tickSync");
        int maintain = tick.calls.indexOf(CACHE + "#maintainModelCache");
        assertTrue(observe >= 0 && observe < query && query < maintain);
        assertEquals(1, tick.calls.stream().filter(call -> call.equals(
                CACHE + "#maintainModelCache")).count());
        assertTrue(tick.fields.contains("net/minecraftforge/event/TickEvent$Phase#END"));
    }

    @Test
    void newLoadsAndLookupsAreNeverRejectedOrEvictedBecauseOfTheTarget() throws IOException {
        List<Code> methods = read(CACHE);
        for (Code code : methods) {
            if (!List.of("ensure", "find", "register", "completeConversion").contains(code.name)) {
                continue;
            }
            assertFalse(code.calls.contains(CACHE + "#maintainModelCache"), code.name);
            assertFalse(code.calls.contains(CACHE + "#evict"), code.name);
            assertFalse(code.lambdas.contains(CACHE + "#evict"), code.name);
            assertFalse(code.fields.contains(ROOT
                    + "config/ClientPreferences#CLIENT_MODEL_MEMORY_CACHE_TARGET_COUNT"), code.name);
        }
        assertTrue(methods.stream().filter(code -> code.name.equals("ensure"))
                .anyMatch(code -> code.calls.contains("java/util/concurrent/ExecutorService#execute")));
    }

    @Test
    void onlyTheSafeMaintenancePointChoosesLruVictims() throws IOException {
        List<Code> methods = read(CACHE);
        Code maintain = method(methods, "maintainModelCache");
        int renderThread = maintain.calls.indexOf(
                "com/mojang/blaze3d/systems/RenderSystem#isOnRenderThread");
        int pins = maintain.calls.indexOf(CACHE + "#protectedModelIds");
        int victims = maintain.calls.indexOf(ROOT + "mesh/ModelCacheRetention#victims");
        assertTrue(renderThread >= 0 && renderThread < pins && pins < victims);
        assertTrue(maintain.lambdas.contains(CACHE + "#evict"));
        for (Code code : methods) {
            if (!code.name.equals("maintainModelCache")) {
                assertFalse(code.calls.contains(ROOT + "mesh/ModelCacheRetention#victims"), code.name);
                assertFalse(code.calls.contains(CACHE + "#evict"), code.name);
                assertFalse(code.lambdas.contains(CACHE + "#evict"), code.name);
            }
        }
    }

    @Test
    void protectionIncludesPendingQueriesAndChecksLiveEntityIdentityNotVisibility() throws IOException {
        List<Code> methods = read(CACHE);
        Code protectedModels = method(methods, "protectedModelIds");
        assertTrue(protectedModels.calls.contains(PREFERENCES + "#pendingModelIds"));
        assertTrue(protectedModels.fields.contains(CACHE + "#ENTITY_MODELS"));
        List<Code> pinCode = methods.stream().filter(code -> code.name.equals("protectedModelIds")
                || code.name.startsWith("lambda$protectedModelIds$")).toList();
        assertTrue(pinCode.stream().anyMatch(code -> code.calls.stream()
                .anyMatch(call -> call.endsWith("#isRemoved"))));
        assertTrue(pinCode.stream().anyMatch(code -> code.calls.stream()
                .anyMatch(call -> call.endsWith("#getEntity"))));
        assertTrue(pinCode.stream().anyMatch(code -> code.opcodes.contains(Opcodes.IF_ACMPNE)
                || code.opcodes.contains(Opcodes.IF_ACMPEQ)));
        for (Code code : pinCode) {
            assertFalse(code.calls.stream().anyMatch(call -> call.contains("Frustum")
                    || call.contains("EpicFightPoseOwnership") || call.endsWith("#isBattleMode")));
            assertFalse(code.calls.contains(CACHE + "#ensure"));
            assertFalse(code.calls.contains(CACHE + "#find"));
            assertFalse(code.calls.contains(CACHE + "#markUsed"));
        }
    }

    @Test
    void offscreenSelectionObservationPrecedesAnyReadyMeshCheck() throws IOException {
        Code advance = method(read(CACHE), "advanceAnimationOutputs");
        assertTrue(advance.calls.stream().anyMatch(call -> call.endsWith("#players")));
        assertTrue(advance.calls.stream().anyMatch(call -> call.endsWith("#entitiesForRendering")));
        int observed = 0;
        int read = 0;
        for (String call : advance.calls) {
            if (call.equals(CACHE + "#observeEntitySelection")) {
                observed++;
            } else if (call.equals(CACHE + "#readyMesh")) {
                assertTrue(observed > read, "Both player and adapter selections are pinned before readiness");
                read++;
            }
        }
        assertEquals(2, observed);
        assertEquals(2, read);
    }

    @Test
    void unusedVictimsStillReceiveCompleteResourceAndAnimationCleanup() throws IOException {
        Code evict = method(read(CACHE), "evict");
        assertTrue(evict.calls.contains(ROOT + "mesh/CompatHumanoidMesh#releaseAllAnimationStates"));
        assertTrue(evict.calls.contains(ROOT + "mesh/CompatHumanoidMesh#destroy"));
        assertTrue(evict.calls.contains(CACHE + "#discardFallbacks"));
        assertTrue(evict.calls.contains(ROOT + "assets/OfficialTextureResolver#release"));
        assertTrue(evict.calls.contains(ROOT + "animation/ParallelAnimationProgram#releaseSoundOutput"));
        Code clear = method(read(CACHE), "clear");
        assertTrue(clear.fields.contains(CACHE + "#ENTITY_MODELS"));
        assertTrue(clear.fields.contains(CACHE + "#RECENCY"));
    }

    private static Code method(List<Code> methods, String name) {
        List<Code> matches = methods.stream().filter(code -> code.name.equals(name)).toList();
        assertEquals(1, matches.size(), name);
        return matches.get(0);
    }

    private static List<Code> read(String owner) throws IOException {
        List<Code> methods = new ArrayList<>();
        try (InputStream in = ModelCacheRetentionContractTest.class.getClassLoader()
                .getResourceAsStream(owner + ".class")) {
            assertNotNull(in, owner);
            new ClassReader(in).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                 String signature, String[] exceptions) {
                    Code code = new Code(name);
                    methods.add(code);
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override
                        public void visitMethodInsn(int opcode, String type, String name,
                                                    String descriptor, boolean isInterface) {
                            code.calls.add(type + "#" + name);
                        }

                        @Override
                        public void visitFieldInsn(int opcode, String type, String name, String descriptor) {
                            code.fields.add(type + "#" + name);
                        }

                        @Override
                        public void visitJumpInsn(int opcode, org.objectweb.asm.Label target) {
                            code.opcodes.add(opcode);
                        }

                        @Override
                        public void visitInvokeDynamicInsn(String name, String descriptor, Handle bootstrap,
                                                           Object... arguments) {
                            for (Object argument : arguments) {
                                if (argument instanceof Handle target) {
                                    code.lambdas.add(target.getOwner() + "#" + target.getName());
                                }
                            }
                        }
                    };
                }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }
        return methods;
    }

    private static final class Code {
        private final String name;
        private final List<String> calls = new ArrayList<>();
        private final List<String> fields = new ArrayList<>();
        private final List<String> lambdas = new ArrayList<>();
        private final List<Integer> opcodes = new ArrayList<>();

        private Code(String name) {
            this.name = name;
        }
    }
}
