package net.okitsu.ysmepicfightcompat.render;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Checks actual dependency bytecode without bootstrapping Minecraft or Mixin. */
class AttachmentRenderHookContractTest {
    private static final String ARMATURE = "yesman/epicfight/api/model/Armature";
    private static final String RENDERER =
            "yesman/epicfight/client/renderer/patched/entity/PatchedLivingEntityRenderer";
    private static final String LAYER = "yesman/epicfight/client/renderer/patched/layer/PatchedLayer";
    private static final String MATRIX = "Lyesman/epicfight/api/utils/math/OpenMatrix4f;";
    private static final String POSE = "Lyesman/epicfight/api/animation/Pose;";
    private static final String JOINT = "Lyesman/epicfight/api/animation/Joint;";
    private static final String MIXINS = "net/okitsu/ysmepicfightcompat/mixin/";

    @Test
    void wrapsBothVanillaAndCustomPatchedLayerCallsInTheActualDependency() throws IOException {
        Hook redirect = hooks(MIXINS + "PatchedLivingEntityRendererMixin").stream()
                .filter(hook -> hook.annotation.endsWith("/Redirect;"))
                .filter(hook -> hook.target.startsWith("L" + LAYER + ";renderLayer("))
                .findFirst().orElseThrow();
        assertEquals(1, redirect.methods.size());
        assertEquals(2, redirect.require);
        assertFalse(redirect.remap);
        assertEquals("INVOKE", redirect.at);
        String selector = redirect.methods.get(0);
        int[] matchingMethod = {0};
        int[] matchingInvocations = {0};
        read(RENDERER, new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                if (!(name + descriptor).equals(selector)) {
                    return null;
                }
                matchingMethod[0]++;
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String method,
                                                String desc, boolean isInterface) {
                        if (redirect.target.equals("L" + owner + ";" + method + desc)) {
                            assertEquals(Opcodes.INVOKEVIRTUAL, opcode);
                            matchingInvocations[0]++;
                        }
                    }
                };
            }
        });
        assertEquals(1, matchingMethod[0], "The exact renderer descriptor must exist");
        assertEquals(2, matchingInvocations[0], "Cover ordinary and custom layer dispatch");
    }

    @Test
    void everyArmatureHookMatchesTheDependencyAndOnlySetPoseIsCanceledBeforeExecution()
            throws IOException {
        Set<String> available = new HashSet<>();
        read(ARMATURE, new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                available.add(name + descriptor);
                return null;
            }
        });
        List<Hook> hooks = hooks(MIXINS + "AttachmentArmatureMixin");
        Set<String> expected = Set.of("setPose(" + POSE + ")V",
                "getPoseMatrices()[" + MATRIX,
                "getPoseAsTransformMatrix(" + POSE + "Z)[" + MATRIX,
                "getBoundTransformFor(" + POSE + JOINT + ")" + MATRIX,
                "getBindedTransformFor(" + POSE + JOINT + ")" + MATRIX);
        Set<String> hooked = new HashSet<>();
        for (Hook hook : hooks) {
            assertTrue(hook.annotation.endsWith("/Inject;"));
            assertFalse(hook.remap);
            assertTrue(hook.cancellable);
            for (String method : hook.methods) {
                assertTrue(available.contains(method), "Missing Epic Fight method: " + method);
                assertEquals(method.startsWith("setPose(") ? "HEAD" : "RETURN", hook.at);
                assertTrue(hooked.add(method), "Duplicate hook: " + method);
            }
        }
        assertEquals(expected, hooked);
    }

    @Test
    void armatureHooksAreClientOnly() throws IOException {
        try (InputStream stream = resource("ysm_epicfight_compat.mixins.json")) {
            var config = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            Set<String> client = new HashSet<>();
            config.getAsJsonArray("client").forEach(entry -> client.add(entry.getAsString()));
            assertTrue(client.contains("AttachmentArmatureMixin"));
            config.getAsJsonArray("mixins").forEach(entry ->
                    assertNotEquals("AttachmentArmatureMixin", entry.getAsString()));
        }
    }

    private static List<Hook> hooks(String type) throws IOException {
        List<Hook> result = new ArrayList<>();
        read(type, new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public AnnotationVisitor visitAnnotation(String annotation, boolean visible) {
                        if (!annotation.endsWith("/Inject;") && !annotation.endsWith("/Redirect;")) {
                            return null;
                        }
                        Hook hook = new Hook(annotation);
                        result.add(hook);
                        return new AnnotationVisitor(Opcodes.ASM9) {
                            @Override
                            public void visit(String key, Object value) {
                                switch (key) {
                                    case "require" -> hook.require = (Integer) value;
                                    case "remap" -> hook.remap = (Boolean) value;
                                    case "cancellable" -> hook.cancellable = (Boolean) value;
                                    default -> { }
                                }
                            }

                            @Override
                            public AnnotationVisitor visitAnnotation(String key, String desc) {
                                return key.equals("at") ? at(hook) : null;
                            }

                            @Override
                            public AnnotationVisitor visitArray(String key) {
                                return new AnnotationVisitor(Opcodes.ASM9) {
                                    @Override
                                    public void visit(String ignored, Object value) {
                                        if (key.equals("method")) {
                                            hook.methods.add((String) value);
                                        }
                                    }

                                    @Override
                                    public AnnotationVisitor visitAnnotation(String ignored, String desc) {
                                        return key.equals("at") ? at(hook) : null;
                                    }
                                };
                            }
                        };
                    }
                };
            }
        });
        return result;
    }

    private static AnnotationVisitor at(Hook hook) {
        return new AnnotationVisitor(Opcodes.ASM9) {
            @Override
            public void visit(String key, Object value) {
                if (key.equals("value")) {
                    hook.at = (String) value;
                } else if (key.equals("target")) {
                    hook.target = (String) value;
                }
            }
        };
    }

    private static void read(String type, ClassVisitor visitor) throws IOException {
        try (InputStream stream = resource(type + ".class")) {
            new ClassReader(stream).accept(visitor, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }
    }

    private static InputStream resource(String name) {
        InputStream stream = AttachmentRenderHookContractTest.class.getClassLoader().getResourceAsStream(name);
        assertNotNull(stream, "Missing test classpath resource " + name);
        return stream;
    }

    private static final class Hook {
        final String annotation;
        final List<String> methods = new ArrayList<>();
        String target = "";
        String at = "";
        int require;
        boolean remap = true;
        boolean cancellable;

        Hook(String annotation) {
            this.annotation = annotation;
        }
    }
}
