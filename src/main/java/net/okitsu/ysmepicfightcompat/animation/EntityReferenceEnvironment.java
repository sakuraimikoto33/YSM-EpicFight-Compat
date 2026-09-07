package net.okitsu.ysmepicfightcompat.animation;

import java.util.Locale;
import java.util.Set;

/** Read-only entity properties, without model-private variables, input, scripts or outputs. */
final class EntityReferenceEnvironment implements ExpressionEngine.Environment {
    private static final Set<String> YSM_VALUES = Set.of(
            "ysm.head_yaw", "ysm.head_pitch", "ysm.weather", "ysm.dimension_name",
            "ysm.ground_speed2", "ysm.in_shield_block_cooldown",
            "ysm.is_passenger", "ysm.is_sleep", "ysm.is_sneak", "ysm.is_open_air",
            "ysm.eye_in_water", "ysm.frozen_ticks", "ysm.air_supply", "ysm.has_helmet",
            "ysm.has_chest_plate", "ysm.has_leggings", "ysm.has_boots", "ysm.has_mainhand",
            "ysm.has_offhand", "ysm.has_elytra", "ysm.is_riptide", "ysm.armor_value",
            "ysm.on_ladder", "ysm.ladder_facing", "ysm.arrow_count", "ysm.stinger_count",
            "ysm.food_level", "ysm.has_left_shoulder_parrot", "ysm.has_right_shoulder_parrot",
            "ysm.left_shoulder_parrot_variant", "ysm.right_shoulder_parrot_variant",
            "ysm.is_player", "ysm.is_maid", "ysm.entity_type", "ysm.mainhand_charged_crossbow",
            "ysm.offhand_charged_crossbow", "ysm.is_fishing", "ysm.xxa", "ysm.yya", "ysm.zza",
            "ysm.block_light", "ysm.sky_light", "ysm.swinging", "ysm.swing_time",
            "ysm.swinging_arm", "ysm.attack_time", "ysm.projectile_owner");
    private static final Set<String> FUNCTIONS = Set.of(
            "query.position", "query.position_delta", "query.rotation_to_camera",
            "query.biome_has_all_tags", "query.biome_has_any_tag", "query.biome_has_any_tags",
            "query.relative_block_has_all_tags", "query.relative_block_has_any_tag",
            "query.relative_block_has_any_tags", "query.is_item_name_any",
            "query.equipped_item_all_tags", "query.equipped_item_any_tag",
            "query.equipped_item_any_tags", "query.max_durability", "query.remaining_durability",
            "ysm.equipped_enchantment_level", "ysm.effect_level", "ysm.relative_block_name",
            "ysm.relative_block_name_any", "ysm.mod_version", "ysm.perlin_noise");

    private final ExpressionEngine.Environment delegate;

    EntityReferenceEnvironment(ExpressionEngine.Environment delegate) {
        this.delegate = java.util.Objects.requireNonNull(delegate);
    }

    @Override public double readVariable(int slot) { return 0.0D; }
    @Override public Object readVariableValue(int slot) { return null; }
    @Override public boolean hasVariable(int slot) { return false; }
    @Override public void writeVariable(int slot, double value) { }
    @Override public void writeVariableValue(int slot, Object value) { }

    @Override
    public Object readQueryValue(int slot) {
        String name = ExpressionEngine.slotName(slot);
        if (name.equals("math.pi") || name.equals("math.e")
                || name.startsWith("query.") && !name.equals("query.debug_output")
                || YSM_VALUES.contains(name)) {
            return delegate.readQueryValue(slot);
        }
        return null;
    }

    @Override public double readQuery(int slot) {
        return ExpressionEngine.number(readQueryValue(slot));
    }

    @Override
    public Object invokeValue(String name, Object[] arguments) {
        String canonical = name.toLowerCase(Locale.ROOT);
        if (canonical.startsWith("q.")) canonical = "query." + canonical.substring(2);
        return canonical.startsWith("math.") || FUNCTIONS.contains(canonical)
                ? delegate.invokeValue(canonical, arguments) : 0.0D;
    }

    @Override public double invoke(String name, double[] arguments) {
        Object[] values = new Object[arguments.length];
        for (int index = 0; index < arguments.length; index++) values[index] = arguments[index];
        return ExpressionEngine.number(invokeValue(name, values));
    }

    @Override public double invokeWithText(String name, String[] arguments) {
        return ExpressionEngine.number(invokeValue(name, arguments));
    }
}
