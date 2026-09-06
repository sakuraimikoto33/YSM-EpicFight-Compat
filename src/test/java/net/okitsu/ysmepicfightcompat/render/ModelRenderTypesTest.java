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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Verifies the copy contract without initializing Minecraft's GL-backed render types. */
class ModelRenderTypesTest {
    private static final String HELPER =
            "net/okitsu/ysmepicfightcompat/render/ModelRenderTypes";
    private static final String MIXINS = "net/okitsu/ysmepicfightcompat/mixin/";
    private static final String STATE_ACCESSOR = MIXINS + "CompositeRenderStateAccessor$State";

    @Test
    void memoizationIsBoundedAndUsesLeastRecentlyUsedEviction() {
        ModelRenderTypes.Cache<String, Object> cache = new ModelRenderTypes.Cache<>(2);
        AtomicInteger creations = new AtomicInteger();
        java.util.function.Function<String, Object> create = key -> {
            creations.incrementAndGet();
            return new Object();
        };
        Object first = cache.get("first", create);
        Object second = cache.get("second", create);
        assertSame(first, cache.get("first", create));
        cache.get("third", create);
        assertEquals(2, cache.size());
        assertSame(first, cache.get("first", create));
        assertNotSame(second, cache.get("second", create));
        assertEquals(4, creations.get());
        assertEquals(2, cache.size());
        cache.clear();
        assertEquals(0, cache.size());
    }

    @Test
    void rejectedEntriesDoNotEvictValidMemoizedTypes() {
        ModelRenderTypes.Cache<String, Object> cache = new ModelRenderTypes.Cache<>(1);
        Object original = cache.get("first", key -> new Object());
        assertThrows(NullPointerException.class, () -> cache.get("bad", key -> null));
        assertSame(original, cache.get("first", key -> fail("Must stay cached")));
        assertThrows(IllegalArgumentException.class, () -> new ModelRenderTypes.Cache<>(0));
    }

    @Test
    void stateCopyReadsEveryNonCullShardAndForcesOnlyCull() throws IOException {
        Set<String> reads = new HashSet<>();
        List<String> writes = new ArrayList<>();
        List<String> constants = new ArrayList<>();
        read(HELPER, new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                if (!name.equals("copyWithCull")) {
                    return null;
                }
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String method,
                                                String desc, boolean isInterface) {
                        if (owner.equals(STATE_ACCESSOR)) {
                            assertTrue(reads.add(method), "Each shard is copied once");
                        }
                        if (method.startsWith("set")) {
                            writes.add(method);
                        }
                        if (method.equals("createCompositeState")) {
                            assertEquals("(Z)Lnet/minecraft/client/renderer/RenderType$CompositeState;",
                                    desc, "Retain the source's affects-outline flag");
                        }
                    }

                    @Override
                    public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
                        if (opcode == Opcodes.GETSTATIC) {
                            constants.add(name);
                        }
                    }
                };
            }
        });
        assertEquals(Set.of("ysmCompat$texture", "ysmCompat$shader", "ysmCompat$transparency",
                "ysmCompat$depthTest", "ysmCompat$lightmap", "ysmCompat$overlay",
                "ysmCompat$layering", "ysmCompat$output", "ysmCompat$texturing",
                "ysmCompat$writeMask", "ysmCompat$line", "ysmCompat$colorLogic"), reads);
        assertEquals(List.of("setTextureState", "setShaderState", "setTransparencyState",
                "setDepthTestState", "setCullState", "setLightmapState", "setOverlayState",
                "setLayeringState", "setOutputState", "setTexturingState", "setWriteMaskState",
                "setLineState", "setColorLogicState"), writes);
        assertEquals(List.of("CULL"), constants, "Do not substitute alpha, shader, depth or blend states");
    }

    @Test
    void accessorsMatchTheActualVanillaFieldsWithoutAnAccessTransformer() throws IOException {
        assertAccessors(MIXINS + "CompositeRenderTypeAccessor",
                "net/minecraft/client/renderer/RenderType$CompositeRenderType", 1);
        assertAccessors(MIXINS + "RenderTypeSortingAccessor",
                "net/minecraft/client/renderer/RenderType", 1);
        assertAccessors(STATE_ACCESSOR,
                "net/minecraft/client/renderer/RenderType$CompositeState", 13);
    }

    private static void assertAccessors(String accessor, String target, int count) throws IOException {
        Map<String, String> fields = new HashMap<>();
        read(target, new ClassVisitor(Opcodes.ASM9) {
            @Override
            public org.objectweb.asm.FieldVisitor visitField(int access, String name,
                    String descriptor, String signature, Object value) {
                fields.put(name, descriptor);
                return null;
            }
        });
        Set<String> found = new HashSet<>();
        read(accessor, new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public AnnotationVisitor visitAnnotation(String annotation, boolean visible) {
                        if (!annotation.equals("Lorg/spongepowered/asm/mixin/gen/Accessor;")) {
                            return null;
                        }
                        return new AnnotationVisitor(Opcodes.ASM9) {
                            @Override
                            public void visit(String key, Object value) {
                                assertNotEquals("remap", key, "Vanilla accessors must be remapped");
                                if (key.equals("value")) {
                                    String field = (String) value;
                                    assertTrue(found.add(field));
                                    assertEquals(fields.get(field), Type.getReturnType(descriptor).getDescriptor(),
                                            "Accessor field: " + target + "." + field);
                                }
                            }
                        };
                    }
                };
            }
        });
        assertEquals(count, found.size());
    }

    private static void read(String type, ClassVisitor visitor) throws IOException {
        try (InputStream stream = ModelRenderTypesTest.class.getClassLoader()
                .getResourceAsStream(type + ".class")) {
            assertNotNull(stream, type);
            new ClassReader(stream).accept(visitor, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }
    }
}
