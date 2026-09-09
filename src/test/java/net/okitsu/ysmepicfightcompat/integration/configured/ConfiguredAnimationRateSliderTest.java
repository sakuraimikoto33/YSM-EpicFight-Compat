package net.okitsu.ysmepicfightcompat.integration.configured;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.google.gson.JsonParser;
import com.mrcrayfish.configured.impl.forge.ForgeValue;
import net.minecraftforge.common.ForgeConfigSpec;
import net.okitsu.ysmepicfightcompat.config.ClientPreferences;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Policy and real-bytecode contracts; does not instantiate a Minecraft screen or claim visual QA. */
class ConfiguredAnimationRateSliderTest {
    private static final List<String> RATE_PATH = List.of("client", "animationEvaluationRateLimitHz");
    private static final String ROOT = "net/okitsu/ysmepicfightcompat/";
    private static final String SLIDER = ROOT + "integration/configured/ConfiguredAnimationRateSlider";
    private static final String MIXIN = ROOT + "mixin/ConfiguredAnimationRateSliderMixin";
    private static final String CONFIGURED = "com/mrcrayfish/configured/";

    @Test
    void everyLegalRateHasOneSliderStepAndRoundTripsExactly() {
        assertEquals(1.0, ConfiguredAnimationRateSlider.sliderPosition(0));
        assertEquals(0, ConfiguredAnimationRateSlider.rateAtPosition(1));
        assertEquals(30, ConfiguredAnimationRateSlider.rateAtPosition(0));
        int lastStep = 240 - 30 + 1;
        double previous = -1;
        for (int rate = 30; rate <= 240; rate++) {
            double position = ConfiguredAnimationRateSlider.sliderPosition(rate);
            assertEquals((rate - 30.0) / lastStep, position, 1.0E-12);
            assertTrue(position > previous);
            assertEquals(rate, ConfiguredAnimationRateSlider.rateAtPosition(position));
            previous = position;
        }
        assertEquals(210.0 / lastStep, previous, 1.0E-12);
        assertTrue(previous < ConfiguredAnimationRateSlider.sliderPosition(0));
    }

    @Test
    void sliderCoordinatesAreBoundedAndNeverProduceTheUnsupportedGap() {
        assertEquals(0, ConfiguredAnimationRateSlider.rateAtPosition(Double.NaN));
        assertEquals(30, ConfiguredAnimationRateSlider.rateAtPosition(Double.NEGATIVE_INFINITY));
        assertEquals(0, ConfiguredAnimationRateSlider.rateAtPosition(Double.POSITIVE_INFINITY));
        assertEquals(30, ConfiguredAnimationRateSlider.rateAtPosition(-0.5));
        assertEquals(0, ConfiguredAnimationRateSlider.rateAtPosition(1.5));
        assertEquals(1.0, ConfiguredAnimationRateSlider.sliderPosition(-1));
        assertEquals(ConfiguredAnimationRateSlider.sliderPosition(240),
                ConfiguredAnimationRateSlider.sliderPosition(1000));
        for (int index = 0; index <= 10000; index++) {
            int rate = ConfiguredAnimationRateSlider.rateAtPosition(index / 10000.0);
            assertTrue(rate == 0 || rate >= 30 && rate <= 240, "Unsupported slider rate: " + rate);
        }
    }

    @Test
    void arrowKeysMoveOneLegalSettingAndClampAtBothEnds() {
        assertEquals(240, ConfiguredAnimationRateSlider.adjacentRate(0, -1));
        assertEquals(0, ConfiguredAnimationRateSlider.adjacentRate(0, 1));
        assertEquals(30, ConfiguredAnimationRateSlider.adjacentRate(30, -1));
        assertEquals(31, ConfiguredAnimationRateSlider.adjacentRate(30, 1));
        assertEquals(239, ConfiguredAnimationRateSlider.adjacentRate(240, -1));
        assertEquals(0, ConfiguredAnimationRateSlider.adjacentRate(240, 1));
        assertEquals(60, ConfiguredAnimationRateSlider.adjacentRate(60, 0));
        assertEquals(61, ConfiguredAnimationRateSlider.adjacentRate(60, Integer.MAX_VALUE));
    }

    @Test
    void onlyTheExactClientForgeValueIsEligibleNotAnotherModsSameNamedSetting() {
        try (RateFixture fixture = new RateFixture()) {
            assertTrue(ConfiguredAnimationRateSlider.isRateValue(fixture.holder));
            assertFalse(ConfiguredAnimationRateSlider.isRateValue(foreignValue(false)));
            assertFalse(ConfiguredAnimationRateSlider.isRateValue(ClientPreferences.ANIMATION_EVALUATION_RATE_LIMIT_HZ));
            assertFalse(ConfiguredAnimationRateSlider.isRateValue(null));
            assertFalse(ConfiguredAnimationRateSlider.isRateValue(new Object()));
            assertEquals(null, ConfiguredAnimationRateSlider.createEntry(new Object(), new Object(), () -> { }));
        }
    }

    @Test
    void changingTheSliderStagesTheExistingHolderWithoutSavingTheForgeConfig() {
        try (RateFixture fixture = new RateFixture()) {
            AtomicInteger changes = new AtomicInteger();
            ConfiguredAnimationRateSlider.applySliderValue(fixture.holder,
                    ConfiguredAnimationRateSlider.sliderPosition(120), changes::incrementAndGet);
            assertEquals(120, fixture.holder.get().intValue());
            assertEquals(1, changes.get());
            assertTrue(fixture.holder.isChanged());
            assertEquals(60, fixture.data.<Integer>get(RATE_PATH).intValue());
            assertEquals(60, ClientPreferences.animationEvaluationRateLimitHz());
            ConfiguredAnimationRateSlider.applySliderValue(fixture.holder,
                    ConfiguredAnimationRateSlider.sliderPosition(120), changes::incrementAndGet);
            assertEquals(1, changes.get(), "Unchanged values must not fire another save-button update");
            ConfiguredAnimationRateSlider.applySliderValue(fixture.holder, 1, changes::incrementAndGet);
            assertEquals(0, fixture.holder.get().intValue());
            assertEquals(2, changes.get());
            fixture.holder.restore();
            assertEquals(60, fixture.holder.get().intValue());
            assertTrue(fixture.holder.isDefault());
            assertFalse(fixture.holder.isChanged());
        }
    }

    @Test
    void holderValidationIsRespectedBeforeNotifyingTheScreen() {
        ForgeValue<Integer> holder = foreignValue(true);
        AtomicInteger changes = new AtomicInteger();
        ConfiguredAnimationRateSlider.applySliderValue(holder, 1, changes::incrementAndGet);
        assertEquals(60, holder.get().intValue());
        assertEquals(0, changes.get());
    }

    @Test
    void optionalMixinMatchesConfiguredsActualFactoryWithoutHardOptionalHandlerTypes() throws IOException {
        Shape shape = read(MIXIN);
        assertTrue(shape.annotations.containsKey("Lorg/spongepowered/asm/mixin/Pseudo;"));
        Map<String, Object> mixin = shape.annotations.get("Lorg/spongepowered/asm/mixin/Mixin;");
        assertEquals(List.of("com.mrcrayfish.configured.client.screen.ConfigScreen"), mixin.get("targets"));
        assertEquals(false, mixin.get("remap"));
        Method handler = shape.methods.stream().filter(method -> method.hasCall(SLIDER, "createEntry"))
                .findFirst().orElseThrow();
        assertFalse(handler.descriptor.contains(CONFIGURED));
        assertEquals("(Ljava/lang/Object;Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable;)V",
                handler.descriptor);
        Map<String, Object> inject = handler.annotations.get("Lorg/spongepowered/asm/mixin/injection/Inject;");
        assertEquals(List.of("createItemFromEntry"), inject.get("method"));
        assertEquals(true, inject.get("cancellable"));
        assertEquals(false, inject.get("remap"));
        assertEquals(0, inject.get("require"));
        assertEquals("HEAD", ((Map<?, ?>) ((List<?>) inject.get("at")).get(0)).get("value"));
        assertTrue(handler.handles.stream().anyMatch(handle -> handle.getOwner().equals(MIXIN)
                && handle.getName().equals("updateButtons") && handle.getDesc().equals("()V")));
        Method dependencyFactory = read(CONFIGURED + "client/screen/ConfigScreen").method("createItemFromEntry");
        assertEquals("(L" + CONFIGURED + "api/IConfigEntry;)L" + CONFIGURED + "client/screen/ListMenuScreen$Item;",
                dependencyFactory.descriptor);
        assertTrue((dependencyFactory.access & Opcodes.ACC_PRIVATE) != 0);
        try (InputStream input = getClass().getResourceAsStream("/ysm_epicfight_compat.mixins.json")) {
            assertNotNull(input);
            var client = JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8))
                    .getAsJsonObject().getAsJsonArray("client");
            assertTrue(client.asList().stream().anyMatch(value ->
                    value.getAsString().equals("ConfiguredAnimationRateSliderMixin")));
        }
    }

    @Test
    void widgetInitializationAndDisabledDragAndKeyboardGuardsPrecedeChanges() throws IOException {
        Shape widget = read(SLIDER + "$RateSlider");
        assertEquals("net/minecraft/client/gui/components/AbstractSliderButton", widget.parent);
        Method constructor = widget.method("<init>");
        int message = constructor.call(SLIDER + "$RateSlider", "updateMessage");
        assertTrue(constructor.field(Opcodes.PUTFIELD, "holder") < message);
        assertTrue(constructor.field(Opcodes.PUTFIELD, "changed") < message);
        Method apply = widget.method("applyValue");
        int commit = apply.call(SLIDER, "applySliderValue");
        assertTrue(apply.field(Opcodes.GETFIELD, "active") < commit);
        assertTrue(apply.field(Opcodes.GETFIELD, "visible") < commit);
        assertTrue(apply.hasCall(SLIDER + "$RateSlider", "refreshFromHolder"));
        Method key = widget.method("keyPressed");
        int adjacent = key.call(SLIDER, "adjacentRate");
        assertTrue(key.field(Opcodes.GETFIELD, "active") < adjacent);
        assertTrue(key.field(Opcodes.GETFIELD, "visible") < adjacent);
        assertTrue(key.hasCall(SLIDER + "$RateSlider", "applyValue"));
    }

    @Test
    void resetAndValueChangesRetainConfiguredsOriginalHolderAndSaveFlow() throws IOException {
        Shape row = read(SLIDER + "$RateItem");
        assertEquals(CONFIGURED + "client/screen/ConfigScreen$ConfigItem", row.parent);
        Method reset = row.method("onResetValue");
        assertTrue(reset.hasCall(SLIDER + "$RateSlider", "refreshFromHolder"));
        assertFalse(reset.hasCall(CONFIGURED + "api/IConfigValue", "set"));
        Method apply = read(SLIDER).method("applySliderValue");
        assertTrue(apply.call(CONFIGURED + "api/IConfigValue", "isValid")
                < apply.call(CONFIGURED + "api/IConfigValue", "set"));
        assertTrue(apply.call(CONFIGURED + "api/IConfigValue", "set")
                < apply.call("java/lang/Runnable", "run"));
        assertFalse(apply.hasCall("net/minecraftforge/common/ForgeConfigSpec$ConfigValue", "set"));
    }

    private static ForgeValue<Integer> foreignValue(boolean onlySixty) {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
        builder.push("client");
        ForgeConfigSpec.ConfigValue<Integer> value = builder.define("animationEvaluationRateLimitHz", 60,
                candidate -> candidate instanceof Integer rate && (!onlySixty || rate == 60));
        builder.pop();
        ForgeConfigSpec spec = builder.build();
        spec.setConfig(CommentedConfig.inMemory());
        return new ForgeValue<>(value, spec.getRaw(RATE_PATH));
    }

    private static final class RateFixture implements AutoCloseable {
        private final CommentedConfig data = CommentedConfig.inMemory();
        private final ForgeValue<Integer> holder;

        private RateFixture() {
            ClientPreferences.CLIENT_SPEC.setConfig(data);
            holder = new ForgeValue<>(ClientPreferences.ANIMATION_EVALUATION_RATE_LIMIT_HZ,
                    ClientPreferences.CLIENT_SPEC.getRaw(RATE_PATH));
        }

        @Override public void close() { ClientPreferences.CLIENT_SPEC.setConfig(null); }
    }

    private static Shape read(String owner) throws IOException {
        Shape shape = new Shape();
        try (InputStream input = ConfiguredAnimationRateSliderTest.class.getClassLoader()
                .getResourceAsStream(owner + ".class")) {
            assertNotNull(input, owner);
            new ClassReader(input).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override public void visit(int version, int access, String name, String signature,
                                            String parent, String[] interfaces) { shape.parent = parent; }
                @Override public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                    return annotation(shape.annotations.computeIfAbsent(descriptor, ignored -> new LinkedHashMap<>()));
                }
                @Override public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                           String signature, String[] exceptions) {
                    Method method = new Method(name, descriptor, access);
                    shape.methods.add(method);
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                            return annotation(method.annotations.computeIfAbsent(descriptor, ignored -> new LinkedHashMap<>()));
                        }
                        @Override public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
                            method.steps.add(new Step(opcode, owner, name));
                        }
                        @Override public void visitMethodInsn(int opcode, String owner, String name,
                                                              String descriptor, boolean isInterface) {
                            method.steps.add(new Step(opcode, owner, name));
                        }
                        @Override public void visitInvokeDynamicInsn(String name, String descriptor,
                                                                     Handle bootstrap, Object... arguments) {
                            for (Object argument : arguments) if (argument instanceof Handle handle) method.handles.add(handle);
                        }
                    };
                }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }
        return shape;
    }

    private static AnnotationVisitor annotation(Map<String, Object> values) {
        return new AnnotationVisitor(Opcodes.ASM9) {
            @Override public void visit(String name, Object value) { values.put(name, value); }
            @Override public AnnotationVisitor visitArray(String name) {
                List<Object> items = new ArrayList<>();
                values.put(name, items);
                return new AnnotationVisitor(Opcodes.ASM9) {
                    @Override public void visit(String ignored, Object value) { items.add(value); }
                    @Override public AnnotationVisitor visitAnnotation(String ignored, String descriptor) {
                        Map<String, Object> nested = new LinkedHashMap<>();
                        items.add(nested);
                        return annotation(nested);
                    }
                };
            }
        };
    }

    private record Step(int opcode, String owner, String name) { }
    private static final class Shape {
        private String parent;
        private final Map<String, Map<String, Object>> annotations = new LinkedHashMap<>();
        private final List<Method> methods = new ArrayList<>();
        Method method(String name) {
            List<Method> matches = methods.stream().filter(method -> method.name.equals(name)).toList();
            assertEquals(1, matches.size(), name);
            return matches.get(0);
        }
    }
    private static final class Method {
        private final String name;
        private final String descriptor;
        private final int access;
        private final List<Step> steps = new ArrayList<>();
        private final List<Handle> handles = new ArrayList<>();
        private final Map<String, Map<String, Object>> annotations = new LinkedHashMap<>();
        private Method(String name, String descriptor, int access) {
            this.name = name;
            this.descriptor = descriptor;
            this.access = access;
        }
        boolean hasCall(String owner, String name) {
            return steps.stream().anyMatch(step -> owner.equals(step.owner) && name.equals(step.name));
        }
        int call(String owner, String name) { return index(-1, owner, name); }
        int field(int opcode, String name) { return index(opcode, null, name); }
        private int index(int opcode, String owner, String name) {
            for (int index = 0; index < steps.size(); index++) {
                Step step = steps.get(index);
                if ((opcode < 0 || step.opcode == opcode) && (owner == null || owner.equals(step.owner))
                        && name.equals(step.name)) return index;
            }
            throw new AssertionError("Missing operation " + owner + "#" + name);
        }
    }
}
