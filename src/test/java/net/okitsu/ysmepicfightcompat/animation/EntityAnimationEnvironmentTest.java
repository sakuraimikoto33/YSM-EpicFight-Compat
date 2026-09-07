package net.okitsu.ysmepicfightcompat.animation;

import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.Vec3;
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
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EntityAnimationEnvironmentTest {
    @Test
    void previewEffectsCannotBeEnabledByAnAnimationSoundScope() {
        assertFalse(EntityAnimationEnvironment.permitsExternalEffects(true));
        assertTrue(EntityAnimationEnvironment.permitsExternalEffects(false));
    }

    @Test
    void onlyAssignedPreviewRoamingValuesOverrideTheSharedRead() {
        assertTrue(EntityAnimationEnvironment.prefersPreviewRoamingValue(
                true, "v.roaming.choice", true));
        assertTrue(EntityAnimationEnvironment.prefersPreviewRoamingValue(
                true, "variable.roaming.choice", true));
        assertFalse(EntityAnimationEnvironment.prefersPreviewRoamingValue(
                true, "v.roaming.choice", false));
        assertFalse(EntityAnimationEnvironment.prefersPreviewRoamingValue(
                false, "v.roaming.choice", true));
        assertFalse(EntityAnimationEnvironment.prefersPreviewRoamingValue(
                true, "v.configuration", true));
        assertFalse(EntityAnimationEnvironment.prefersPreviewRoamingValue(
                true, "v.roaming.", true));
    }

    @Test
    void everyExternalEffectCallIsUnreachableWhenPreviewPermissionIsFalse() throws IOException {
        List<EffectMethod> methods = effectMethods();
        Set<String> guardedMethods = new HashSet<>();
        for (EffectMethod method : methods) {
            if (method.steps.stream().noneMatch(EffectStep::externalEffect)) continue;
            guardedMethods.add(method.name);
            assertFalse(method.reachesExternalEffect(false), method.name);
            assertTrue(method.reachesExternalEffect(true),
                    "The normal world path must remain reachable: " + method.name);
        }
        assertEquals(Set.of("update", "playSoundEffect", "stopSoundScope",
                "playParticleEffect", "stopParticleScope", "reset", "writeVariable",
                "playSound", "claimAttackSound", "stopSound", "stopAllSounds", "particle"),
                guardedMethods);
    }

    @Test
    void limitsAttackSoundOwnershipToSwingScopesAndTheMatchingHand() {
        assertEquals(InteractionHand.MAIN_HAND,
                EntityAnimationEnvironment.attackHandForScope(
                        "controller/player.post_swing/state/1/0",
                        Set.of(InteractionHand.MAIN_HAND)));
        assertEquals(InteractionHand.OFF_HAND,
                EntityAnimationEnvironment.attackHandForScope(
                        "controller/player.post_swing_offhand/state/1/0",
                        Set.of(InteractionHand.MAIN_HAND, InteractionHand.OFF_HAND)));
        assertNull(EntityAnimationEnvironment.attackHandForScope(
                "controller/player.post_hold/state/1/0",
                Set.of(InteractionHand.MAIN_HAND)));
        assertNull(EntityAnimationEnvironment.attackHandForScope(
                "swing:sword", Set.of(InteractionHand.OFF_HAND)));
    }

    @Test
    void matchesOfficialYsmHeadQueryDirections() {
        assertEquals(-30.0F, EntityAnimationEnvironment.officialHeadPitch(30.0F));
        assertEquals(25.0F, EntityAnimationEnvironment.officialHeadPitch(-25.0F));
        assertEquals(-45.0F,
                EntityAnimationEnvironment.officialHeadYaw(65.0F, 20.0F));
        assertEquals(-85.0F,
                EntityAnimationEnvironment.officialHeadYaw(170.0F, 0.0F));
        assertEquals(-10.0F,
                EntityAnimationEnvironment.officialInterpolatedHeadYaw(
                        170.0F, -170.0F, 0.5F, 170.0F));
    }

    @Test
    void customBowAimUsesProjectileYawLocallyAndInterpolatedViewRemotely() {
        assertEquals(35.0F, EntityAnimationEnvironment.customBowAimYaw(
                35.0F, 28.0F, true));
        assertEquals(28.0F, EntityAnimationEnvironment.customBowAimYaw(
                35.0F, 28.0F, false));
    }

    @Test
    void customBowAimUsesEpicFightsActualOuterModelYaw() {
        assertEquals(0.0F,
                EntityAnimationEnvironment.customBowRelativeHeadYaw(
                        190.0F, 185.0F, true, 190.0F), 0.00001F);
        assertEquals(5.0F,
                EntityAnimationEnvironment.customBowRelativeHeadYaw(
                        190.0F, 185.0F, false, 190.0F), 0.00001F);
        assertEquals(-85.0F,
                EntityAnimationEnvironment.customBowRelativeHeadYaw(
                        -170.0F, -170.0F, true, 95.0F), 0.00001F);
        assertEquals(85.0F,
                EntityAnimationEnvironment.customBowRelativeHeadYaw(
                        170.0F, 170.0F, true, -95.0F), 0.00001F);
    }

    @Test
    void yawSpeedUsesTheShortestWrappedTickDelta() {
        assertEquals(200.0F, EntityAnimationEnvironment.yawSpeed(10.0F, 0.0F));
        assertEquals(400.0F, EntityAnimationEnvironment.yawSpeed(-170.0F, 170.0F));
    }

    @Test
    void officialAngleMathPreservesFractionsAndUsesTheShortestArc() {
        assertEquals(60.25D, EntityAnimationEnvironment.minimumAngle(780.25D),
                1.0E-12D);
        assertEquals(-179.75D, EntityAnimationEnvironment.minimumAngle(180.25D),
                1.0E-12D);
        assertEquals(360.0D, EntityAnimationEnvironment.lerpRotate(350.0D, 10.0D, 0.5D),
                1.0E-12D);
        assertEquals(0.0D, EntityAnimationEnvironment.lerpRotate(10.0D, 350.0D, 0.5D),
                1.0E-12D);
    }

    @Test
    void dieRollFunctionsAreBoundedAndIntegerRollsIncludeBothEnds() {
        Random minimum = new Random() {
            @Override
            public double nextDouble() {
                return 0.0D;
            }
        };
        Random maximum = new Random() {
            @Override
            public double nextDouble() {
                return Math.nextDown(1.0D);
            }
        };

        assertEquals(6.0D, EntityAnimationEnvironment.dieRoll(minimum,
                new double[]{3.0D, 2.0D, 5.0D}, false), 1.0E-12D);
        assertEquals(15.0D, EntityAnimationEnvironment.dieRoll(maximum,
                new double[]{3.0D, 2.0D, 5.0D}, true), 1.0E-12D);
        assertEquals(0.0D, EntityAnimationEnvironment.dieRoll(minimum,
                new double[]{-1.0D, 2.0D, 5.0D}, true), 1.0E-12D);
    }

    @Test
    void timeAndCardinalQueriesMatchOfficialYsmConventions() {
        assertEquals(0.0D, EntityAnimationEnvironment.timeOfDay(18000L), 1.0E-12D);
        assertEquals(0.25D, EntityAnimationEnvironment.timeOfDay(0L), 1.0E-12D);
        assertEquals(0.5D, EntityAnimationEnvironment.timeOfDay(6000L), 1.0E-12D);
        assertEquals(0.75D, EntityAnimationEnvironment.timeOfDay(12000L), 1.0E-12D);
        assertEquals(2.0D, EntityAnimationEnvironment.cardinalFacing2d(Direction.NORTH));
        assertEquals(3.0D, EntityAnimationEnvironment.cardinalFacing2d(Direction.SOUTH));
        assertEquals(4.0D, EntityAnimationEnvironment.cardinalFacing2d(Direction.WEST));
        assertEquals(5.0D, EntityAnimationEnvironment.cardinalFacing2d(Direction.EAST));
    }

    @Test
    void cameraRotationReturnsWorldPitchAndYawWithAttachedCameraFallback() {
        Vec3 origin = new Vec3(0.0D, 1.0D, 0.0D);
        assertEquals(0.0D, EntityAnimationEnvironment.rotationToCamera(origin,
                new Vec3(0.0D, 1.0D, 5.0D), 12.0F, 34.0F, 0), 1.0E-12D);
        assertEquals(-90.0D, EntityAnimationEnvironment.rotationToCamera(origin,
                new Vec3(5.0D, 1.0D, 0.0D), 12.0F, 34.0F, 1), 1.0E-12D);
        assertEquals(12.0D, EntityAnimationEnvironment.rotationToCamera(origin,
                origin, 12.0F, 34.0F, 0), 1.0E-12D);
        assertEquals(34.0D, EntityAnimationEnvironment.rotationToCamera(origin,
                origin, 12.0F, 34.0F, 1), 1.0E-12D);
    }

    @Test
    void relativeBlockQueriesRejectOffsetsOutsideYsmSafetyLimit() {
        assertEquals(8, EntityAnimationEnvironment.relativeOffset(8.0D));
        assertEquals(-8, EntityAnimationEnvironment.relativeOffset(-8.0D));
        assertNull(EntityAnimationEnvironment.relativeOffset(9.0D));
        assertNull(EntityAnimationEnvironment.relativeOffset(Double.NaN));
    }

    /** Verifies the compiled boundary without constructing a bootstrapped Minecraft entity. */
    private static List<EffectMethod> effectMethods() throws IOException {
        List<EffectMethod> methods = new ArrayList<>();
        String type = "net/okitsu/ysmepicfightcompat/animation/EntityAnimationEnvironment";
        try (InputStream stream = EntityAnimationEnvironmentTest.class.getClassLoader()
                .getResourceAsStream(type + ".class")) {
            assertNotNull(stream);
            new ClassReader(stream).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                 String signature, String[] exceptions) {
                    EffectMethod method = new EffectMethod(name, type);
                    methods.add(method);
                    return method;
                }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }
        return methods;
    }

    private record EffectStep(int opcode, List<Label> targets,
                              boolean permission, boolean externalEffect) { }
    private record EffectPosition(int index, Boolean permission) { }

    private static final class EffectMethod extends MethodVisitor {
        private final String name;
        private final String environmentType;
        private final List<EffectStep> steps = new ArrayList<>();
        private final Map<Label, Integer> labels = new IdentityHashMap<>();

        private EffectMethod(String name, String environmentType) {
            super(Opcodes.ASM9);
            this.name = name;
            this.environmentType = environmentType;
        }

        @Override public void visitLabel(Label label) { labels.put(label, steps.size()); }

        @Override
        public void visitMethodInsn(int opcode, String owner, String method,
                                    String descriptor, boolean isInterface) {
            String prefix = "net/okitsu/ysmepicfightcompat/animation/";
            boolean external = owner.equals(prefix + "ClientParticleOutput")
                    || owner.equals(prefix + "AttackSoundOwnership")
                    || owner.equals(prefix + "ClientSoundOutput")
                    && !Set.of("request", "identifier").contains(method)
                    || owner.equals(prefix + "OfficialRoamingVariables$View")
                    && method.equals("writeRoaming");
            steps.add(new EffectStep(opcode, List.of(),
                    owner.equals(environmentType) && method.equals("permitsExternalEffects"), external));
        }

        @Override
        public void visitJumpInsn(int opcode, Label target) {
            steps.add(new EffectStep(opcode, List.of(target), false, false));
        }

        @Override
        public void visitInsn(int opcode) {
            if (opcode >= Opcodes.IRETURN && opcode <= Opcodes.RETURN || opcode == Opcodes.ATHROW) {
                steps.add(new EffectStep(opcode, List.of(), false, false));
            }
        }

        @Override
        public void visitTableSwitchInsn(int minimum, int maximum, Label fallback, Label... targets) {
            addSwitch(fallback, targets);
        }

        @Override
        public void visitLookupSwitchInsn(Label fallback, int[] keys, Label[] targets) {
            addSwitch(fallback, targets);
        }

        private void addSwitch(Label fallback, Label[] targets) {
            List<Label> all = new ArrayList<>(List.of(targets));
            all.add(fallback);
            steps.add(new EffectStep(Opcodes.TABLESWITCH, all, false, false));
        }

        private boolean reachesExternalEffect(boolean permission) {
            ArrayDeque<EffectPosition> pending = new ArrayDeque<>();
            Set<EffectPosition> visited = new HashSet<>();
            pending.add(new EffectPosition(0, null));
            while (!pending.isEmpty()) {
                EffectPosition position = pending.removeFirst();
                if (position.index >= steps.size() || !visited.add(position)) continue;
                EffectStep step = steps.get(position.index);
                if (step.externalEffect) return true;
                if (step.permission) {
                    pending.add(new EffectPosition(position.index + 1, permission));
                } else if (step.opcode >= Opcodes.IRETURN && step.opcode <= Opcodes.RETURN
                        || step.opcode == Opcodes.ATHROW) {
                    // No normal successor.
                } else if (!step.targets.isEmpty()) {
                    Boolean jump = position.permission == null ? null
                            : step.opcode == Opcodes.IFEQ ? !position.permission
                            : step.opcode == Opcodes.IFNE ? position.permission : null;
                    if (jump == null || jump) {
                        for (Label target : step.targets) {
                            pending.add(new EffectPosition(labels.get(target), null));
                        }
                    }
                    if (step.opcode != Opcodes.GOTO && step.opcode != Opcodes.TABLESWITCH
                            && (jump == null || !jump)) {
                        pending.add(new EffectPosition(position.index + 1, null));
                    }
                } else {
                    pending.add(new EffectPosition(position.index + 1, null));
                }
            }
            return false;
        }
    }
}
