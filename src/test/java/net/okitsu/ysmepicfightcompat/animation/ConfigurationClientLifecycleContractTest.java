package net.okitsu.ysmepicfightcompat.animation;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Label;
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

/** Checks the live-player boundary without constructing Minecraft entities or touching GL. */
class ConfigurationClientLifecycleContractTest {
    private static final String ROOT = "net/okitsu/ysmepicfightcompat/";
    private static final String OWNER = ROOT + "animation/OfficialConfigurationVariables";
    private static final String MINECRAFT = "net/minecraft/client/Minecraft";

    @Test
    void aReplacementCannotReadTheOldLocalOverlayBeforeTheNextClientTick() throws IOException {
        Code lookup = read("lookup");
        int state = lookup.index(Opcodes.GETSTATIC, OWNER, "STATES");
        assertTrue(lookup.index(Opcodes.GETFIELD, MINECRAFT, "player") < state);
        assertTrue(lookup.index(Opcodes.GETSTATIC, OWNER, "localPlayer") < state);
        assertTrue(lookup.steps.subList(0, state).stream().filter(Step::identityJump).count() >= 2);
        assertTrue(lookup.steps.subList(0, state).stream().anyMatch(step -> step.opcode == Opcodes.ARETURN));
        assertFalse(lookup.hasCall("java/util/Map", "remove"));
        assertFalse(lookup.hasCall(OWNER, "bindLocal"), "A read must not reset a newer state");
    }

    @Test
    void aLateLeaveOrRegistrationOfAnOldEntityCannotResetTheCurrentPlayersValues() throws IOException {
        Code reset = read("reset");
        int remove = reset.index(Opcodes.INVOKEINTERFACE, "java/util/Map", "remove");
        int currentPlayer = reset.steps.stream().filter(step -> "getPlayerByUUID".equals(step.name))
                .mapToInt(reset.steps::indexOf).findFirst().orElseThrow();
        assertTrue(currentPlayer < remove);
        assertTrue(reset.steps.subList(currentPlayer, remove).stream().anyMatch(Step::identityJump));
        assertTrue(reset.steps.subList(currentPlayer, remove).stream()
                .anyMatch(step -> step.opcode == Opcodes.RETURN));
        assertTrue(reset.index(Opcodes.GETSTATIC, OWNER, "localPlayer") < remove);
    }

    @Test
    void bindingANewLocalPlayerDiscardsOldPredictionsBeforePublishingTheNewIdentity() throws IOException {
        Code bind = read("bindLocal");
        int assign = bind.index(Opcodes.PUTSTATIC, OWNER, "localPlayer");
        long removals = bind.steps.subList(0, assign).stream().filter(step ->
                step.matches(Opcodes.INVOKEINTERFACE, "java/util/Map", "remove")).count();
        assertEquals(2, removals, "Drop the old UUID and any prior snapshot for the replacement UUID");
        assertTrue(bind.steps.subList(0, assign).stream().anyMatch(Step::identityJump));
        assertTrue(bind.index(Opcodes.PUTSTATIC, OWNER, "scopeRequest") > assign);
        assertTrue(bind.index(Opcodes.PUTSTATIC, OWNER, "retryTicks") > assign);
        assertFalse(bind.hasCall(ROOT + "network/CompatNetwork", "sendConfigurationUpdate"));
    }

    @Test
    void lifecycleCleanupOnlyDropsTheCompatCopyAndNeverWritesZeroOrResetsOfficialInitialization()
            throws IOException {
        for (String method : List.of("reset", "bindLocal", "clear")) {
            Code code = read(method);
            assertFalse(code.steps.stream().anyMatch(step ->
                    "writeVariable".equals(step.name) || "writeRoaming".equals(step.name)
                            || "evaluate".equals(step.name)), method);
            for (String target : List.of("OfficialRoamingVariables", "EntityAnimationEnvironment",
                    "EntityScriptRuntime")) {
                assertFalse(code.steps.stream().anyMatch(step ->
                        (ROOT + "animation/" + target).equals(step.owner)), method + ": " + target);
            }
        }
    }

    private static Code read(String name) throws IOException {
        Code code = new Code();
        try (InputStream input = ConfigurationClientLifecycleContractTest.class.getClassLoader()
                .getResourceAsStream(OWNER + ".class")) {
            assertNotNull(input);
            new ClassReader(input).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public MethodVisitor visitMethod(int access, String method, String descriptor,
                                                 String signature, String[] exceptions) {
                    if (!name.equals(method)) {
                        return null;
                    }
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override
                        public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
                            code.steps.add(new Step(opcode, owner, name));
                        }

                        @Override
                        public void visitMethodInsn(int opcode, String owner, String name,
                                                    String descriptor, boolean isInterface) {
                            code.steps.add(new Step(opcode, owner, name));
                        }

                        @Override
                        public void visitInsn(int opcode) {
                            code.steps.add(new Step(opcode, null, null));
                        }

                        @Override
                        public void visitJumpInsn(int opcode, Label label) {
                            code.steps.add(new Step(opcode, null, null));
                        }
                    };
                }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }
        assertFalse(code.steps.isEmpty(), name);
        return code;
    }

    private record Step(int opcode, String owner, String name) {
        boolean identityJump() {
            return opcode == Opcodes.IF_ACMPEQ || opcode == Opcodes.IF_ACMPNE;
        }

        boolean matches(int expectedOpcode, String expectedOwner, String expectedName) {
            return opcode == expectedOpcode && expectedOwner.equals(owner) && expectedName.equals(name);
        }
    }

    private static final class Code {
        private final List<Step> steps = new ArrayList<>();

        int index(int opcode, String owner, String name) {
            int index = steps.indexOf(new Step(opcode, owner, name));
            assertTrue(index >= 0, owner + "#" + name);
            return index;
        }

        boolean hasCall(String owner, String name) {
            return steps.stream().anyMatch(step -> owner.equals(step.owner) && name.equals(step.name));
        }
    }
}
