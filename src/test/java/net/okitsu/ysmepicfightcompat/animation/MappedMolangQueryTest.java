package net.okitsu.ysmepicfightcompat.animation;

import net.okitsu.ysmmapping.api.YsmMethodSymbol;
import org.junit.jupiter.api.Test;

import java.lang.invoke.MethodType;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MappedMolangQueryTest {
    private static final ClassLoader LOADER = MappedMolangQueryTest.class.getClassLoader();

    @Test
    void samplesTheExactPrivateStaticMethodWithoutConstructingContext() {
        Capability capability = new Capability(1.25F);
        int constructions = Context.constructions;
        MappedMolangQuery query = resolve("readPublic", PublicInput.class,
                PublicInput.class, "context", Context.class).orElseThrow();
        MappedMolangQuery.Bound bound = query.bind(capability).orElseThrow();

        assertEquals(1.25D, bound.sample().orElseThrow());
        assertSame(capability, Functions.lastPublic.context());
        PublicInput originalProxy = Functions.lastPublic;
        capability.value = -3.5F;
        assertEquals(-3.5D, bound.sample().orElseThrow());
        assertSame(originalProxy, Functions.lastPublic);
        assertEquals(constructions, Context.constructions);
    }

    @Test
    void supportsANonPublicInputInterface() {
        MappedMolangQuery.Bound bound = resolve("readHidden", HiddenInput.class,
                HiddenInput.class, "context", Context.class).orElseThrow()
                .bind(new Capability(4.5F)).orElseThrow();

        assertEquals(4.5D, bound.sample().orElseThrow());
    }

    @Test
    void supportsAPrivateNestedInputInterface() {
        MappedMolangQuery.Bound bound = resolve("readPrivate", PrivateInput.class,
                PrivateInput.class, "context", Context.class).orElseThrow()
                .bind(new Capability(6.5F)).orElseThrow();

        assertEquals(6.5D, bound.sample().orElseThrow());
    }

    @Test
    void supportsAnAccessorDeclaredByAParentInterface() {
        MappedMolangQuery.Bound bound = resolve("readChild", ChildInput.class,
                PublicInput.class, "context", Context.class).orElseThrow()
                .bind(new Capability(2.5F)).orElseThrow();

        assertEquals(2.5D, bound.sample().orElseThrow());
    }

    @Test
    void supportsAnExactGetterRedeclarationInTheInputSubInterface() {
        MappedMolangQuery.Bound bound = resolve("readRedeclared", RedeclaredInput.class,
                PublicInput.class, "context", Context.class).orElseThrow()
                .bind(new Capability(5.5F)).orElseThrow();

        assertEquals(5.5D, bound.sample().orElseThrow());
    }

    @Test
    void acceptsAnExistingSubclassOfAnAbstractContextReturnType() {
        MappedMolangQuery.Bound bound = resolve("readAbstract", AbstractInput.class,
                AbstractInput.class, "context", AbstractContext.class).orElseThrow()
                .bind(new ConcreteContext(7.5F)).orElseThrow();

        assertEquals(7.5D, bound.sample().orElseThrow());
    }

    @Test
    void objectMethodsUseProxyIdentityWithoutDelegatingToTheContext() {
        Capability capability = new Capability(8.0F);
        MappedMolangQuery query = resolve("readPublic", PublicInput.class,
                PublicInput.class, "context", Context.class).orElseThrow();
        assertTrue(query.bind(capability).orElseThrow().sample().isPresent());
        PublicInput first = Functions.lastPublic;
        assertTrue(query.bind(capability).orElseThrow().sample().isPresent());
        PublicInput second = Functions.lastPublic;

        assertTrue(first.equals(first));
        assertFalse(first.equals(second));
        assertFalse(first.equals(capability));
        assertFalse(first.equals(null));
        assertEquals(System.identityHashCode(first), first.hashCode());
        assertEquals("MappedMolangQuery.Proxy@"
                + Integer.toHexString(System.identityHashCode(first)), first.toString());
    }

    @Test
    void unknownAndDefaultMethodsAreExplicitlyUnsupported() {
        MappedMolangQuery query = resolve("readExtended", ExtendedInput.class,
                ExtendedInput.class, "context", Context.class).orElseThrow();
        assertTrue(query.bind(new Capability(9.0F)).orElseThrow().sample().isPresent());
        ExtendedInput input = Functions.lastExtended;

        UnsupportedOperationException unknown = assertThrows(UnsupportedOperationException.class,
                input::otherContext);
        UnsupportedOperationException defaultMethod = assertThrows(
                UnsupportedOperationException.class, input::defaultValue);
        assertEquals("Unsupported mapped Molang query input operation", unknown.getMessage());
        assertEquals(unknown.getMessage(), defaultMethod.getMessage());
    }

    @Test
    void invokingAnUnsupportedGetterMakesTheSampleMissing() {
        MappedMolangQuery.Bound bound = resolve("readUnsupported", ExtendedInput.class,
                ExtendedInput.class, "context", Context.class).orElseThrow()
                .bind(new Capability(10.0F)).orElseThrow();

        assertTrue(bound.sample().isEmpty());
    }

    @Test
    void foreignAndNullContextsCannotBind() {
        MappedMolangQuery query = resolve("readPublic", PublicInput.class,
                PublicInput.class, "context", Context.class).orElseThrow();

        assertTrue(query.bind(new Object()).isEmpty());
        assertTrue(query.bind(null).isEmpty());
        assertTrue(query.bind(new ConcreteContext(1.0F)).isEmpty());
    }

    @Test
    void rejectsEveryNonFiniteResultWithoutChangingFiniteValues() {
        Capability capability = new Capability(0.0F);
        MappedMolangQuery.Bound bound = resolve("readPublic", PublicInput.class,
                PublicInput.class, "context", Context.class).orElseThrow()
                .bind(capability).orElseThrow();
        for (float value : new float[]{Float.NaN, Float.POSITIVE_INFINITY,
                Float.NEGATIVE_INFINITY}) {
            capability.value = value;
            assertTrue(bound.sample().isEmpty());
        }
        for (float value : new float[]{0.0F, -0.0F, -2.0F, Float.MIN_VALUE, Float.MAX_VALUE}) {
            capability.value = value;
            assertEquals(Double.doubleToRawLongBits((double) value),
                    Double.doubleToRawLongBits(bound.sample().orElseThrow()));
        }
    }

    @Test
    void checkedExceptionsRuntimeExceptionsAndOrdinaryErrorsBecomeMissing() {
        for (String name : new String[]{"throwsChecked", "throwsRuntime", "throwsAssertion"}) {
            MappedMolangQuery.Bound bound = resolve(name, PublicInput.class,
                    PublicInput.class, "context", Context.class).orElseThrow()
                    .bind(new Capability(1.0F)).orElseThrow();
            assertTrue(bound.sample().isEmpty());
        }
    }

    @Test
    void queryMustBeStaticNonNativeAndOwnedByAClass() {
        for (String name : new String[]{"instanceQuery", "nativeQuery"}) {
            assertTrue(resolve(name, PublicInput.class, PublicInput.class,
                    "context", Context.class).isEmpty());
        }
        assertTrue(MappedMolangQuery.resolve(symbol(InterfaceFunctions.class,
                        "query", float.class, PublicInput.class), accessor(), LOADER).isEmpty());
    }

    @Test
    void queryMustHaveExactlyOneInterfaceParameterAndPrimitiveFloatReturn() {
        for (YsmMethodSymbol query : new YsmMethodSymbol[]{
                symbol(Functions.class, "noArguments", float.class),
                symbol(Functions.class, "twoArguments", float.class,
                        PublicInput.class, PublicInput.class),
                symbol(Functions.class, "concreteArgument", float.class, Context.class),
                symbol(Functions.class, "boxedReturn", Float.class, PublicInput.class),
                symbol(Functions.class, "doubleReturn", double.class, PublicInput.class)}) {
            assertTrue(MappedMolangQuery.resolve(query, accessor(), LOADER).isEmpty());
        }
    }

    @Test
    void queryDescriptorMustMatchItsActualReturnTypeAndParametersExactly() {
        assertTrue(MappedMolangQuery.resolve(symbol(Functions.class, "doubleReturn",
                float.class, PublicInput.class), accessor(), LOADER).isEmpty());
        assertTrue(MappedMolangQuery.resolve(symbol(Functions.class, "readPublic",
                float.class, HiddenInput.class), accessor(), LOADER).isEmpty());
    }

    @Test
    void mappedOwnersMustDeclareTheExactMembersWithoutInheritedMemberSearch() {
        assertTrue(MappedMolangQuery.resolve(symbol(InheritedFunctions.class, "inherited",
                float.class, PublicInput.class), accessor(), LOADER).isEmpty());
        assertTrue(resolve("readChild", ChildInput.class, ChildInput.class,
                "context", Context.class).isEmpty());
    }

    @Test
    void accessorOwnerMustBeTheInputInterfaceOrItsParent() {
        assertTrue(resolve("readPublic", PublicInput.class, ConcreteAccessor.class,
                "context", Context.class).isEmpty());
        assertTrue(resolve("readPublic", PublicInput.class, ForeignInput.class,
                "context", Context.class).isEmpty());
        assertTrue(resolve("readPublic", PublicInput.class, RedeclaredInput.class,
                "context", Context.class).isEmpty());
    }

    @Test
    void accessorMustBeAnAbstractInstanceGetterWithAnExactClassReturnType() {
        for (YsmMethodSymbol accessor : new YsmMethodSymbol[]{
                symbol(AccessorShapes.class, "staticContext", Context.class),
                symbol(AccessorShapes.class, "defaultContext", Context.class),
                symbol(AccessorShapes.class, "withArgument", Context.class, int.class),
                symbol(AccessorShapes.class, "primitiveContext", float.class),
                symbol(AccessorShapes.class, "arrayContext", Context[].class),
                symbol(AccessorShapes.class, "interfaceContext", Runnable.class),
                symbol(AccessorShapes.class, "context", Object.class)}) {
            assertTrue(MappedMolangQuery.resolve(symbol(Functions.class, "readShapes",
                    float.class, AccessorShapes.class), accessor, LOADER).isEmpty());
        }
    }

    @Test
    void objectIdentityMethodsCannotDoubleAsTheMappedContextGetter() {
        assertTrue(resolve("readText", TextInput.class, TextInput.class,
                "toString", String.class).isEmpty());
    }

    @Test
    void missingSymbolsMalformedDescriptorsAndUnresolvableLoadersBecomeMissing() {
        YsmMethodSymbol query = symbol(Functions.class, "readPublic", float.class,
                PublicInput.class);
        assertTrue(MappedMolangQuery.resolve(null, accessor(), LOADER).isEmpty());
        assertTrue(MappedMolangQuery.resolve(query, null, LOADER).isEmpty());
        assertTrue(MappedMolangQuery.resolve(new YsmMethodSymbol(query.owner(),
                "missing", query.descriptor()), accessor(), LOADER).isEmpty());
        assertTrue(MappedMolangQuery.resolve(new YsmMethodSymbol(query.owner(),
                query.name(), "(bad"), accessor(), LOADER).isEmpty());
        assertTrue(MappedMolangQuery.resolve(query, new YsmMethodSymbol(accessor().owner(),
                "context", "(bad"), LOADER).isEmpty());
        assertTrue(MappedMolangQuery.resolve(query, accessor(), new ClassLoader(null) { })
                .isEmpty());
    }

    private static Optional<MappedMolangQuery> resolve(String query, Class<?> input,
            Class<?> accessorOwner, String accessor, Class<?> context) {
        return MappedMolangQuery.resolve(symbol(Functions.class, query, float.class, input),
                symbol(accessorOwner, accessor, context), LOADER);
    }

    private static YsmMethodSymbol accessor() {
        return symbol(PublicInput.class, "context", Context.class);
    }

    private static YsmMethodSymbol symbol(Class<?> owner, String name, Class<?> result,
            Class<?>... parameters) {
        return new YsmMethodSymbol(owner.getName().replace('.', '/'), name,
                MethodType.methodType(result, parameters).toMethodDescriptorString());
    }

    public static class Context {
        private static int constructions;
        float value;

        Context(float value) {
            constructions++;
            this.value = value;
        }

        @Override
        public boolean equals(Object other) {
            throw new AssertionError("Context equality must not be invoked");
        }

        @Override
        public int hashCode() {
            throw new AssertionError("Context hash must not be invoked");
        }

        @Override
        public String toString() {
            throw new AssertionError("Context text must not be invoked");
        }
    }

    public static final class Capability extends Context {
        Capability(float value) {
            super(value);
        }
    }

    public abstract static class AbstractContext {
        abstract float value();
    }

    public static final class ConcreteContext extends AbstractContext {
        private final float value;

        ConcreteContext(float value) {
            this.value = value;
        }

        @Override
        float value() {
            return value;
        }
    }

    public interface PublicInput {
        Context context();
    }

    interface HiddenInput {
        Context context();
    }

    private interface PrivateInput {
        Context context();
    }

    public interface ChildInput extends PublicInput { }

    public interface RedeclaredInput extends PublicInput {
        @Override
        Context context();
    }

    public interface AbstractInput {
        AbstractContext context();
    }

    public interface ExtendedInput {
        Context context();
        Context otherContext();

        default float defaultValue() {
            throw new AssertionError("Default method must not be executed");
        }
    }

    public interface ForeignInput {
        Context context();
    }

    public interface TextInput {
        String toString();
    }

    public interface AccessorShapes {
        Context context();
        Context withArgument(int value);
        float primitiveContext();
        Context[] arrayContext();
        Runnable interfaceContext();

        static Context staticContext() {
            throw new AssertionError("Static getter must not be executed");
        }

        default Context defaultContext() {
            throw new AssertionError("Default getter must not be executed");
        }
    }

    public static final class ConcreteAccessor {
        public Context context() {
            throw new AssertionError("Concrete accessor must not be executed");
        }
    }

    public interface InterfaceFunctions {
        static float query(PublicInput input) {
            return input.context().value;
        }
    }

    private static class BaseFunctions {
        static float inherited(PublicInput input) {
            return input.context().value;
        }
    }

    private static final class InheritedFunctions extends BaseFunctions { }

    private static final class Functions {
        private static PublicInput lastPublic;
        private static ExtendedInput lastExtended;

        private static float readPublic(PublicInput input) {
            lastPublic = input;
            return input.context().value;
        }

        private static float readHidden(HiddenInput input) {
            return input.context().value;
        }

        private static float readPrivate(PrivateInput input) {
            return input.context().value;
        }

        private static float readChild(ChildInput input) {
            return input.context().value;
        }

        private static float readRedeclared(RedeclaredInput input) {
            return input.context().value;
        }

        private static float readAbstract(AbstractInput input) {
            return input.context().value();
        }

        private static float readExtended(ExtendedInput input) {
            lastExtended = input;
            return input.context().value;
        }

        private static float readUnsupported(ExtendedInput input) {
            return input.otherContext().value;
        }

        private static float readShapes(AccessorShapes input) {
            return input.context().value;
        }

        private static float readText(TextInput input) {
            return input.toString().length();
        }

        private static float throwsChecked(PublicInput input) throws Exception {
            throw new Exception("Synthetic runtime detail must not escape");
        }

        private static float throwsRuntime(PublicInput input) {
            throw new IllegalStateException("Synthetic runtime detail must not escape");
        }

        private static float throwsAssertion(PublicInput input) {
            throw new AssertionError("Synthetic runtime detail must not escape");
        }

        private float instanceQuery(PublicInput input) {
            return input.context().value;
        }

        private static native float nativeQuery(PublicInput input);

        private static float noArguments() {
            return 1.0F;
        }

        private static float twoArguments(PublicInput first, PublicInput second) {
            return first.context().value + second.context().value;
        }

        private static float concreteArgument(Context context) {
            return context.value;
        }

        private static Float boxedReturn(PublicInput input) {
            return input.context().value;
        }

        private static double doubleReturn(PublicInput input) {
            return input.context().value;
        }
    }
}
