package net.okitsu.ysmepicfightcompat.animation;

import net.okitsu.ysmmapping.api.YsmMethodSymbol;
import org.junit.jupiter.api.Test;

import java.lang.invoke.MethodType;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MappedClientModelSelectionTest {
    private static final ClassLoader LOADER = MappedClientModelSelectionTest.class.getClassLoader();

    @Test
    void inheritedGettersReadTheLiveModelAndDisabledStateOnEveryCall() {
        MappedClientModelSelection selection = selection();
        ClientState context = new ClientState();
        context.id = "test:model_a";
        assertEquals("test:model_a", selection.modelId(context));

        context.id = "test:model_b";
        assertEquals("test:model_b", selection.modelId(context));
        context.disabled = true;
        assertNull(selection.modelId(context));
        context.disabled = false;
        assertEquals("test:model_b", selection.modelId(context));
    }

    @Test
    void nullEmptyBlankAndForeignContextsHaveNoSelectedModel() {
        MappedClientModelSelection selection = selection();
        ClientState context = new ClientState();
        for (String id : new String[]{null, "", " ", "\t\r\n"}) {
            context.id = id;
            assertNull(assertDoesNotThrow(() -> selection.modelId(context)));
        }
        assertNull(assertDoesNotThrow(() -> selection.modelId(null)));
        assertNull(assertDoesNotThrow(() -> selection.modelId(new Object())));
        assertNull(assertDoesNotThrow(() -> selection.modelId(new Shapes())));
    }

    @Test
    void unavailableSymbolsOwnersMembersAndLoadersProduceNoBinding() {
        assertUnavailable(null, disabled());
        assertUnavailable(modelId(), null);
        assertUnavailable(new YsmMethodSymbol("synthetic/missing/Selection", "modelId",
                "()Ljava/lang/String;"), disabled());
        assertUnavailable(symbol(State.class, "missing", String.class), disabled());
        assertUnavailable(modelId(), symbol(State.class, "missing", boolean.class));
        assertUnavailable(new YsmMethodSymbol(modelId().owner(), "modelId", "(bad"), disabled());
        assertUnavailable(modelId(), new YsmMethodSymbol(disabled().owner(), "disabled", "(bad"));
        assertTrue(assertDoesNotThrow(() -> MappedClientModelSelection.resolve(
                modelId(), disabled(), new ClassLoader(null) { })).isEmpty());
    }

    @Test
    void gettersRequireExactZeroArgumentInstanceMethodContracts() {
        YsmMethodSymbol validId = symbol(Shapes.class, "modelId", String.class);
        YsmMethodSymbol validDisabled = symbol(Shapes.class, "disabled", boolean.class);
        for (YsmMethodSymbol id : new YsmMethodSymbol[]{
                symbol(Shapes.class, "idWithArgument", String.class, int.class),
                symbol(Shapes.class, "objectId", Object.class),
                symbol(Shapes.class, "staticId", String.class),
                symbol(Shapes.class, "objectId", String.class),
                symbol(Shapes.class, "modelId", String.class, int.class)}) {
            assertUnavailable(id, validDisabled);
        }
        for (YsmMethodSymbol disabled : new YsmMethodSymbol[]{
                symbol(Shapes.class, "disabledWithArgument", boolean.class, int.class),
                symbol(Shapes.class, "boxedDisabled", Boolean.class),
                symbol(Shapes.class, "staticDisabled", boolean.class),
                symbol(Shapes.class, "boxedDisabled", boolean.class),
                symbol(Shapes.class, "disabled", boolean.class, int.class)}) {
            assertUnavailable(validId, disabled);
        }
    }

    @Test
    void eitherGetterCanFailWithoutThrowingOrCachingAnOldSelection() {
        MappedClientModelSelection selection = selection();
        ClientState context = new ClientState();
        context.id = "test:model_a";
        assertEquals(context.id, selection.modelId(context));

        for (Throwable failure : new Throwable[]{new Exception("synthetic checked failure"),
                new IllegalStateException("synthetic runtime failure"),
                new AssertionError("synthetic assertion failure"),
                new LinkageError("synthetic linkage failure")}) {
            context.idFailure = failure;
            assertNull(assertDoesNotThrow(() -> selection.modelId(context)));
            context.idFailure = null;
            context.disabledFailure = failure;
            assertNull(assertDoesNotThrow(() -> selection.modelId(context)));
            context.disabledFailure = null;
        }
        context.id = "test:model_b";
        assertEquals(context.id, selection.modelId(context));
    }

    private static MappedClientModelSelection selection() {
        return MappedClientModelSelection.resolve(modelId(), disabled(), LOADER).orElseThrow();
    }

    private static YsmMethodSymbol modelId() {
        return symbol(State.class, "modelId", String.class);
    }

    private static YsmMethodSymbol disabled() {
        return symbol(State.class, "disabled", boolean.class);
    }

    private static void assertUnavailable(YsmMethodSymbol id, YsmMethodSymbol disabled) {
        assertTrue(assertDoesNotThrow(() -> MappedClientModelSelection.resolve(
                id, disabled, LOADER)).isEmpty());
    }

    private static YsmMethodSymbol symbol(Class<?> owner, String name, Class<?> result,
                                          Class<?>... parameters) {
        return new YsmMethodSymbol(owner.getName().replace('.', '/'), name,
                MethodType.methodType(result, parameters).toMethodDescriptorString());
    }

    public static class State {
        String id;
        boolean disabled;
        Throwable idFailure;
        Throwable disabledFailure;

        public String modelId() throws Throwable {
            if (idFailure != null) throw idFailure;
            return id;
        }

        public boolean disabled() throws Throwable {
            if (disabledFailure != null) throw disabledFailure;
            return disabled;
        }
    }

    public static final class ClientState extends State { }

    public static final class Shapes {
        public String modelId() { return "test:foreign"; }
        public boolean disabled() { return false; }
        public String idWithArgument(int ignored) { return "test:foreign"; }
        public boolean disabledWithArgument(int ignored) { return false; }
        public Object objectId() { return "test:foreign"; }
        public Boolean boxedDisabled() { return false; }
        public static String staticId() { throw new AssertionError("Static getter must not run"); }
        public static boolean staticDisabled() { throw new AssertionError("Static getter must not run"); }
    }
}
