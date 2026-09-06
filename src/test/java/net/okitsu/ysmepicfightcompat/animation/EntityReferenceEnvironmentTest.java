package net.okitsu.ysmepicfightcompat.animation;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EntityReferenceEnvironmentTest {
    @Test
    void readsEntityQueriesAndOfficialOwnerExampleWithTypedEquipmentArguments() {
        ProbeEnvironment host = new ProbeEnvironment();
        EntityReferenceEnvironment reference = new EntityReferenceEnvironment(host);
        assertEquals(12.0D, reference.readQuery(ExpressionEngine.querySlot("query.health")));
        assertEquals(12.0D, reference.readQuery(ExpressionEngine.querySlot("ysm.armor_value")));
        assertEquals(3.0D, reference.invokeValue("ysm.equipped_enchantment_level",
                new Object[]{"Mainhand", "minecraft:flame"}));
        assertEquals(List.of("Mainhand", "minecraft:flame"), host.arguments);
        assertEquals(3.0D, reference.invokeValue("q.position", new Object[]{1.0D}));
        assertEquals("query.position", host.functions.get(host.functions.size() - 1));
    }

    @Test
    void deniesPrivateVariablesScriptsControllersInputAndModelOutputs() {
        ProbeEnvironment host = new ProbeEnvironment();
        EntityReferenceEnvironment reference = new EntityReferenceEnvironment(host);
        int variable = ExpressionEngine.slot("v.roaming.private");
        assertNull(reference.readVariableValue(variable));
        assertFalse(reference.hasVariable(variable));
        reference.writeVariableValue(variable, 20.0D);
        reference.writeVariable(variable, 20.0D);
        assertEquals(0, host.writes);
        for (String name : List.of("fn.other", "ctrl.set_animation", "ctrl.sync",
                "ysm.play_sound", "ysm.stop_sound", "ysm.stop_all_sounds", "ysm.particle",
                "ysm.abs_particle", "ysm.first_order", "ysm.second_order", "ysm.mouse",
                "ysm.keyboard", "query.debug_output", "q.debug_output")) {
            assertEquals(0.0D, reference.invokeValue(name, new Object[]{"test"}), name);
        }
        for (String name : List.of("ctrl.playing_extra_animation", "ysm.hit_target_id",
                "ysm.texture_name", "context.private", "ysm.rendering_in_inventory")) {
            assertNull(reference.readQueryValue(ExpressionEngine.querySlot(name)), name);
        }
        assertTrue(host.functions.isEmpty());
        assertEquals(0, host.queryReads);
    }

    @Test
    void mathStillEvaluatesButUnknownCallsDoNotReachTheHost() {
        ProbeEnvironment host = new ProbeEnvironment();
        EntityReferenceEnvironment reference = new EntityReferenceEnvironment(host);
        assertEquals(3.0D, reference.invoke("math.sin", new double[]{30.0D}));
        assertEquals(0.0D, reference.invokeWithText("unknown.execute", new String[]{"test"}));
        assertEquals(List.of("math.sin"), host.functions);
    }

    private static final class ProbeEnvironment implements ExpressionEngine.Environment {
        private final List<String> functions = new ArrayList<>();
        private List<Object> arguments = List.of();
        private int writes;
        private int queryReads;
        @Override public double readVariable(int slot) { return 99; }
        @Override public boolean hasVariable(int slot) { return true; }
        @Override public void writeVariable(int slot, double value) { writes++; }
        @Override public double readQuery(int slot) { queryReads++; return 12; }
        @Override public Object invokeValue(String name, Object[] arguments) {
            functions.add(name);
            this.arguments = List.of(arguments.clone());
            return 3.0D;
        }
        @Override public double invoke(String name, double[] arguments) { throw new AssertionError(); }
        @Override public double invokeWithText(String name, String[] arguments) { throw new AssertionError(); }
    }
}
