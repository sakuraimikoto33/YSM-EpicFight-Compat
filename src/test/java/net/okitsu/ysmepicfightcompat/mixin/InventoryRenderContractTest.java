package net.okitsu.ysmepicfightcompat.mixin;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/** Inspects the packaged render boundaries without initializing Minecraft or Mixin. */
class InventoryRenderContractTest {
    private static final String ROOT = "net/okitsu/ysmepicfightcompat/";
    private static final String MIXIN = ROOT + "mixin/InventoryRenderScopeMixin";
    private static final String SCOPE = ROOT + "render/InventoryRenderScope";
    private static final String FRAME = ROOT + "render/RenderFrameContext$Frame";
    private static final String PROGRAM = ROOT + "animation/ParallelAnimationProgram";
    private static final String MESH = ROOT + "mesh/CompatHumanoidMesh";
    private static final String INVENTORY = "net/minecraft/client/gui/screens/inventory/InventoryScreen";
    private static final String RENDER_SYSTEM = "com/mojang/blaze3d/systems/RenderSystem";
    private static final String ENTITY = "Lnet/minecraft/world/entity/LivingEntity;";
    private static final String PREVIEW_DESCRIPTOR = "(Lnet/minecraft/client/gui/GuiGraphics;III"
            + "Lorg/joml/Quaternionf;Lorg/joml/Quaternionf;" + ENTITY + ")V";
    private static final String PREVIEW_SELECTOR = "renderEntityInInventory" + PREVIEW_DESCRIPTOR;

    @Test
    void redirectMatchesTheExactVanillaPreviewAndFancyCall() throws IOException {
        ClassInfo mixin = read(MIXIN);
        assertEquals(List.of(INVENTORY), mixin.targets);
        assertTrue(mixin.remap);
        MethodInfo handler = mixin.method("ysmCompat$renderInventoryScoped",
                "(Ljava/lang/Runnable;" + PREVIEW_DESCRIPTOR.substring(1));
        assertNotEquals(0, handler.access & Opcodes.ACC_STATIC);
        Hook hook = only(handler.hooks);
        assertEquals("Lorg/spongepowered/asm/mixin/injection/Redirect;", hook.annotation);
        assertEquals(List.of(PREVIEW_SELECTOR), hook.methods);
        assertEquals(1, hook.require);
        assertTrue(hook.remap);
        assertEquals("INVOKE", hook.at);
        assertEquals("L" + RENDER_SYSTEM + ";runAsFancy(Ljava/lang/Runnable;)V", hook.target);
        assertFalse(hook.atRemap);

        MethodInfo vanilla = read(INVENTORY).method("renderEntityInInventory", PREVIEW_DESCRIPTOR);
        assertNotEquals(0, vanilla.access & Opcodes.ACC_STATIC);
        int fancy = only(vanilla.calls(RENDER_SYSTEM, "runAsFancy", "(Ljava/lang/Runnable;)V"));
        assertEquals(Opcodes.INVOKESTATIC, vanilla.steps.get(fancy).opcode);
    }

    @Test
    void handlerOpensForTheEntityAndClosesOnBothNormalAndExceptionalExit() throws IOException {
        MethodInfo handler = read(MIXIN).method("ysmCompat$renderInventoryScoped",
                "(Ljava/lang/Runnable;" + PREVIEW_DESCRIPTOR.substring(1));
        int open = only(handler.calls(SCOPE, "open", "(Ljava/lang/Object;)L" + SCOPE + "$Token;"));
        assertEquals(Step.variable(Opcodes.ALOAD, 7), handler.steps.get(open - 1),
                "The scope must use the original LivingEntity argument");
        int fancy = only(handler.calls(RENDER_SYSTEM, "runAsFancy", "(Ljava/lang/Runnable;)V"));
        assertEquals(Step.variable(Opcodes.ALOAD, 0), handler.steps.get(fancy - 1));
        List<Integer> closes = handler.calls(SCOPE + "$Token", "close", "()V");
        assertEquals(2, closes.size(), "Explicit finally must close on both exits");
        CatchRegion cleanup = only(handler.catches.stream()
                .filter(region -> region.type == null)
                .filter(region -> handler.labels.get(region.start) <= fancy
                        && fancy < handler.labels.get(region.end)).toList());
        int start = handler.labels.get(cleanup.start);
        int end = handler.labels.get(cleanup.end);
        int exceptional = handler.labels.get(cleanup.handler);
        assertTrue(open < start && start <= fancy && fancy < end);
        assertTrue(end <= closes.get(0) && closes.get(0) < exceptional);
        assertTrue(exceptional <= closes.get(1));
        int rethrow = only(handler.indices(step -> step.opcode == Opcodes.ATHROW));
        assertTrue(closes.get(1) < rethrow);
    }

    @Test
    void inventoryHookIsClientOnlyAndItsVanillaSelectorHasAProductionRefmap() throws IOException {
        JsonObject config = json("ysm_epicfight_compat.mixins.json");
        assertEquals(1, count(config, "client", "InventoryRenderScopeMixin"));
        assertEquals(0, count(config, "mixins", "InventoryRenderScopeMixin"));
        JsonObject refmap = json(config.get("refmap").getAsString());
        // This is the public Minecraft 1.20.1 named-to-SRG mapping, not a YSM alias.
        String mapped = "L" + INVENTORY + ";m_280432_" + PREVIEW_DESCRIPTOR;
        assertEquals(mapped, refmap.getAsJsonObject("mappings").getAsJsonObject(MIXIN)
                .get(PREVIEW_SELECTOR).getAsString());
        assertEquals(mapped, refmap.getAsJsonObject("data").getAsJsonObject("searge")
                .getAsJsonObject(MIXIN).get(PREVIEW_SELECTOR).getAsString());
    }

    @Test
    void frameClaimsOnceFromItsEntityAndFirstPersonArgumentsAndStoresTheBit() throws IOException {
        ClassInfo frame = read(FRAME);
        MethodInfo constructor = only(frame.methods.stream()
                .filter(method -> method.name.equals("<init>")).toList());
        int claim = only(constructor.calls(SCOPE, "claim", "(Ljava/lang/Object;Z)Z"));
        assertEquals(Step.variable(Opcodes.ALOAD, 1), constructor.steps.get(claim - 2));
        assertEquals(Step.variable(Opcodes.ILOAD, 2), constructor.steps.get(claim - 1));
        assertEquals(new Step(Opcodes.PUTFIELD, FRAME, "renderingInInventory", "Z", -1),
                constructor.steps.get(claim + 1));
        MethodInfo getter = frame.method("renderingInInventory", "()Z");
        assertEquals(List.of(Step.variable(Opcodes.ALOAD, 0),
                new Step(Opcodes.GETFIELD, FRAME, "renderingInInventory", "Z", -1),
                Step.opcode(Opcodes.IRETURN)), getter.steps);
    }

    @Test
    void meshPassesTheClaimedFrameBitAsTheFinalSampleArgument() throws IOException {
        String descriptor = "(" + ENTITY + "FZLjava/lang/Float;ZL" + ROOT
                + "animation/MovementAnimationType;Z)L" + PROGRAM + "$Frame;";
        MethodInfo draw = only(read(MESH).methods.stream()
                .filter(method -> !method.calls(PROGRAM, "sample", descriptor).isEmpty()).toList());
        assertEquals("draw", draw.name);
        int sample = only(draw.calls(PROGRAM, "sample", descriptor));
        assertEquals(new Step(Opcodes.INVOKEVIRTUAL, FRAME, "renderingInInventory", "()Z", -1),
                draw.steps.get(sample - 1));
    }

    @Test
    void preFramePoseSelectionChecksPendingWithoutConsumingTheScope() throws IOException {
        MethodInfo selection = only(read(ROOT + "render/CombatPlayerRenderer").methods.stream()
                .filter(method -> method.name.equals("configuredFullBodyMovement")).toList());
        int itemSwitch = only(selection.calls(MESH, "itemSwitchOwnsPose", "(" + ENTITY + "Z)Z"));
        assertEquals(new Step(Opcodes.INVOKESTATIC, SCOPE, "pending", "(Ljava/lang/Object;)Z", -1),
                selection.steps.get(itemSwitch - 1));
        assertTrue(selection.calls(SCOPE, "claim", "(Ljava/lang/Object;Z)Z").isEmpty());
    }

    @Test
    void shieldObserverIsAnUncancelledRequiredReturnHookOnTheCommonSide() throws IOException {
        String shieldMixin = ROOT + "mixin/ShieldBlockObserverMixin";
        ClassInfo mixin = read(shieldMixin);
        assertEquals(List.of("net/minecraftforge/common/ForgeHooks"), mixin.targets);
        assertFalse(mixin.remap);
        MethodInfo handler = only(mixin.methods.stream().filter(method -> !method.hooks.isEmpty()).toList());
        Hook hook = only(handler.hooks);
        String descriptor = "(" + ENTITY + "Lnet/minecraft/world/damagesource/DamageSource;F)"
                + "Lnet/minecraftforge/event/entity/living/ShieldBlockEvent;";
        assertEquals("Lorg/spongepowered/asm/mixin/injection/Inject;", hook.annotation);
        assertEquals(List.of("onShieldBlock" + descriptor), hook.methods);
        assertEquals("RETURN", hook.at);
        assertEquals(1, hook.require);
        assertFalse(hook.remap);
        assertFalse(hook.cancellable);
        assertNotEquals(0, read("net/minecraftforge/common/ForgeHooks")
                .method("onShieldBlock", descriptor).access & Opcodes.ACC_STATIC);
        assertTrue(handler.steps.stream().noneMatch(step ->
                step.name.equals("cancel") || step.name.equals("setReturnValue")));
        JsonObject config = json("ysm_epicfight_compat.mixins.json");
        assertEquals(1, count(config, "mixins", "ShieldBlockObserverMixin"));
        assertEquals(0, count(config, "client", "ShieldBlockObserverMixin"));
    }

    private static long count(JsonObject config, String side, String mixin) {
        long matches = 0;
        for (var entry : config.getAsJsonArray(side)) {
            if (mixin.equals(entry.getAsString())) matches++;
        }
        return matches;
    }

    private static JsonObject json(String name) throws IOException {
        try (InputStream stream = resource(name)) {
            return JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8))
                    .getAsJsonObject();
        }
    }

    private static <T> T only(List<T> values) {
        assertEquals(1, values.size(), "Expected exactly one matching contract element");
        return values.get(0);
    }

    private static ClassInfo read(String name) throws IOException {
        ClassInfo info = new ClassInfo();
        try (InputStream stream = resource(name + ".class")) {
            new ClassReader(stream).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                    if (!descriptor.equals("Lorg/spongepowered/asm/mixin/Mixin;")) return null;
                    return new AnnotationVisitor(Opcodes.ASM9) {
                        @Override public void visit(String key, Object value) {
                            if (key.equals("remap")) info.remap = (Boolean) value;
                        }

                        @Override public AnnotationVisitor visitArray(String key) {
                            if (!key.equals("value")) return null;
                            return new AnnotationVisitor(Opcodes.ASM9) {
                                @Override public void visit(String ignored, Object value) {
                                    info.targets.add(((Type) value).getInternalName());
                                }
                            };
                        }
                    };
                }

                @Override
                public MethodVisitor visitMethod(int access, String method, String descriptor,
                                                 String signature, String[] exceptions) {
                    MethodInfo code = new MethodInfo(access, method, descriptor);
                    info.methods.add(code);
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override public AnnotationVisitor visitAnnotation(String annotation, boolean visible) {
                            if (!annotation.endsWith("/Redirect;") && !annotation.endsWith("/Inject;")) return null;
                            Hook hook = new Hook(annotation);
                            code.hooks.add(hook);
                            return hookVisitor(hook);
                        }
                        @Override public void visitMethodInsn(int opcode, String owner, String name,
                                                              String desc, boolean isInterface) {
                            code.steps.add(new Step(opcode, owner, name, desc, -1));
                        }
                        @Override public void visitFieldInsn(int opcode, String owner, String name, String desc) {
                            code.steps.add(new Step(opcode, owner, name, desc, -1));
                        }
                        @Override public void visitVarInsn(int opcode, int variable) {
                            code.steps.add(Step.variable(opcode, variable));
                        }
                        @Override public void visitInsn(int opcode) { code.steps.add(Step.opcode(opcode)); }
                        @Override public void visitJumpInsn(int opcode, Label label) {
                            code.steps.add(Step.opcode(opcode));
                        }
                        @Override public void visitLabel(Label label) { code.labels.put(label, code.steps.size()); }
                        @Override public void visitTryCatchBlock(Label start, Label end, Label handler, String type) {
                            code.catches.add(new CatchRegion(start, end, handler, type));
                        }
                    };
                }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }
        return info;
    }

    private static AnnotationVisitor hookVisitor(Hook hook) {
        return new AnnotationVisitor(Opcodes.ASM9) {
            @Override public void visit(String key, Object value) {
                switch (key) {
                    case "require" -> hook.require = (Integer) value;
                    case "remap" -> hook.remap = (Boolean) value;
                    case "cancellable" -> hook.cancellable = (Boolean) value;
                    default -> { }
                }
            }
            @Override public AnnotationVisitor visitAnnotation(String key, String descriptor) {
                return key.equals("at") ? atVisitor(hook) : null;
            }
            @Override public AnnotationVisitor visitArray(String key) {
                return new AnnotationVisitor(Opcodes.ASM9) {
                    @Override public void visit(String ignored, Object value) {
                        if (key.equals("method")) hook.methods.add((String) value);
                    }
                    @Override public AnnotationVisitor visitAnnotation(String ignored, String descriptor) {
                        return key.equals("at") ? atVisitor(hook) : null;
                    }
                };
            }
        };
    }

    private static AnnotationVisitor atVisitor(Hook hook) {
        return new AnnotationVisitor(Opcodes.ASM9) {
            @Override public void visit(String key, Object value) {
                switch (key) {
                    case "value" -> hook.at = (String) value;
                    case "target" -> hook.target = (String) value;
                    case "remap" -> hook.atRemap = (Boolean) value;
                    default -> { }
                }
            }
        };
    }

    private static InputStream resource(String name) {
        InputStream stream = InventoryRenderContractTest.class.getClassLoader().getResourceAsStream(name);
        assertNotNull(stream, "Missing classpath resource " + name);
        return stream;
    }

    private static final class ClassInfo {
        final List<String> targets = new ArrayList<>();
        final List<MethodInfo> methods = new ArrayList<>();
        boolean remap = true;

        MethodInfo method(String name, String descriptor) {
            return only(methods.stream().filter(method ->
                    method.name.equals(name) && method.descriptor.equals(descriptor)).toList());
        }
    }

    private static final class MethodInfo {
        final int access;
        final String name;
        final String descriptor;
        final List<Step> steps = new ArrayList<>();
        final List<Hook> hooks = new ArrayList<>();
        final Map<Label, Integer> labels = new IdentityHashMap<>();
        final List<CatchRegion> catches = new ArrayList<>();

        MethodInfo(int access, String name, String descriptor) {
            this.access = access;
            this.name = name;
            this.descriptor = descriptor;
        }

        List<Integer> calls(String owner, String name, String descriptor) {
            return indices(step -> step.opcode >= Opcodes.INVOKEVIRTUAL
                    && step.opcode <= Opcodes.INVOKEINTERFACE && step.owner.equals(owner)
                    && step.name.equals(name) && step.descriptor.equals(descriptor));
        }

        List<Integer> indices(Predicate<Step> predicate) {
            return IntStream.range(0, steps.size()).filter(index -> predicate.test(steps.get(index)))
                    .boxed().toList();
        }
    }

    private record Step(int opcode, String owner, String name, String descriptor, int variable) {
        static Step opcode(int opcode) { return variable(opcode, -1); }
        static Step variable(int opcode, int variable) { return new Step(opcode, "", "", "", variable); }
    }

    private record CatchRegion(Label start, Label end, Label handler, String type) { }

    private static final class Hook {
        final String annotation;
        final List<String> methods = new ArrayList<>();
        String at = "";
        String target = "";
        int require;
        boolean remap = true;
        boolean atRemap = true;
        boolean cancellable;

        Hook(String annotation) { this.annotation = annotation; }
    }
}
