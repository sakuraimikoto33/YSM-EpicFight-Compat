package net.okitsu.ysmepicfightcompat.animation;

import com.google.gson.JsonParser;
import net.okitsu.ysmepicfightcompat.geometry.GeometryDocument;
import net.okitsu.ysmepicfightcompat.mesh.AuxiliaryBoneLayout;
import org.junit.jupiter.api.Test;
import yesman.epicfight.api.utils.math.OpenMatrix4f;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigurationExpressionPoseTest {
    private static final String MODEL = "test:configuration_expression";
    private static final String VARIABLE = "v.expression";
    private static final int SLOT = ExpressionEngine.slot(VARIABLE);

    @Test
    void pendingLocalConfigurationChangesSwitchPoseAtTheSameAnimationTime() {
        ModelConfigurationOverrides local = new ModelConfigurationOverrides();
        Fallback fallback = new Fallback();
        ConfiguredEnvironment environment = new ConfiguredEnvironment(local, fallback);
        Fixture fixture = fixture();

        for (double value : new double[]{0.0D, 1.0D, 0.0D}) {
            Map<String, Double> changed = local.evaluate(MODEL,
                    "variable.expression=" + value + ";", fallback);

            assertEquals(Map.of(VARIABLE, value), changed);
            assertEquals(changed, local.pendingChanges());
            assertPose(fixture, environment, value);
        }
        assertEquals(7.0D, fallback.readVariable(SLOT));
    }

    @Test
    void acceptedRemoteSnapshotsSwitchPoseAtTheSameAnimationTime() {
        ConfigurationVariableOverrides sender = new ConfigurationVariableOverrides();
        ModelConfigurationOverrides remote = new ModelConfigurationOverrides();
        Fallback fallback = new Fallback();
        ConfiguredEnvironment environment = new ConfiguredEnvironment(remote, fallback);
        Fixture fixture = fixture();

        for (double value : new double[]{0.0D, 1.0D, 0.0D}) {
            Map<String, Double> snapshot = sender.evaluate(
                    "variable.expression=" + value + ";", fallback);
            remote.accept(MODEL, snapshot);

            assertTrue(remote.pendingChanges().isEmpty());
            assertPose(fixture, environment, value);
        }
        assertEquals(7.0D, fallback.readVariable(SLOT));
    }

    private static void assertPose(Fixture fixture, ConfiguredEnvironment environment,
                                   double value) {
        assertTrue(environment.hasVariable(SLOT));
        assertEquals(value, environment.readVariable(SLOT));
        ParallelAnimationProgram.Frame frame = fixture.program().sampleAt(0.0D, environment);
        assertEquals(Set.of(value == 0.0D ? "variant_b" : "variant_a"), frame.hiddenBones());
        assertScale(frame.parallelDeltas()[fixture.first()], value == 0.0D ? 1.0F : 0.0F);
        assertScale(frame.parallelDeltas()[fixture.second()], value == 1.0D ? 1.0F : 0.0F);
    }

    private static void assertScale(OpenMatrix4f matrix, float expected) {
        assertEquals(expected, matrix.m00, 0.00001F);
        assertEquals(expected, matrix.m11, 0.00001F);
        assertEquals(expected, matrix.m22, 0.00001F);
    }

    private static Fixture fixture() {
        GeometryDocument geometry = new GeometryDocument();
        geometry.add(new GeometryDocument.Bone("Head"));
        for (String name : new String[]{"variant_a", "variant_b"}) {
            GeometryDocument.Bone child = new GeometryDocument.Bone(name);
            child.parentName("Head");
            geometry.add(child);
        }
        geometry.linkHierarchy();
        AnimationClip clip = BedrockAnimationParser.parse("parallel0", JsonParser.parseString("""
                {
                  "loop": true,
                  "bones": {
                    "variant_a": {"scale": "v.expression == 0 ? 1 : 0"},
                    "variant_b": {"scale": "v.expression == 1 ? 1 : 0"}
                  }
                }
                """).getAsJsonObject());
        AuxiliaryBoneLayout layout = AuxiliaryBoneLayout.create(geometry);
        return new Fixture(new ParallelAnimationProgram(geometry, Map.of(clip.name(), clip),
                layout, 1.0F, 1.0F), layout.entryForBoneName("variant_a").auxiliaryIndex(),
                layout.entryForBoneName("variant_b").auxiliaryIndex());
    }

    private record Fixture(ParallelAnimationProgram program, int first, int second) { }

    /** Supplies the model-scoped configuration values to the isolated pose evaluator. */
    private record ConfiguredEnvironment(ModelConfigurationOverrides configuration, Fallback fallback)
            implements ExpressionEngine.Environment {
        @Override public double readVariable(int slot) {
            ConfigurationVariableOverrides.Lookup value = configuration.lookup(MODEL, slot);
            return value.present() ? value.value() : fallback.readVariable(slot);
        }
        @Override public boolean hasVariable(int slot) {
            return configuration.lookup(MODEL, slot).present() || fallback.hasVariable(slot);
        }
        @Override public void writeVariable(int slot, double value) { fallback.writeVariable(slot, value); }
        @Override public double readQuery(int slot) { return fallback.readQuery(slot); }
        @Override public double invoke(String name, double[] arguments) { return fallback.invoke(name, arguments); }
        @Override public double invokeWithText(String name, String[] arguments) {
            return fallback.invokeWithText(name, arguments);
        }
    }

    private static final class Fallback implements ExpressionEngine.Environment {
        private final Map<Integer, Double> values = new HashMap<>(Map.of(SLOT, 7.0D));
        @Override public double readVariable(int slot) { return values.getOrDefault(slot, 0.0D); }
        @Override public boolean hasVariable(int slot) { return values.containsKey(slot); }
        @Override public void writeVariable(int slot, double value) { values.put(slot, value); }
        @Override public double readQuery(int slot) { return 0.0D; }
        @Override public double invoke(String name, double[] arguments) { return 0.0D; }
        @Override public double invokeWithText(String name, String[] arguments) { return 0.0D; }
    }
}
