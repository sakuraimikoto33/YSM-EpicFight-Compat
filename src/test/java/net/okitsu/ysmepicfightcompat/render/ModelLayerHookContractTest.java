package net.okitsu.ysmepicfightcompat.render;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Checks the narrow draw-order seams against the installed Epic Fight dependency. */
class ModelLayerHookContractTest {
    private static final String RENDERER =
            "yesman/epicfight/client/renderer/patched/entity/PatchedLivingEntityRenderer";
    private static final String MESH = "yesman/epicfight/api/client/model/SkinnedMesh";
    private static final String MIXIN =
            "net/okitsu/ysmepicfightcompat/mixin/PatchedLivingEntityRendererMixin";
    private static final String RENDER =
            "render(Lnet/minecraft/world/entity/LivingEntity;" +
                    "Lyesman/epicfight/world/capabilities/entitypatch/LivingEntityPatch;" +
                    "Lnet/minecraft/client/renderer/entity/LivingEntityRenderer;" +
                    "Lnet/minecraft/client/renderer/MultiBufferSource;" +
                    "Lcom/mojang/blaze3d/vertex/PoseStack;IF)V";

    @Test
    void redirectsExactlyOneBodyDrawAndOneFinalLayerDispatch() throws IOException {
        List<Hook> hooks = hooks();
        assertEquals(2, hooks.size());
        assertEquals(Set.of("ysmCompat$drawBodyWithModelLayerOrder", "ysmCompat$renderRemainingLayers"),
                hooks.stream().map(hook -> hook.handler).collect(java.util.stream.Collectors.toSet()));
        for (Hook hook : hooks) {
            assertEquals(List.of(RENDER), hook.methods);
            assertEquals("INVOKE", hook.at);
            assertFalse(hook.remap);
            assertFalse(hook.targetRemap);
            assertEquals(1, hook.require);
            assertEquals(1, hook.expect);
            int[] matchingMethods = {0};
            int[] matchingInvocations = {0};
            read(RENDERER, new ClassVisitor(Opcodes.ASM9) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                 String signature, String[] exceptions) {
                    if (!(name + descriptor).equals(RENDER)) {
                        return null;
                    }
                    matchingMethods[0]++;
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override
                        public void visitMethodInsn(int opcode, String owner, String method,
                                                    String desc, boolean isInterface) {
                            if (hook.target.equals("L" + owner + ";" + method + desc)) {
                                assertEquals(Opcodes.INVOKEVIRTUAL, opcode);
                                matchingInvocations[0]++;
                                assertHandlerArguments(hook, owner, desc);
                            }
                        }
                    };
                }
            });
            assertEquals(1, matchingMethods[0]);
            assertEquals(1, matchingInvocations[0], hook.target);
        }
    }

    @Test
    void decorationDrawsStayOutsideTheRedirectedMethod() throws IOException {
        Hook body = hooks().stream().filter(hook -> hook.target.startsWith("L" + MESH + ";draw("))
                .findFirst().orElseThrow();
        int[] decorationDraws = {0};
        read(RENDERER, new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                if (!name.startsWith("lambda$render$")) {
                    return null;
                }
                assertFalse(body.methods.contains(name + descriptor));
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String method,
                                                String desc, boolean isInterface) {
                        if (body.target.equals("L" + owner + ";" + method + desc)) {
                            decorationDraws[0]++;
                        }
                    }
                };
            }
        });
        assertEquals(1, decorationDraws[0]);
    }

    @Test
    void shadowLayerMethodHasTheExactErasedDescriptorAndVisibility() throws IOException {
        Map<String, Integer> methods = new HashMap<>();
        read(RENDERER, new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                methods.put(name + descriptor, access);
                return null;
            }
        });
        int[] shadows = {0};
        read(MIXIN, new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public AnnotationVisitor visitAnnotation(String annotation, boolean visible) {
                        if (annotation.equals("Lorg/spongepowered/asm/mixin/Shadow;")) {
                            shadows[0]++;
                            assertEquals("renderLayer", name);
                            Integer targetAccess = methods.get(name + descriptor);
                            assertNotNull(targetAccess, "The shadow must exist in Epic Fight");
                            assertNotEquals(0, targetAccess & Opcodes.ACC_PROTECTED);
                            assertEquals(0, targetAccess & Opcodes.ACC_STATIC);
                            assertNotEquals(0, access & Opcodes.ACC_ABSTRACT);
                        }
                        return null;
                    }
                };
            }
        });
        assertEquals(1, shadows[0]);
    }

    @Test
    void outlineFlushAccessorTargetsOnlyTheSuppliedNormalDelegate() throws IOException {
        String descriptor = "Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;";
        int[] fields = {0};
        read("net/minecraft/client/renderer/OutlineBufferSource", new ClassVisitor(Opcodes.ASM9) {
            @Override
            public org.objectweb.asm.FieldVisitor visitField(int access, String name,
                    String desc, String signature, Object value) {
                if (name.equals("bufferSource")) {
                    fields[0]++;
                    assertEquals(descriptor, desc);
                    assertEquals(0, access & Opcodes.ACC_STATIC);
                }
                return null;
            }
        });
        int[] accessors = {0};
        read("net/okitsu/ysmepicfightcompat/mixin/OutlineBufferSourceAccessor",
                new ClassVisitor(Opcodes.ASM9) {
                    @Override
                    public MethodVisitor visitMethod(int access, String name, String desc,
                                                     String signature, String[] exceptions) {
                        assertEquals("ysmCompat$bufferSource", name);
                        assertEquals("()" + descriptor, desc);
                        return new MethodVisitor(Opcodes.ASM9) {
                            @Override
                            public AnnotationVisitor visitAnnotation(String annotation, boolean visible) {
                                if (!annotation.equals("Lorg/spongepowered/asm/mixin/gen/Accessor;")) {
                                    return null;
                                }
                                accessors[0]++;
                                return new AnnotationVisitor(Opcodes.ASM9) {
                                    @Override
                                    public void visit(String key, Object value) {
                                        if (key.equals("value")) {
                                            assertEquals("bufferSource", value);
                                        }
                                        assertNotEquals("remap", key);
                                    }
                                };
                            }
                        };
                    }
                });
        assertEquals(1, fields[0]);
        assertEquals(1, accessors[0]);
    }

    private static void assertHandlerArguments(Hook hook, String owner, String invocationDescriptor) {
        List<Type> expected = new ArrayList<>();
        expected.add(Type.getObjectType(owner));
        expected.addAll(Arrays.asList(Type.getArgumentTypes(invocationDescriptor)));
        if (hook.handler.equals("ysmCompat$drawBodyWithModelLayerOrder")) {
            expected.addAll(Arrays.asList(Type.getArgumentTypes(RENDER.substring("render".length()))));
        }
        assertEquals(expected, Arrays.asList(Type.getArgumentTypes(hook.descriptor)));
        assertEquals(Type.VOID_TYPE, Type.getReturnType(hook.descriptor));
    }

    private static List<Hook> hooks() throws IOException {
        List<Hook> result = new ArrayList<>();
        read(MIXIN, new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                if (!name.equals("ysmCompat$drawBodyWithModelLayerOrder")
                        && !name.equals("ysmCompat$renderRemainingLayers")) {
                    return null;
                }
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public AnnotationVisitor visitAnnotation(String annotation, boolean visible) {
                        if (!annotation.equals("Lorg/spongepowered/asm/mixin/injection/Redirect;")) {
                            return null;
                        }
                        Hook hook = new Hook(name, descriptor);
                        result.add(hook);
                        return new AnnotationVisitor(Opcodes.ASM9) {
                            @Override
                            public void visit(String key, Object value) {
                                switch (key) {
                                    case "require" -> hook.require = (Integer) value;
                                    case "expect" -> hook.expect = (Integer) value;
                                    case "remap" -> hook.remap = (Boolean) value;
                                    default -> { }
                                }
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
                                };
                            }

                            @Override
                            public AnnotationVisitor visitAnnotation(String key, String desc) {
                                return new AnnotationVisitor(Opcodes.ASM9) {
                                    @Override
                                    public void visit(String attribute, Object value) {
                                        switch (attribute) {
                                            case "value" -> hook.at = (String) value;
                                            case "target" -> hook.target = (String) value;
                                            case "remap" -> hook.targetRemap = (Boolean) value;
                                            default -> { }
                                        }
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

    private static void read(String type, ClassVisitor visitor) throws IOException {
        try (InputStream stream = ModelLayerHookContractTest.class.getClassLoader()
                .getResourceAsStream(type + ".class")) {
            assertNotNull(stream, type);
            new ClassReader(stream).accept(visitor, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }
    }

    private static final class Hook {
        private final String handler;
        private final String descriptor;
        private final List<String> methods = new ArrayList<>();
        private String at;
        private String target;
        private boolean remap = true;
        private boolean targetRemap = true;
        private int require;
        private int expect;

        private Hook(String handler, String descriptor) {
            this.handler = handler;
            this.descriptor = descriptor;
        }
    }
}
